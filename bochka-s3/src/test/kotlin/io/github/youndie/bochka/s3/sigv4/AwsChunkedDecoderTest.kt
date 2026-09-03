package io.github.youndie.bochka.s3.sigv4

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The streaming upload framings, on bodies produced by an implementation that is not ours.
 *
 * Source: `docs/spec/aws-chunked/`, written by `generate.py` from the reference server's chain and
 * botocore's unsigned form. Nobody publishes vectors for this, and it is the part where being
 * wrong means `aws s3 cp` does not work at all — so the fixtures are the closest thing to an
 * independent check that exists.
 */
class AwsChunkedDecoderTest {
    private val fixtures: Path =
        Path
            .of(
                System.getProperty("bochka.specDir")
                    ?: error("bochka.specDir is not set; the build has to point tests at docs/spec"),
            ).resolve("aws-chunked")

    private class Fixture(
        val meta: Map<String, String>,
        val body: ByteArray,
        val obj: ByteArray,
    )

    private fun fixture(name: String): Fixture {
        val dir = fixtures.resolve(name)
        val meta =
            Files.readAllLines(dir.resolve("meta")).filter { it.isNotBlank() }.associate {
                val eq = it.indexOf('=')
                it.substring(0, eq) to it.substring(eq + 1)
            }
        return Fixture(meta, Files.readAllBytes(dir.resolve("body")), Files.readAllBytes(dir.resolve("object")))
    }

    private fun decoderFor(
        f: Fixture,
        sink: (ByteArray, Int, Int) -> Unit,
    ): AwsChunkedDecoder {
        val signed = f.meta.getValue("mode") != "unsigned-trailer"
        val signing =
            if (!signed) {
                null
            } else {
                ChunkSigning(
                    secret = f.meta.getValue("secret"),
                    timestamp = f.meta.getValue("timestamp"),
                    date = f.meta.getValue("date"),
                    region = f.meta.getValue("region"),
                    seedSignature = f.meta.getValue("seed-signature"),
                )
            }
        return AwsChunkedDecoder(
            decodedLength = f.meta.getValue("decoded-length").toLong(),
            signing = signing,
            expectedTrailers = announcedTrailers(f),
            sink = sink,
        )
    }

    /** `x-amz-trailer` on the request announces which trailers may arrive; empty means none. */
    private fun announcedTrailers(f: Fixture): List<String> {
        val declared = f.meta.getValue("trailers")
        return declared.split(",").filter { it.isNotEmpty() }
    }

    private fun decode(
        name: String,
        chunkSize: Int = Int.MAX_VALUE,
    ): Pair<ByteArray, AwsChunkedDecoder> {
        val f = fixture(name)
        val out = ByteArrayOutputStream()
        val decoder = decoderFor(f) { bytes, offset, length -> out.write(bytes, offset, length) }

        var at = 0
        while (at < f.body.size) {
            val take = minOf(chunkSize, f.body.size - at)
            decoder.feed(f.body, at, take)
            at += take
        }
        decoder.finish()
        return out.toByteArray() to decoder
    }

    @Test
    fun `a signed body gives back exactly the object`() {
        for (name in listOf("signed-one-chunk", "signed-several-chunks", "signed-64k-chunks", "signed-empty-object")) {
            val (decoded, _) = decode(name)
            assertContentEquals(fixture(name).obj, decoded, name)
        }
    }

    @Test
    fun `the result does not depend on how the bytes were split`() {
        // The reason this decoder is fed rather than read from: on a selector the split is whatever
        // the kernel hands over, and a state machine that works only on whole frames works only in
        // tests. One byte at a time is the cruelest split there is.
        val whole = decode("signed-several-chunks").first
        for (size in listOf(1, 2, 7, 64, 1023)) {
            assertContentEquals(whole, decode("signed-several-chunks", chunkSize = size).first, "split of $size")
        }
    }

    @Test
    fun `a signed trailer is verified and its checksum checked against the object`() {
        val (decoded, decoder) = decode("signed-trailer-crc32")

        assertContentEquals(fixture("signed-trailer-crc32").obj, decoded)
        assertEquals(1, decoder.trailers.size)
        assertTrue(decoder.trailers.containsKey("x-amz-checksum-crc32"), decoder.trailers.toString())
    }

    @Test
    fun `the unsigned trailer framing carries no signatures at all`() {
        // What modern SDKs send by default since they started adding a checksum: plain chunked
        // framing, one trailer, nothing signed inside the body.
        val (decoded, decoder) = decode("unsigned-trailer-crc32")

        assertContentEquals(fixture("unsigned-trailer-crc32").obj, decoded)
        assertTrue(decoder.trailers.containsKey("x-amz-checksum-crc32"))
    }

    @Test
    fun `a byte changed inside a chunk breaks the chain`() {
        val f = fixture("signed-several-chunks")
        val tampered = f.body.copyOf()
        // Somewhere in the middle of the first chunk's data, past the size line.
        tampered[200] = (tampered[200] + 1).toByte()

        val failure =
            assertFailsWith<AwsChunkedDecoder.MalformedBody> {
                val decoder = decoderFor(f) { _, _, _ -> }
                decoder.feed(tampered)
                decoder.finish()
            }
        assertEquals(S3Error.SIGNATURE_DOES_NOT_MATCH, failure.error)
    }

