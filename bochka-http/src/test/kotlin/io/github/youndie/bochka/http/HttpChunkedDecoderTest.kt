package io.github.youndie.bochka.http

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpChunkedDecoderTest {
    private fun decode(
        wire: String,
        split: Int = Int.MAX_VALUE,
    ): Pair<String, HttpChunkedDecoder> {
        val out = ByteArrayOutputStream()
        val decoder = HttpChunkedDecoder(sink = { b, o, l -> out.write(b, o, l) })
        val bytes = wire.toByteArray(Charsets.ISO_8859_1)
        var at = 0
        while (at < bytes.size) {
            val take = minOf(split, bytes.size - at)
            at += decoder.feed(bytes, at, take)
        }
        return out.toString(Charsets.ISO_8859_1) to decoder
    }

    @Test
    fun `chunks come off and the payload is joined`() {
        val (body, decoder) = decode("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")

        assertEquals("hello world", body)
        assertTrue(decoder.isComplete)
    }

    @Test
    fun `an aws-chunked frame survives inside an http chunk`() {
        // The case that matters, and the one that only appears behind a proxy: nginx re-chunks with
        // its own sizes, so the client's `aws-chunked` frames end up split across HTTP chunks. What
        // comes out here has to be the client's framing, untouched, for the S3 layer to parse.
        val inner = "100000;chunk-signature=abc\r\n"
        val wire = "8\r\n${inner.take(8)}\r\n${(inner.length - 8).toString(16)}\r\n${inner.drop(8)}\r\n0\r\n\r\n"

        val (body, _) = decode(wire)

        assertEquals(inner, body)
    }

    @Test
    fun `chunk extensions are skipped rather than parsed`() {
        // `;chunk-signature=` is legal HTTP chunk-extension syntax and belongs to the layer above.
        val (body, _) = decode("5;chunk-signature=deadbeef\r\nhello\r\n0\r\n\r\n")

        assertEquals("hello", body)
    }

    @Test
    fun `trailers after the final chunk are collected`() {
        val (body, decoder) = decode("5\r\nhello\r\n0\r\nx-amz-checksum-crc32: AAAAAA==\r\n\r\n")

        assertEquals("hello", body)
        assertEquals("AAAAAA==", decoder.trailers["x-amz-checksum-crc32"])
    }

    @Test
    fun `the result does not depend on where the bytes were split`() {
        val wire = "5\r\nhello\r\n6\r\n world\r\n0\r\nx-amz-checksum-crc32: AA==\r\n\r\n"
        val whole = decode(wire).first

        for (size in listOf(1, 2, 3, 7, 13)) {
            assertEquals(whole, decode(wire, split = size).first, "split of $size")
        }
    }

    @Test
    fun `what is left after the body belongs to the next request`() {
        val bytes = "5\r\nhello\r\n0\r\n\r\nGET / HTTP/1.1\r\n".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        val decoder = HttpChunkedDecoder(sink = { b, o, l -> out.write(b, o, l) })

        val taken = decoder.feed(bytes)

        assertTrue(decoder.isComplete)
        assertEquals("GET / HTTP/1.1\r\n", String(bytes, taken, bytes.size - taken, Charsets.ISO_8859_1))
    }

    @Test
    fun `malformed framing is refused rather than guessed at`() {
        assertFailsWith<HttpChunkedDecoder.Malformed> { decode("zz\r\nhello\r\n0\r\n\r\n") }
        assertFailsWith<HttpChunkedDecoder.Malformed> { decode("5\nhello\n0\n\n") }
        assertFailsWith<HttpChunkedDecoder.Malformed> { decode("5\r\nhelloXX\r\n0\r\n\r\n") }
    }

    @Test
    fun `a chunk larger than the limit is refused before it is read`() {
        val decoder = HttpChunkedDecoder(maxChunkBytes = 1024, sink = { _, _, _ -> })

        assertFailsWith<HttpChunkedDecoder.Malformed> {
            decoder.feed("100000\r\n".toByteArray(Charsets.ISO_8859_1))
        }
    }
}
