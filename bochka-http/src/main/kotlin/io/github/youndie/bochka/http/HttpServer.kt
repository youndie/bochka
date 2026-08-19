package io.github.youndie.bochka.http

import io.github.youndie.bochka.http.nio.Connection
import io.github.youndie.bochka.http.nio.SelectorConnection
import io.github.youndie.bochka.http.nio.SelectorLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets

/**
 * The HTTP/1.1 server: accepts connections, reads requests, answers them.
 *
 * One coroutine per connection over a shared selector. The session is sequential — read a head,
 * answer it, read the next — which is what HTTP/1.1 without pipelining is, and pipelining is not
 * supported on purpose: no S3 client does it, and a server that half-supports it answers the second
 * request with the first one's status.
 */
class HttpServer(
    private val handler: HttpHandler,
    bindAddress: String = "127.0.0.1",
    port: Int = 0,
    private val readBufferBytes: Int = 32 * 1024,
) : Closeable {
    private val loop = SelectorLoop()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serverChannel: ServerSocketChannel = ServerSocketChannel.open()
    private val serverKey: SelectionKey

    /**
     * Bound explicitly rather than to a wildcard by default. A wildcard bind succeeds even when
     * another process already holds the specific address, and then quietly receives none of the
     * connections — the neighbouring broker lost a day to that one (its §1.16).
     */
    val boundPort: Int get() = (serverChannel.localAddress as InetSocketAddress).port

    init {
        serverChannel.bind(InetSocketAddress(bindAddress, port))
        serverKey = loop.register(serverChannel)
        scope.launch { acceptLoop() }
    }

    private suspend fun acceptLoop() {
        while (true) {
            val channel = serverChannel.accept()
            if (channel == null) {
                loop.awaitAcceptable(serverKey)
                continue
            }
            val key = loop.register(channel)
            val connection = SelectorConnection(channel, key, loop)
            scope.launch {
                try {
                    session(connection)
                } catch (_: Throwable) {
                    // One connection dying is not the server dying. Nothing here is logged yet:
                    // logging arrives with the app module, and a `printStackTrace` would be a
                    // decision made by accident.
                } finally {
                    connection.close()
                }
            }
        }
    }

    private suspend fun session(connection: Connection) {
        val buffer = ByteBuffer.allocate(readBufferBytes)
        var carried = ByteArray(0)

        while (true) {
            val parser = HttpRequestParser()
            var head: HttpRequestParser.Head? = null
            var leftover = ByteArray(0)

            // Whatever the previous request left behind belongs to this one.
            if (carried.isNotEmpty()) {
                val taken = feedSafely(parser, connection, carried) ?: return
                if (parser.isComplete) {
                    head = parser.head
                    leftover = carried.copyOfRange(taken, carried.size)
                }
                carried = ByteArray(0)
            }

            while (head == null) {
                buffer.clear()
                val read = connection.readSome(buffer)
                if (read < 0) return
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val taken = feedSafely(parser, connection, bytes) ?: return
                if (parser.isComplete) {
                    head = parser.head
                    leftover = bytes.copyOfRange(taken, bytes.size)
                }
            }

            val request = head
            // Wrapped exactly like `handle` below, and it was not — which is the whole of M-176.
            // `screen` reads the head, and reading a header can fail: a malformed `x-amz-tagging`
            // threw out of here, past this loop, and the client got a closed socket with no bytes
            // in it. A refusal has to be a refusal; "the connection dropped" is diagnosed as the
            // network, and the suite reported it as `ConnectionClosedError` for a milestone.
            val screened =
                try {
                    handler.screen(request)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    handler.failed(request, e)
                }
            if (screened != null) {
                // Answering without reading the body is the point (§1.2), and it also means the
                // connection can no longer be reused: what the client is about to send, or has
                // already sent, belongs to a request that is over.
                respond(connection, request, screened.copy(close = true))
                return
            }

            if (request.expectsContinue) {
                connection.writeFully(ByteBuffer.wrap(HttpResponse.CONTINUE))
            }

            val body = SocketBody(connection, request, leftover, readBufferBytes)
            val response =
                try {
                    handler.handle(request, body)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The connection is closed afterwards regardless, because the body may be
                    // half-read and what is left on the socket belongs to a request that is over.
                    // What matters is that the client is told something first.
                    handler.failed(request, e).copy(close = true)
                }
            if (!body.isDrained) {
                respond(connection, request, response.copy(close = true))
                return
            }

            carried = body.carried()
            respond(connection, request, response)
            if (response.close || !request.keepAlive) return
        }
    }

    /** Returns the bytes consumed, or null when the request was refused and the session is over. */
    private suspend fun feedSafely(
        parser: HttpRequestParser,
        connection: Connection,
        bytes: ByteArray,
    ): Int? =
        try {
            parser.feed(bytes)
        } catch (e: HttpRequestParser.Malformed) {
            val message = (e.message ?: "malformed request").toByteArray(StandardCharsets.UTF_8)
            val response =
                HttpResponse(
                    status = e.status,
                    reason = reasonFor(e.status),
                    headers = listOf("Content-Type" to "text/plain; charset=utf-8"),
                    body = message,
                    close = true,
                )
            connection.writeFully(ByteBuffer.wrap(response.render()))
            null
        }

    private suspend fun respond(
        connection: Connection,
        head: HttpRequestParser.Head,
        response: HttpResponse,
    ) {
        val withBody = head.method != "HEAD"
        connection.writeFully(ByteBuffer.wrap(response.render(withBody = withBody)))
        val file = response.file ?: return
        if (withBody) sendFile(connection, file)
    }

    /**
     * The object's bytes, straight from the page cache to the socket.
     *
     * `transferTo` and not a read-write loop, and the target is the connection's own
     * [Connection.transferTarget] rather than anything wrapping it: the JDK takes the `sendfile`
     * path only when the destination is a real `SocketChannelImpl`, and any decorator silently
     * turns this into a copy through the heap that returns identical bytes (§1.6.3). That is what
     * M-60 gates and what M-61 measures.
     *
     * The loop is not optional. `transferTo` is allowed to move fewer bytes than asked — on a
     * non-blocking socket it returns as soon as the send buffer is full — and a zero means the
     * socket is not writable rather than that the file ended, so the connection is waited on.
     */
    private suspend fun sendFile(
        connection: Connection,
        file: HttpResponse.FileSlice,
    ) {
        java.nio.channels.FileChannel
            .open(file.path, java.nio.file.StandardOpenOption.READ)
            .use { source ->
                var position = file.offset
                var remaining = file.length
                while (remaining > 0) {
                    val moved = source.transferTo(position, remaining, connection.transferTarget)
                    if (moved > 0) {
                        position += moved
                        remaining -= moved
                    } else {
                        connection.awaitWritable()
                    }
                }
            }
    }

    override fun close() {
        scope.cancel()
        serverChannel.close()
        loop.close()
    }

    /**
     * The body as it comes off the socket: what the head parser over-read first, then the rest.
     *
     * Only the two framings HTTP itself defines are here — a stated length, or chunked. The S3
     * layer wraps this to take `aws-chunked` apart, because that framing lives inside the body and
     * is none of HTTP's business.
     */
    private class SocketBody(
        private val connection: Connection,
        private val head: HttpRequestParser.Head,
        private val leftover: ByteArray,
        private val bufferBytes: Int,
    ) : HttpHandler.RequestBody {
        private var consumedLeftover = 0
        private var remaining = head.contentLength ?: 0
        var isDrained = false
            private set

        /** Trailers that arrived after the final HTTP chunk, if the body was chunked. */
        var chunkedTrailers: Map<String, String> = emptyMap()
            private set

        override suspend fun forEach(consume: (ByteArray, Int, Int) -> Unit) {
            if (head.isChunked) forEachChunked(consume) else forEachSized(consume)
        }

        private suspend fun forEachSized(consume: (ByteArray, Int, Int) -> Unit) {
            val fromLeftover = minOf(remaining, (leftover.size - consumedLeftover).toLong()).toInt()
            if (fromLeftover > 0) {
                consume(leftover, consumedLeftover, fromLeftover)
                consumedLeftover += fromLeftover
                remaining -= fromLeftover
            }

            val buffer = ByteBuffer.allocate(bufferBytes)
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(remaining, buffer.capacity().toLong()).toInt())
                val read = connection.readSome(buffer)
                if (read < 0) throw java.io.EOFException("body ended $remaining bytes early")
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                consume(bytes, 0, bytes.size)
                remaining -= bytes.size
            }
            isDrained = true
        }

        /**
         * HTTP's own framing comes off here, and what it wrapped goes on.
         *
         * Passing the bytes through untouched works only while the client's chunk boundaries and
         * the transport's happen to coincide — which they do on a direct connection and do not
         * behind a proxy, where the layers are visibly nested (see [HttpChunkedDecoder]).
         */
        private suspend fun forEachChunked(consume: (ByteArray, Int, Int) -> Unit) {
            val decoder = HttpChunkedDecoder(sink = consume)
            if (leftover.size > consumedLeftover) {
                consumedLeftover += decoder.feed(leftover, consumedLeftover, leftover.size - consumedLeftover)
            }
            val buffer = ByteBuffer.allocate(bufferBytes)
            while (!decoder.isComplete) {
                buffer.clear()
                val read = connection.readSome(buffer)
                if (read < 0) throw java.io.EOFException("body ended inside a chunked frame")
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                decoder.feed(bytes)
            }
            chunkedTrailers = decoder.trailers
            isDrained = true
        }

        /** What was read past the end of this body and belongs to the next request. */
        fun carried(): ByteArray {
            val unread = leftover.size - consumedLeftover
            return if (unread > 0) leftover.copyOfRange(consumedLeftover, leftover.size) else ByteArray(0)
        }
    }

    private companion object {
        fun reasonFor(status: Int): String =
            when (status) {
                400 -> "Bad Request"
                431 -> "Request Header Fields Too Large"
                505 -> "HTTP Version Not Supported"
                else -> "Error"
            }
    }
}
