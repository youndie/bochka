package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.s3.sigv4.S3Error
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Checksum

/**
 * The checksums a client can state about a body, checked while the body streams past.
 *
 * Two mechanisms, and they are not the same thing. `Content-MD5` is HTTP's, base64 of the raw
 * digest, and it predates the rest. `x-amz-checksum-<algorithm>` is S3's own
 * (`s3-service-2.json`, `PutObjectRequest.members.ChecksumCRC32` and its siblings), also base64,
 * and it is what a modern SDK sends: aws-cli v2 puts `x-amz-checksum-crc32` on every upload
 * without being asked.
 *
 * **Not the same as the signature's payload hash.** `x-amz-content-sha256` proves the bytes are
 * the ones that were signed; these prove the bytes are the ones the client meant to send. Both are
 * checked, and a body can fail either.
 *
 * The algorithms are the ones clients actually send, and that list was set by a client rather than
 * by taste. `crc64nvme` was refused by name here at first — the reasoning was that it needs a table
 * this project would have to carry and that nobody sends it unasked — and the first `aws s3 cp`
 * answered `NotImplemented`, because `aws-cli` v2 puts exactly that header on every default upload.
 * What is still refused by name is what no client was observed to send: `sha512` and the three
 * `xxhash` variants. Refused, not ignored: a stated checksum nobody verifies is worse than none,
 * because the client believes the bytes were checked.
 */