    @Test
    fun `reordering two chunks breaks the chain even though both are signed`() {
        // The property the chain exists for: every chunk is signed over the one before it, so a
        // valid frame in the wrong place is no longer valid. A per-chunk signature without the
        // chain would accept this.
        val f = fixture("signed-several-chunks")
        val frames = splitFrames(f.body)
        val reordered = frames[1] + frames[0] + frames.drop(2).reduce { a, b -> a + b }

        val failure =
            assertFailsWith<AwsChunkedDecoder.MalformedBody> {
                val decoder = decoderFor(f) { _, _, _ -> }
                decoder.feed(reordered)
                decoder.finish()
            }
        assertEquals(S3Error.SIGNATURE_DOES_NOT_MATCH, failure.error)
    }

    @Test
    fun `a wrong checksum in the trailer is a bad digest, not a bad signature`() {
        // The trailer signature still verifies — the client signed what it sent. What does not
        // match is the checksum against the object, and the two failures have to be told apart:
        // one means "somebody tampered", the other means "your disk or your library is lying".
        val f = fixture("unsigned-trailer-crc32")
        val body = String(f.body, Charsets.ISO_8859_1)
        val marker = "x-amz-checksum-crc32:"
        val at = body.indexOf(marker) + marker.length
        val broken = (body.substring(0, at) + "AAAAAA==" + body.substring(at + 8)).toByteArray(Charsets.ISO_8859_1)

        val failure =
            assertFailsWith<AwsChunkedDecoder.MalformedBody> {
                val decoder = decoderFor(f) { _, _, _ -> }
                decoder.feed(broken)
                decoder.finish()
            }
        assertEquals(S3Error.BAD_DIGEST, failure.error)
    }

    @Test
    fun `a body that stops in the middle of a frame is incomplete, not empty`() {
        val f = fixture("signed-several-chunks")

        val failure =
            assertFailsWith<AwsChunkedDecoder.MalformedBody> {
                val decoder = decoderFor(f) { _, _, _ -> }
                decoder.feed(f.body, 0, f.body.size / 2)
                decoder.finish()
            }
        assertEquals(S3Error.INCOMPLETE_BODY, failure.error)
    }

    @Test
    fun `a chunk larger than the limit is refused before it is read`() {
        val f = fixture("signed-one-chunk")
        val oversized = "1000001;chunk-signature=${"0".repeat(64)}\r\n".toByteArray()

        val failure =
            assertFailsWith<AwsChunkedDecoder.MalformedBody> {
                decoderFor(f) { _, _, _ -> }.feed(oversized)
            }
        assertEquals(S3Error.INCOMPLETE_BODY, failure.error)
        assertTrue(failure.message!!.contains("limit"), failure.message!!)
    }

    @Test
    fun `a chunk of exactly the limit is accepted at its size line`() {
        // The other half of the pair above, and it costs nothing: the size line is refused - or
        // not - before a byte of the chunk is read, so the largest allowed size can be offered
        // without producing sixteen mebibytes to go with it. Without this the refusing test alone
        // proves only that some limit exists somewhere below 16 MiB + 1; the rule this repository
        // wrote for itself in M37 is that a boundary needs the last value that passes as well as
        // the first that does not.
        val f = fixture("signed-one-chunk")
        val atTheLimit = "${AwsChunkedDecoder.MAX_CHUNK.toString(16)};chunk-signature=${"0".repeat(64)}\r\n"

        // No exception: the decoder now waits for the bytes it was promised.
        decoderFor(f) { _, _, _ -> }.feed(atTheLimit.toByteArray())
    }

    @Test
    fun `a size line longer than the limit is refused rather than buffered`() {
        val f = fixture("signed-one-chunk")
        val endless = ("0".repeat(5000)).toByteArray()

        val failure =
            assertFailsWith<AwsChunkedDecoder.MalformedBody> {
                decoderFor(f) { _, _, _ -> }.feed(endless)
            }
        assertEquals(S3Error.INCOMPLETE_BODY, failure.error)
    }

    @Test
    fun `more bytes than the decoded length promised are refused`() {
        val f = fixture("signed-several-chunks")
        val lying =
            AwsChunkedDecoder(decodedLength = 10, signing = null, expectedTrailers = emptyList()) { _, _, _ -> }

        val failure = assertFailsWith<AwsChunkedDecoder.MalformedBody> { lying.feed(f.body) }
        assertEquals(S3Error.INCOMPLETE_BODY, failure.error)
    }

    /** Splits a signed body into `<size line>\r\n<data>\r\n` frames. */
    private fun splitFrames(body: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var at = 0
        while (at < body.size) {
            val lineEnd = indexOfCrlf(body, at)
            val header = String(body, at, lineEnd - at, Charsets.ISO_8859_1)
            val size = header.substringBefore(';').toInt(16)
            val frameEnd = lineEnd + 2 + size + 2
            frames.add(body.copyOfRange(at, minOf(frameEnd, body.size)))
            at = frameEnd
        }
        return frames
    }

    private fun indexOfCrlf(
        body: ByteArray,
        from: Int,
    ): Int {
        var i = from
        while (i + 1 < body.size) {
            if (body[i] == '\r'.code.toByte() && body[i + 1] == '\n'.code.toByte()) return i
            i++
        }
        return body.size
    }
}
