package io.github.youndie.bochka.s3.sigv4

import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.CRC32C

/**
 * Takes an `aws-chunked` body apart and hands out the object's bytes.
 *
 * This is the framing `aws s3 cp` uses by default, so a server that does not read it does not work
 * with the AWS CLI at all (research, §1.1). The object arrives as frames:
 *
 * ```
 * <size in hex>;chunk-signature=<hex>\r\n<data>\r\n
 * ...
 * 0;chunk-signature=<hex>\r\n
 * x-amz-checksum-crc32:<base64>\r\n          (only with a trailer)
 * x-amz-trailer-signature:<hex>\r\n\r\n      (only when the trailer is signed)
 * ```
 *
 * and the real length of the object is in `X-Amz-Decoded-Content-Length`, because `Content-Length`
 * describes the framing and the client removed it anyway.
 *
 * **Push, not pull.** Bytes are fed in as they arrive and the object's bytes come out through
 * [sink]. A server on a selector has no thread to block in a `read()`, and an `InputStream` here
 * would have to be adapted back into events by whoever owns the socket. Feeding also makes the
 * whole thing testable on a recorded byte stream, which is the module boundary this layer exists
 * to keep (Р8).
 *
 * Limits are enforced rather than assumed: a chunk of 16 MiB and a line of 4 KiB, the same numbers
 * the reference server uses (`minio/minio`, `cmd/streaming-signature-v4.go:178,260`). This is an
 * unauthenticated-shaped input path even when the request is signed — the frames arrive before
 * they are verified.
 */
