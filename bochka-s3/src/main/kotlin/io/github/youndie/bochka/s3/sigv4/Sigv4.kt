package io.github.youndie.bochka.s3.sigv4

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The arithmetic of Signature Version 4: hashes, the derived key, the string to sign.
 *
 * Everything here is from `docs/spec/reference/botocore-auth.py` — the code `aws-cli` signs with —
 * and everything is checked against the 34 official vectors in `docs/spec/aws-sig-v4-test-suite/`.
 *
 * No crypto dependency: `MessageDigest` and `Mac` are in the JDK, and on this path they are also
 * intrinsified, which a Kotlin implementation of SHA-256 would not be.
 */
object Sigv4 {
    const val ALGORITHM: String = "AWS4-HMAC-SHA256"

    /** sha256 of the empty string, and S3 sends it for every request with no body. */
    const val EMPTY_PAYLOAD_SHA256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private const val HMAC = "HmacSHA256"
    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(StandardCharsets.UTF_8))

    /**
     * `AWS4-HMAC-SHA256\n<timestamp>\n<scope>\n<hex(sha256(canonicalRequest))>`
     * (`botocore-auth.py:405`).
     *
     * The scope date is the first eight characters of [timestamp] rather than a separately
     * formatted date. Computing it apart is correct 86 399 seconds a day and wrong at midnight.
     */
    fun stringToSign(
        timestamp: String,
        scope: String,
        canonicalRequest: String,
    ): String =
        buildString {
            append(ALGORITHM).append('\n')
            append(timestamp).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest))
        }

    /** `HMAC(HMAC(HMAC(HMAC("AWS4"+secret, date), region), service), "aws4_request")`, `:417`. */
    fun signingKey(
        secret: String,
        date: String,
        region: String,
        service: String,
    ): ByteArray {
        val initial = ("AWS4$secret").toByteArray(StandardCharsets.UTF_8)
        val kDate = hmac(initial, date)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    fun signature(
        signingKey: ByteArray,
        stringToSign: String,
    ): String = hex(hmac(signingKey, stringToSign))

    /**
     * Compares two signatures without leaking where they diverge.
     *
     * `MessageDigest.isEqual` is the JDK's constant-time comparison. An `==` here would answer the
     * question "how many leading characters are right", which is enough to walk a signature out of
     * a server one character at a time. Cheap to do properly, so it is done properly.
     */
    fun signaturesMatch(
        expected: String,
        actual: String,
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            actual.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun hmac(
        key: ByteArray,
        data: String,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v shr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
