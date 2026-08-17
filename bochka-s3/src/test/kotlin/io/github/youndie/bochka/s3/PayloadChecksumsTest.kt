package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.s3.sigv4.S3Error
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a client can state about the bytes it is sending, and what happens when it is wrong.
 *
 * The headers are `s3-service-2.json`, `PutObjectRequest.members` — `ContentMD5` at `Content-MD5`
 * and `ChecksumCRC32`/`ChecksumCRC32C`/`ChecksumSHA1`/`ChecksumSHA256` at `x-amz-checksum-*`, all
 * base64. The expected values below are **not** produced by this code: they come from Python's
 * `hashlib` and `zlib`, plus a from-scratch Castagnoli CRC, in `docs/spec/checksums/generate.py`.
 * A vector computed by the implementation it checks proves only that the code is deterministic.
 */
class PayloadChecksumsTest {
    private val content = "testcontent".toByteArray()

    /** The generated vectors, as `content-name` → `algorithm` → base64. */
    private val vectors: Map<String, Map<String, String>> =
        Path
            .of(System.getProperty("bochka.specDir") ?: error("bochka.specDir is not set"))
            .resolve("checksums/vectors.txt")
            .let(Files::readAllLines)
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { it.split('\t') }
            .groupBy({ it[0] }, { it[1] to it[2] })
            .mapValues { (_, pairs) -> pairs.toMap() }

    private fun vector(
        content: String,
        algorithm: String,
    ) = vectors.getValue(content).getValue(algorithm)

    private fun headOf(vararg headers: Pair<String, String>): (String) -> String? {
        val map = headers.associate { it.first.lowercase() to it.second }
        return { map[it.lowercase()] }
    }

    private fun run(vararg headers: Pair<String, String>): PayloadChecksums =
        PayloadChecksums.of(headOf(*headers)).also { it.update(content, 0, content.size) }

    @Test
    fun `a body with nothing stated about it passes`() {
        val checksums = run()
        assertNull(checksums.rejection)
        assertNull(checksums.verify())
        assertNull(checksums.stored())
    }

    @Test
    fun `every algorithm agrees with an independent implementation`() {
        for (
        header in
        listOf(
            "x-amz-checksum-crc32",
            "x-amz-checksum-crc32c",
            "x-amz-checksum-crc64nvme",
            "x-amz-checksum-sha1",
            "x-amz-checksum-sha256",
        )
        ) {
            val checksums = run(header to vector("testcontent", header))
            assertNull(checksums.rejection, header)
            assertNull(checksums.verify(), header)
        }
    }

    @Test
    fun `Content-MD5 is checked against the body`() {
        assertNull(run("Content-MD5" to vector("testcontent", "content-md5")).verify())
    }

    @Test
    fun `a checksum that does not describe the body is a BadDigest`() {
        // The empty string's CRC32C, against eleven bytes of content: well-formed, right length,
        // and about different bytes.
        val ofNothing = vectors.getValue("empty")
        assertEquals(
            S3Error.BAD_DIGEST,
            run("x-amz-checksum-crc32c" to ofNothing.getValue("x-amz-checksum-crc32c")).verify()?.error,
        )
        assertEquals(S3Error.BAD_DIGEST, run("Content-MD5" to ofNothing.getValue("content-md5")).verify()?.error)
    }

    @Test
    fun `a malformed header is refused from the head alone`() {
        // The difference that matters: this is known before a byte of the body has been read, so
        // the refusal costs nothing (§1.2). BadDigest cannot be — it is about the body.
        assertEquals(
            S3Error.INVALID_DIGEST,
            PayloadChecksums.of(headOf("Content-MD5" to "not base64")).rejection?.error,
        )
        assertEquals(S3Error.INVALID_DIGEST, PayloadChecksums.of(headOf("Content-MD5" to "AAAA")).rejection?.error)
        assertEquals(
            S3Error.INVALID_REQUEST,
            PayloadChecksums.of(headOf("x-amz-checksum-crc32" to "AAAAAAAA")).rejection?.error,
        )
    }

    @Test
    fun `two checksums of different algorithms contradict each other`() {
        val rejection =
            PayloadChecksums
                .of(
                    headOf(
                        "x-amz-checksum-crc32" to vector("testcontent", "x-amz-checksum-crc32"),
                        "x-amz-checksum-sha1" to vector("testcontent", "x-amz-checksum-sha1"),
                    ),
                ).rejection
        assertEquals(S3Error.INVALID_REQUEST, rejection?.error)
    }

    @Test
    fun `an algorithm this server does not have is refused by name`() {
        // Not accepted-and-ignored: a stated checksum that nobody verifies is worse than none,
        // because the client believes the bytes were checked.
        assertEquals(
            S3Error.NOT_IMPLEMENTED,
            PayloadChecksums.of(headOf("x-amz-checksum-sha512" to "AAAA")).rejection?.error,
        )
    }

    @Test
    fun `the algorithm aws-cli sends by default is one this server has`() {
        // This list is set by clients, not by taste. crc64nvme was on the refused side for about
        // an hour, and every default `aws s3 cp` came back NotImplemented.
        val vector = vector("testcontent", "x-amz-checksum-crc64nvme")
        val checksums = run("x-amz-checksum-crc64nvme" to vector)
        assertNull(checksums.rejection)
        assertNull(checksums.verify())
        assertEquals(Metadata.Checksum("crc64nvme", vector), checksums.stored())
    }

    @Test
    fun `a 64-bit checksum in a 32-bit field is refused before the body`() {
        // Eight bytes base64-encoded, not four: a length check is the only thing between a
        // truncated CRC and a BadDigest that blames the body.
        assertEquals(
            S3Error.INVALID_REQUEST,
            PayloadChecksums.of(headOf("x-amz-checksum-crc64nvme" to "AAAAAA==")).rejection?.error,
        )
    }

    @Test
    fun `what is stored is what the client sent, so a GET can answer with it`() {
        val crc32c = vector("testcontent", "x-amz-checksum-crc32c")
        assertEquals(Metadata.Checksum("crc32c", crc32c), run("x-amz-checksum-crc32c" to crc32c).stored())
    }

    @Test
    fun `anyStated sees every form, including the ones this server refuses`() {
        // What DeleteObjects screens on (M-45). An unsupported algorithm still counts as "stated":
        // the request is refused either way, and refusing it for the right reason matters.
        assertNotNull(PayloadChecksums.of(headOf("Content-MD5" to "x")))
        for (
        header in
        listOf("content-md5", "x-amz-checksum-crc32", "x-amz-checksum-sha256", "x-amz-checksum-sha512")
        ) {
            assertEquals(true, PayloadChecksums.anyStated(headOf(header to "value")), header)
        }
        assertEquals(false, PayloadChecksums.anyStated(headOf("content-type" to "text/plain")))
    }

    @Test
    fun `a checksum over a body fed in pieces is the same checksum`() {
        // The bytes arrive from a socket in whatever sizes the network chose, and a checksum that
        // depended on those sizes would fail on a large object and pass in every test.
        val checksums =
            PayloadChecksums.of(headOf("x-amz-checksum-crc32c" to vector("testcontent", "x-amz-checksum-crc32c")))
        for (i in content.indices) checksums.update(content, i, 1)
        assertNull(checksums.verify())
    }
}