class PayloadChecksums private constructor(
    private val md5Expected: String?,
    private val algorithm: Algorithm?,
    private val stated: String?,
    /**
     * Why the head itself cannot be honoured, when it cannot.
     *
     * Carried on the object rather than returned beside it, because this is read during screening
     * and the whole point of screening is that the refusal exists before the body does (§1.2).
     */
    val rejection: Rejection? = null,
) {
    enum class Algorithm(
        val header: String,
    ) {
        CRC32("x-amz-checksum-crc32"),
        CRC32C("x-amz-checksum-crc32c"),
        CRC64NVME("x-amz-checksum-crc64nvme"),
        SHA1("x-amz-checksum-sha1"),
        SHA256("x-amz-checksum-sha256"),
        ;

        /** The suffix as it appears in the header name and is stored: `crc32c`, `sha256`. */
        val id: String get() = header.removePrefix("x-amz-checksum-")
    }

    data class Rejection(
        val error: S3Error,
        val detail: String,
    )

    /**
     * What one part of an assembled object contributes: how long it was, and what it hashed to.
     *
     * A type of its own rather than the store's `PartSummary`, so this file keeps knowing nothing
     * about the store — and the length is here because `FULL_OBJECT` cannot be computed without
     * it, which is not obvious from the name of a checksum.
     */
    data class Piece(
        val size: Long,
        val checksum: Metadata.Checksum?,
    )

    private val md5 = if (md5Expected != null) MessageDigest.getInstance("MD5") else null
    private val running: Running? = algorithm?.let(::runningFor)

    fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        md5?.update(bytes, offset, length)
        running?.update(bytes, offset, length)
    }

    /** `null` when everything the client stated about the body turned out to be true. */
    fun verify(): Rejection? {
        if (md5 != null) {
            val expected = decodeBase64(md5Expected!!, 16) ?: return Rejection(S3Error.INVALID_DIGEST, "Content-MD5")
            if (!MessageDigest.isEqual(expected, md5.digest())) {
                return Rejection(S3Error.BAD_DIGEST, "Content-MD5 does not describe the body")
            }
        }
        if (running != null) {
            val computed = Base64.getEncoder().encodeToString(running.digest())
            if (computed != stated) {
                return Rejection(
                    S3Error.BAD_DIGEST,
                    "${algorithm!!.header} said $stated, computed $computed",
                )
            }
        }
        return null
    }

    /** What goes into the index, so a later `GET` can answer with it without rereading the object. */
    fun stored(): Metadata.Checksum? = algorithm?.let { Metadata.Checksum(it.id, stated!!) }

    private interface Running {
        fun update(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        )

        fun digest(): ByteArray
    }

    companion object {
        /** Reads what the head states; [rejection] says why it cannot be honoured, when it cannot. */
        fun of(header: (String) -> String?): PayloadChecksums {
            fun refused(
                error: S3Error,
                detail: String,
            ) = PayloadChecksums(null, null, null, Rejection(error, detail))

            val md5 = header("content-md5")?.trim()?.takeIf { it.isNotEmpty() }
            if (md5 != null && decodeBase64(md5, 16) == null) {
                return refused(S3Error.INVALID_DIGEST, "Content-MD5 is not base64 of 16 bytes")
            }

            for (unsupported in UNSUPPORTED) {
                if (header(unsupported) != null) return refused(S3Error.NOT_IMPLEMENTED, unsupported)
            }

            val present = Algorithm.entries.mapNotNull { a -> header(a.header)?.trim()?.let { a to it } }
            if (present.size > 1) {
                return refused(
                    S3Error.INVALID_REQUEST,
                    "more than one checksum: ${present.joinToString { it.first.header }}",
                )
            }

            // `substringBefore('-')`, because a composite checksum is `<base64>-<count>` and a
            // client sends one back on `CompleteMultipartUpload` to state what it expects the
            // finished object to hash to. Counting the suffix as part of the value refused every
            // checksum this server had itself produced. Standard base64 has no `-` in its
            // alphabet — the URL-safe variant does, and S3 does not use it — so the split is exact
            // rather than lucky.
            val chosen = present.firstOrNull()
            if (chosen != null && decodeBase64(chosen.second.substringBefore('-'), lengthOf(chosen.first)) == null) {
                // `BadDigest`, not `InvalidRequest`. A value that cannot be decoded is a value that
                // does not describe the body — the same answer as one that decodes and disagrees,
                // because from the client's side the fact is the same: what it stated about these
                // bytes is not true of them. `InvalidRequest` would send it to check its header
                // names (`test_object_checksum_sha256`).
                return refused(S3Error.BAD_DIGEST, "${chosen.first.header} is not base64 of the right length")
            }
            return PayloadChecksums(md5, chosen?.first, chosen?.second)
        }

        /**
         * The checksum of an assembled object — one of two different answers.
         *
         * `s3-service-2.json` gives `ChecksumType` two values and they are two computations, not
         * two labels:
         *
         * * **`COMPOSITE`** — the algorithm run over the parts' **raw** checksums, base64, with
         *   `-<count>` after it. The same shape as a multipart `ETag` and for the same reason: the
         *   object's bytes never went through a single hash, so a value that looked like an
         *   ordinary checksum would be one no client could reproduce from the bytes it holds, and
         *   the suffix says which kind it is. The only possible answer for a digest.
         * * **`FULL_OBJECT`** — the checksum of the object's actual bytes, computed from the
         *   parts' checksums and lengths ([CrcCombine]) rather than by reading it back. Offered
         *   for the CRCs only, because only they compose. A client that downloads the object and
         *   checksums it gets this value, which is the point of the type existing.
         *
         * `null` when there is no answer: the parts disagree about the algorithm, name one this
         * server does not have, carry a value that is not base64, or ask for `FULL_OBJECT` of a
         * digest. Answering anyway is the failure mode this whole class exists to avoid — a stated
         * checksum nobody can reproduce is worse than none, because the client believes it.
         */
        fun ofParts(
            parts: List<Piece>,
            checksumType: String?,
        ): Metadata.Checksum? {
            if (parts.isEmpty()) return null
            val stated = parts.map { it.checksum }.takeIf { all -> all.all { it != null } } ?: return null
            val name = stated.map { it!!.algorithm }.distinct().singleOrNull() ?: return null
            val algorithm = Algorithm.entries.firstOrNull { it.id == name } ?: return null

            val raw = ArrayList<ByteArray>(parts.size)
            for (value in stated) {
                raw +=
                    try {
                        Base64.getDecoder().decode(value!!.value.substringBefore('-'))
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
            }

            if (checksumType.equals("FULL_OBJECT", ignoreCase = true)) {
                val combiner = CrcCombine.of(algorithm) ?: return null
                var value = numberOf(raw.first())
                for (index in 1 until raw.size) {
                    value = combiner.combine(value, numberOf(raw[index]), parts[index].size)
                }
                val width = raw.first().size
                val bytes = ByteArray(width) { i -> (value ushr ((width - 1 - i) * 8)).toByte() }
                return Metadata.Checksum(name, Base64.getEncoder().encodeToString(bytes))
            }

            val running = runningFor(algorithm)
            for (bytes in raw) running.update(bytes, 0, bytes.size)
            return Metadata.Checksum(name, Base64.getEncoder().encodeToString(running.digest()) + "-" + parts.size)
        }

        /** A CRC travels big-endian, which is how [ZipChecksum] wrote it; this reads it back. */
        private fun numberOf(bytes: ByteArray): Long {
            var value = 0L
            for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xFF)
            return value
        }

        /** Whether the request stated a checksum of any kind — what `DeleteObjects` requires (M-45). */
        fun anyStated(header: (String) -> String?): Boolean =
            header("content-md5") != null ||
                Algorithm.entries.any { header(it.header) != null } ||
                UNSUPPORTED.any { header(it) != null }

        private val UNSUPPORTED =
            listOf(
                "x-amz-checksum-sha512",
                "x-amz-checksum-xxhash64",
                "x-amz-checksum-xxhash3",
                "x-amz-checksum-xxhash128",
            )

        private fun lengthOf(algorithm: Algorithm) =
            when (algorithm) {
                Algorithm.CRC32, Algorithm.CRC32C -> 4
                Algorithm.CRC64NVME -> 8
                Algorithm.SHA1 -> 20
                Algorithm.SHA256 -> 32
            }

        private fun decodeBase64(
            value: String,
            expectedLength: Int,
        ): ByteArray? =
            try {
                Base64.getDecoder().decode(value).takeIf { it.size == expectedLength }
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun runningFor(algorithm: Algorithm): Running =
            when (algorithm) {
                Algorithm.CRC32 -> ZipChecksum(CRC32(), 4)
                Algorithm.CRC32C -> ZipChecksum(CRC32C(), 4)
                Algorithm.CRC64NVME -> ZipChecksum(Crc64Nvme(), 8)
                Algorithm.SHA1 -> Digest(MessageDigest.getInstance("SHA-1"))
                Algorithm.SHA256 -> Digest(MessageDigest.getInstance("SHA-256"))
            }
    }

    /**
     * A CRC arrives base64-encoded as its **big-endian** bytes, not as a decimal or hex string.
     *
     * [width] is how many of them: four for the 32-bit CRCs, eight for CRC-64/NVME. Encoding a
     * 64-bit value in four bytes would produce a well-formed header that never matches.
     */
    private class ZipChecksum(
        private val checksum: Checksum,
        private val width: Int,
    ) : Running {
        override fun update(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = checksum.update(bytes, offset, length)

        override fun digest(): ByteArray {
            val value = checksum.value
            return ByteArray(width) { i -> (value ushr ((width - 1 - i) * 8)).toByte() }
        }
    }

    private class Digest(
        private val digest: MessageDigest,
    ) : Running {
        override fun update(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = digest.update(bytes, offset, length)

        override fun digest(): ByteArray = digest.digest()
    }
}
