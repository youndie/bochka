package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.S3Error
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption with a key the client brings and the server never keeps (M26).
 *
 * Three headers, and the model names them: `PutObjectRequest` takes
 * `x-amz-server-side-encryption-customer-algorithm`, `-key` and `-key-MD5`
 * (`docs/spec/s3-service-2.json:11815`); `GetObjectOutput` gives back **two** of the three
 * (`:6385`). The absence of the key from the answer is not an omission in the model — a server
 * that returns the key has erased the whole difference between this and encrypting with its own.
 *
 * ## What is stored and what is not
 *
 * The key is used and dropped. What the index keeps is the algorithm, the MD5 of the key and the
 * initialisation vector — and each of the three is there for a reason that is not the others':
 *
 * * the **MD5** tells a right key from a wrong one. Without it a wrong key decrypts to rubbish and
 *   the client is handed rubbish instead of a refusal, which is the worst of the three outcomes;
 * * the **IV** is not a secret and cannot be derived from anything else. Deriving it from the file
 *   id was considered and rejected: the file id's job is to be a name, and a name that is also a
 *   cryptographic input can never be changed — not by compaction, not by a repair tool;
 * * the **algorithm** because a stored object has to say what it is, rather than what today's
 *   server happens to do.
 *
 * ## Why counter mode
 *
 * `AES/CTR/NoPadding`, and the two properties that decide it are both about the shape of this
 * store rather than about cryptography. The ciphertext is exactly as long as the plaintext, so the
 * size in the index stays the size of the object; and any offset can be decrypted without the
 * bytes before it, so a `Range` read stays a `Range` read instead of becoming a read of everything
 * up to the range.
 */
data class SseC(
    val algorithm: String,
    val keyBytes: ByteArray,
    val keyMd5: String,
) {
    /** A cipher positioned at [offset] bytes into the object, which is what a `Range` needs. */
    fun cipherAt(
        iv: ByteArray,
        offset: Long,
    ): Cipher {
        val block = offset / BLOCK
        val counter = iv.copyOf()
        // The counter is the IV plus the block number, big-endian over the whole sixteen bytes —
        // which is what `AES/CTR` in the JDK does internally when it walks forward, so starting it
        // here at block N gives byte N*16 the same key stream it would have had.
        var carry = block
        for (i in counter.indices.reversed()) {
            if (carry == 0L) break
            val sum = (counter[i].toLong() and 0xFF) + (carry and 0xFF)
            counter[i] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(counter))
        // Counter mode is its own inverse, so one direction covers both; what is left is the part
        // of the first block that the offset skips past.
        val skew = (offset % BLOCK).toInt()
        if (skew > 0) cipher.update(ByteArray(skew))
        return cipher
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is SseC && algorithm == other.algorithm && keyMd5 == other.keyMd5 &&
                    keyBytes.contentEquals(other.keyBytes)
            )

    override fun hashCode(): Int = keyMd5.hashCode()

    /** What went wrong with the three headers, in the shape the handler answers with. */
    class Refused(
        val error: S3Error,
        val detail: String,
    ) : RuntimeException(detail)

    companion object {
        const val ALGORITHM_HEADER = "x-amz-server-side-encryption-customer-algorithm"
        const val KEY_HEADER = "x-amz-server-side-encryption-customer-key"
        const val KEY_MD5_HEADER = "x-amz-server-side-encryption-customer-key-md5"

        /** The only algorithm S3 names for SSE-C, and the only one accepted here. */
        const val AES256 = "AES256"

        private const val BLOCK = 16
        private const val KEY_BYTES = 32

        /**
         * The three headers as one value, or null when none of them is there.
         *
         * Partial sets are refused rather than half-read: a request with an algorithm and no key is
         * not "unencrypted", it is a client that believes it is encrypting.
         */
        fun of(header: (String) -> String?): SseC? {
            val algorithm = header(ALGORITHM_HEADER)?.trim()
            val key = header(KEY_HEADER)?.trim()
            val md5 = header(KEY_MD5_HEADER)?.trim()
            if (algorithm == null && key == null && md5 == null) return null

            if (algorithm != AES256) {
                throw Refused(
                    S3Error.INVALID_ARGUMENT,
                    "the only customer encryption algorithm is $AES256, got ${algorithm ?: "nothing"}",
                )
            }
            if (key.isNullOrEmpty()) {
                throw Refused(S3Error.INVALID_ARGUMENT, "$ALGORITHM_HEADER without $KEY_HEADER")
            }
            val bytes =
                runCatching { Base64.getDecoder().decode(key) }
                    .getOrElse { throw Refused(S3Error.INVALID_ARGUMENT, "$KEY_HEADER is not base64") }
            if (bytes.size != KEY_BYTES) {
                throw Refused(S3Error.INVALID_ARGUMENT, "$AES256 needs a $KEY_BYTES-byte key, got ${bytes.size}")
            }
            val computed = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
            if (md5.isNullOrEmpty()) {
                throw Refused(S3Error.INVALID_ARGUMENT, "$KEY_HEADER without $KEY_MD5_HEADER")
            }
            // Checked here and not at the read, because a mismatch means the client made a mistake
            // now. Storing it and finding out later turns a typo into an object nobody can open.
            if (md5 != computed) {
                throw Refused(S3Error.INVALID_DIGEST, "$KEY_MD5_HEADER does not match the key it describes")
            }
            return SseC(algorithm, bytes, computed)
        }

        /** A fresh initialisation vector. Not a secret, and never reused: one per stored file. */
        fun newIv(): ByteArray = ByteArray(BLOCK).also { java.security.SecureRandom().nextBytes(it) }
    }
}