class AwsChunkedDecoder(
    private val decodedLength: Long,
    private val signing: ChunkSigning?,
    private val expectedTrailers: List<String> = emptyList(),
    private val sink: (ByteArray, Int, Int) -> Unit,
) {
    class MalformedBody(
        val error: S3Error,
        message: String,
    ) : IllegalArgumentException(message)

    private enum class State { SIZE_LINE, DATA, DATA_CRLF, TRAILER, DONE }

    private var state = State.SIZE_LINE
    private val line = StringBuilder(64)
    private var chunkRemaining = 0L
    private var chunkSignature: String? = null
    private var chunkDigest = MessageDigest.getInstance("SHA-256")
    private var lastChunkSeen = false
    private var produced = 0L
    private val trailerBytes = StringBuilder(128)
    private val collectedTrailers = LinkedHashMap<String, String>()
    private val checksum = ObjectChecksum()

    val trailers: Map<String, String> get() = collectedTrailers

    val isComplete: Boolean get() = state == State.DONE

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ) {
        var at = offset
        val end = offset + length
        while (at < end) {
            when (state) {
                State.SIZE_LINE -> at = readLine(bytes, at, end) { onSizeLine(it) }
                State.DATA -> at = readData(bytes, at, end)
                State.DATA_CRLF -> at = readLine(bytes, at, end) { onDataCrlf(it) }
                State.TRAILER -> at = readLine(bytes, at, end) { onTrailerLine(it) }
                State.DONE -> throw MalformedBody(S3Error.INCOMPLETE_BODY, "bytes after the final chunk")
            }
        }
    }

    /** Call when the sender is done: catches a body that stopped in the middle of a frame. */
    fun finish() {
        if (state != State.DONE) throw MalformedBody(S3Error.INCOMPLETE_BODY, "body ended inside a frame")
        if (produced != decodedLength) {
            throw MalformedBody(
                S3Error.INCOMPLETE_BODY,
                "x-amz-decoded-content-length said $decodedLength, got $produced",
            )
        }
    }

    private inline fun readLine(
        bytes: ByteArray,
        from: Int,
        end: Int,
        onLine: (String) -> Unit,
    ): Int {
        var at = from
        while (at < end) {
            val b = bytes[at++]
            if (b == LF) {
                if (line.isEmpty() || line.last() != '\r') {
                    throw MalformedBody(S3Error.INCOMPLETE_BODY, "a frame line must end with CRLF")
                }
                val complete = line.substring(0, line.length - 1)
                line.setLength(0)
                onLine(complete)
                return at
            }
            line.append((b.toInt() and 0xFF).toChar())
            if (line.length > MAX_LINE) throw MalformedBody(S3Error.INCOMPLETE_BODY, "frame line over $MAX_LINE bytes")
        }
        return at
    }

    private fun readData(
        bytes: ByteArray,
        from: Int,
        end: Int,
    ): Int {
        val take = minOf(chunkRemaining, (end - from).toLong()).toInt()
        chunkDigest.update(bytes, from, take)
        checksum.update(bytes, from, take)
        sink(bytes, from, take)
        produced += take
        if (produced > decodedLength) {
            throw MalformedBody(
                S3Error.INCOMPLETE_BODY,
                "more bytes than x-amz-decoded-content-length promised ($decodedLength)",
            )
        }
        chunkRemaining -= take
        if (chunkRemaining == 0L) state = State.DATA_CRLF
        return from + take
    }

    private fun onSizeLine(raw: String) {
        val semicolon = raw.indexOf(';')
        val sizeText = if (semicolon < 0) raw else raw.substring(0, semicolon)
        val size =
            sizeText.trim().toLongOrNull(16)
                ?: throw MalformedBody(S3Error.INCOMPLETE_BODY, "chunk size '$sizeText' is not hex")
        if (size < 0 || size > MAX_CHUNK) {
            throw MalformedBody(S3Error.INCOMPLETE_BODY, "chunk of $size bytes; the limit is $MAX_CHUNK")
        }

        chunkSignature =
            if (semicolon < 0) {
                null
            } else {
                val extension = raw.substring(semicolon + 1)
                if (!extension.startsWith(CHUNK_SIGNATURE)) {
                    throw MalformedBody(S3Error.INCOMPLETE_BODY, "unknown chunk extension '$extension'")
                }
                extension.removePrefix(CHUNK_SIGNATURE)
            }
        if (signing != null && chunkSignature == null) {
            throw MalformedBody(S3Error.INCOMPLETE_BODY, "a signed body needs a signature on every chunk")
        }

        chunkDigest = MessageDigest.getInstance("SHA-256")
        chunkRemaining = size
        lastChunkSeen = size == 0L
        if (size == 0L) {
            // The final chunk has no data and — unlike a data chunk — **no trailing CRLF of its
            // own**. What comes next is either the blank line that ends the body or the first
            // trailer, and treating the two the same is what makes both framings one state machine.
            verifyChunkSignature()
            state = State.TRAILER
        } else {
            state = State.DATA
        }
    }

    private fun onDataCrlf(raw: String) {
        // The empty line between a chunk's data and the next size line.
        if (raw.isNotEmpty()) throw MalformedBody(S3Error.INCOMPLETE_BODY, "chunk not followed by CRLF")
        verifyChunkSignature()
        state = State.SIZE_LINE
    }

    private fun verifyChunkSignature() {
        val signing = signing ?: return
        val provided = chunkSignature ?: return
        val expected = signing.chunkSignature(hex(chunkDigest.digest()))
        if (!Sigv4.signaturesMatch(expected, provided)) {
            throw MalformedBody(
                S3Error.SIGNATURE_DOES_NOT_MATCH,
                "chunk signature $provided, computed $expected",
            )
        }
        signing.accept(provided)
    }

    private fun onTrailerLine(raw: String) {
        if (raw.isEmpty()) {
            // The blank line that ends the body. With signed trailers the signature line has
            // already been read; with unsigned ones there was nothing to read.
            finishTrailers()
            return
        }

        if (raw.startsWith(TRAILER_SIGNATURE, ignoreCase = true)) {
            val provided = raw.substring(TRAILER_SIGNATURE.length).trim()
            val signing =
                signing
                    ?: throw MalformedBody(S3Error.INCOMPLETE_BODY, "a trailer signature on an unsigned body")
            // The bytes signed are the trailer lines, each terminated by a bare `\n` — not the
            // `\r\n` they arrived with (`cmd/streaming-signature-v4.go:517-523`).
            val expected = signing.trailerSignature(Sigv4.sha256Hex(trailerBytes.toString()))
            if (!Sigv4.signaturesMatch(expected, provided)) {
                throw MalformedBody(
                    S3Error.SIGNATURE_DOES_NOT_MATCH,
                    "trailer signature $provided, computed $expected",
                )
            }
            signing.accept(provided)
            return
        }

        val colon = raw.indexOf(':')
        if (colon <= 0) throw MalformedBody(S3Error.INCOMPLETE_BODY, "trailer line without a name: '$raw'")
        val name = raw.substring(0, colon).lowercase()
        val value = raw.substring(colon + 1).trim()
        if (expectedTrailers.isNotEmpty() && expectedTrailers.none { it.equals(name, ignoreCase = true) }) {
            throw MalformedBody(S3Error.INCOMPLETE_BODY, "trailer '$name' was not announced by x-amz-trailer")
        }
        collectedTrailers[name] = value
        trailerBytes.append(raw).append('\n')
    }

    private fun finishTrailers() {
        for ((name, value) in collectedTrailers) {
            val mismatch = checksum.verify(name, value)
            if (mismatch != null) throw MalformedBody(S3Error.BAD_DIGEST, mismatch)
        }
        state = State.DONE
    }

    /**
     * The checksums a client may put in a trailer, computed as the object goes past.
     *
     * Since 2025 the AWS SDKs send one by default — which is why the unsigned-trailer framing
     * exists at all — so this is the ordinary path rather than an option somebody turns on.
     * Unknown algorithms are carried and not verified: refusing a checksum we cannot compute would
     * break a client for being newer than us.
     */
    private class ObjectChecksum {
        private val crc32 = CRC32()
        private val crc32c = CRC32C()
        private val sha1 = MessageDigest.getInstance("SHA-1")
        private val sha256 = MessageDigest.getInstance("SHA-256")

        fun update(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            crc32.update(bytes, offset, length)
            crc32c.update(bytes, offset, length)
            sha1.update(bytes, offset, length)
            sha256.update(bytes, offset, length)
        }

        /** Returns null when it matches or when the algorithm is one we do not compute. */
        fun verify(
            name: String,
            value: String,
        ): String? {
            val computed =
                when (name.lowercase()) {
                    "x-amz-checksum-crc32" -> encode(intToBytes(crc32.value))
                    "x-amz-checksum-crc32c" -> encode(intToBytes(crc32c.value))
                    "x-amz-checksum-sha1" -> encode(sha1.digest())
                    "x-amz-checksum-sha256" -> encode(sha256.digest())
                    else -> return null
                }
            return if (computed == value) null else "$name: client said $value, computed $computed"
        }

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun intToBytes(value: Long): ByteArray =
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            )
    }

    // `internal` rather than `private`, so that the boundary test can offer exactly the largest
    // chunk this decoder allows instead of writing `1000000` next to it. A limit spelled in two
    // places is a limit that will be changed in one.
    internal companion object {
        const val CHUNK_SIGNATURE = "chunk-signature="
        const val TRAILER_SIGNATURE = "x-amz-trailer-signature:"
        const val MAX_CHUNK = 16L * 1024 * 1024
        const val MAX_LINE = 4 * 1024
        const val LF = '\n'.code.toByte()

        private val HEX = "0123456789abcdef".toCharArray()

        fun hex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                out[i * 2] = HEX[v shr 4]
                out[i * 2 + 1] = HEX[v and 0x0F]
            }
            return String(out)
        }
    }
}
