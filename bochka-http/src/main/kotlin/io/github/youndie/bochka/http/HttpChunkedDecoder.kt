package io.github.youndie.bochka.http

/**
 * Takes HTTP's own chunked transfer encoding off a body.
 *
 * It exists because the two framings **nest**, and that is not obvious until something re-frames
 * the request. A client sending a streaming upload writes `aws-chunked` frames as the *content*,
 * and the transport then puts HTTP chunks around them. On a direct connection the two boundaries
 * coincide, so a server that passes the bytes straight through appears to work. Put a TLS
 * terminator in front — which is how bochka is meant to run at all (research, Р5) — and nginx
 * re-chunks with its own sizes:
 *
 * ```
 * 1ff9\r\n            <- nginx's HTTP chunk, 8185 bytes
 * 100000\r\n          <- botocore's aws-chunked frame, 1 MiB, inside it
 * <object bytes…>
 * ```
 *
 * Reading that with the `aws-chunked` decoder alone counts framing bytes as object bytes, and the
 * upload fails with "more bytes than promised" after transferring perfectly. So HTTP's layer comes
 * off here, and only what it wrapped goes on to the S3 layer.
 *
 * Chunk extensions are skipped rather than parsed: at this level `;chunk-signature=…` is somebody
 * else's business. Trailers after the final chunk are collected and handed over.
 */
class HttpChunkedDecoder(
    private val maxChunkBytes: Long = 64L * 1024 * 1024,
    private val maxLineBytes: Int = 8 * 1024,
    private val sink: (ByteArray, Int, Int) -> Unit,
) {
    class Malformed(
        message: String,
    ) : IllegalArgumentException(message)

    private enum class State { SIZE, DATA, DATA_CRLF, TRAILER, DONE }

    private var state = State.SIZE
    private val line = StringBuilder(32)
    private var remaining = 0L
    private val collectedTrailers = LinkedHashMap<String, String>()

    val trailers: Map<String, String> get() = collectedTrailers

    val isComplete: Boolean get() = state == State.DONE

    /** Returns how many bytes it consumed; anything left belongs to the next request. */
    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ): Int {
        var at = offset
        val end = offset + length
        while (at < end && state != State.DONE) {
            at =
                when (state) {
                    State.DATA -> readData(bytes, at, end)
                    else -> readLine(bytes, at, end)
                }
        }
        return at - offset
    }

    private fun readData(
        bytes: ByteArray,
        from: Int,
        end: Int,
    ): Int {
        val take = minOf(remaining, (end - from).toLong()).toInt()
        sink(bytes, from, take)
        remaining -= take
        if (remaining == 0L) state = State.DATA_CRLF
        return from + take
    }

    private fun readLine(
        bytes: ByteArray,
        from: Int,
        end: Int,
    ): Int {
        var at = from
        while (at < end) {
            val b = bytes[at++]
            if (b != LF) {
                line.append((b.toInt() and 0xFF).toChar())
                if (line.length > maxLineBytes) throw Malformed("chunked line over $maxLineBytes bytes")
                continue
            }
            if (line.isEmpty() || line.last() != CR) throw Malformed("a chunked line must end with CRLF")
            val text = line.substring(0, line.length - 1)
            line.setLength(0)
            onLine(text)
            return at
        }
        return at
    }

    private fun onLine(text: String) {
        when (state) {
            State.SIZE -> {
                // Everything after `;` is an extension. Not parsed here on purpose — the signature
                // that lives in one belongs to the layer above.
                val size =
                    text.substringBefore(';').trim().toLongOrNull(16)
                        ?: throw Malformed("chunk size '${text.substringBefore(';')}' is not hex")
                if (size < 0 || size > maxChunkBytes) throw Malformed("chunk of $size bytes")
                remaining = size
                state = if (size == 0L) State.TRAILER else State.DATA
            }

            State.DATA_CRLF -> {
                if (text.isNotEmpty()) throw Malformed("chunk not followed by CRLF")
                state = State.SIZE
            }

            State.TRAILER -> {
                if (text.isEmpty()) {
                    state = State.DONE
                    return
                }
                val colon = text.indexOf(':')
                if (colon <= 0) throw Malformed("trailer line without a name")
                collectedTrailers[text.substring(0, colon).lowercase()] = text.substring(colon + 1).trim()
            }

            else -> {
                throw Malformed("unexpected line in state $state")
            }
        }
    }

    private companion object {
        const val CR = '\r'
        const val LF = '\n'.code.toByte()
    }
}
