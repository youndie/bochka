package io.github.youndie.bochka.http

import io.github.youndie.bochka.http.nio.Connection
import io.github.youndie.bochka.http.nio.SelectorConnection
import io.github.youndie.bochka.http.nio.SelectorLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.time.Duration

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
    /**
     * How long a request head may take to arrive, from the first byte of it.
     *
     * The limits above it are all about **size** — the head, a line, the number of headers — and a
     * client that sends one byte a minute breaks none of them while holding the connection for
     * ever. That is a connection-exhaustion attack written as patience, and it is also what a dead
     * NAT looks like from here, which is why the answer is `408` and a close rather than a silent
     * drop: the client is told what happened.
     */
    private val headTimeout: Duration = DEFAULT_HEAD_TIMEOUT,
    /**
     * How long the body may go **idle** — between two reads, not in total.
     *
     * In total would be a size limit wearing a clock: a five-gibibyte upload over a slow link is
     * legitimate and takes as long as it takes. What is not legitimate is a client that stops
     * sending and never says so, and the gap between reads is what tells them apart.
     */
    private val bodyIdleTimeout: Duration = DEFAULT_BODY_IDLE_TIMEOUT,
    /**
     * How many connections may be live at once, refused by name beyond that.
     *
     * Derived from the heap rather than chosen, for the same reason the object ceiling is
     * ([ceilingForHeap]): a connection costs memory whether or not anybody counts it, so the only
     * question is whether the number is published or discovered by falling over.
     *
     * The refusal is a `503` on an accepted socket rather than a socket left unaccepted. Not
     * accepting is silent: the client sees a connection that hangs and then dies, which is what a
     * dead server looks like, and the operator sees nothing at all.
     */
    private val maxConnections: Int = ceilingForHeap(),
) : Closeable {
    private val live =
        java.util.concurrent.atomic
            .AtomicInteger()
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

            // Counted here rather than inside the coroutine: the count has to be true by the time
            // the next `accept` asks, and a coroutine that has not been scheduled yet has not
            // counted itself.
            if (live.incrementAndGet() > maxConnections) {
                scope.launch {
                    try {
                        respond(connection, unsentRequest, HttpResponse(503, "Service Unavailable", close = true))
                    } catch (e: Throwable) {
                        handler.abandoned(e)
                    } finally {
                        live.decrementAndGet()
                        connection.close()
                    }
                }
                continue
            }

            scope.launch {
                try {
                    session(connection)
                } catch (e: Throwable) {
                    // One connection dying is not the server dying, so the loop goes on — but it
                    // used to go on in silence, with a comment saying logging would arrive with the
                    // app module. It has (M-229), and this is the way through: the decision about
                    // what a dead connection is worth saying belongs up there, not here.
                    handler.abandoned(e)
                } finally {
                    // The slot comes back here, and this is the half that makes it a ceiling
                    // rather than a lifetime budget: without it the count only rises and a server
                    // that has served its limit refuses everybody for ever.
                    live.decrementAndGet()
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
                val read =
                    try {
                        withTimeout(headTimeout.toMillis()) { connection.readSome(buffer) }
                    } catch (_: TimeoutCancellationException) {
                        // Answered rather than dropped. A client that is merely slow learns that it
                        // was too slow; one that is holding the slot on purpose learns nothing it
                        // did not know, and the slot comes back either way.
                        respond(connection, unsentRequest, HttpResponse(408, "Request Timeout", close = true))
                        return
                    }
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

            val body = SocketBody(connection, request, leftover, readBufferBytes, bodyIdleTimeout)
            val response =
                try {
                    handler.handle(request, body)
                } catch (e: RequestTimeout) {
                    // Its own answer rather than `failed`: a client that stopped sending is not a
                    // bug in this server, and `500` would tell it to retry the very thing it did
                    // not finish.
                    HttpResponse(408, "Request Timeout", close = true)
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
            // Said out loud before it is answered (M-229). The client has always seen this refusal;
            // until now the log above had no idea it happened, which is the same complaint M-205
            // made about a `500` one layer up.
            handler.malformed(e.status, e)
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
        val filter = file.through
        if (filter != null) {
            sendFiltered(connection, file, filter)
            return
        }
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

    /**
     * The other read path, and the only one there is when the bytes on the disk are not the bytes
     * of the object.
     *
     * A read into the process, a transformation and a write — everything `transferTo` exists to
     * avoid, and unavoidable here: the kernel cannot decrypt. Deliberately not a fallback for
     * anything else. Nothing but SSE-C sets a filter, so an object that is not encrypted cannot
     * end up here by accident, and there is a test asserting exactly that (M-188).
     */
    private suspend fun sendFiltered(
        connection: Connection,
        file: HttpResponse.FileSlice,
        filter: HttpResponse.Filter,
    ) {
        val chunk = ByteArray(64 * 1024)
        java.nio.channels.FileChannel
            .open(file.path, java.nio.file.StandardOpenOption.READ)
            .use { source ->
                var position = file.offset
                var remaining = file.length
                while (remaining > 0) {
                    val wanted = minOf(remaining, chunk.size.toLong()).toInt()
                    val buffer = ByteBuffer.wrap(chunk, 0, wanted)
                    var read = 0
                    while (buffer.hasRemaining()) {
                        val n = source.read(buffer, position + read)
                        if (n < 0) break
                        read += n
                    }
                    if (read <= 0) break
                    filter.apply(chunk, 0, read)
                    connection.writeFully(ByteBuffer.wrap(chunk, 0, read))
                    position += read
                    remaining -= read
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
        private val idleTimeout: Duration,
    ) : HttpHandler.RequestBody {
        private var consumedLeftover = 0
        private var remaining = head.contentLength ?: 0

        /**
         * Whether the socket holds nothing that belonged to this request.
         *
         * True from the start when there is no body, and that line is a fix rather than a
         * shortcut. Reading the body is what used to set it, so a request nobody read the body of
         * looked unfinished — and a `GET` has no body to read. Every read therefore ended its
         * connection, while every test said connections were kept: the handler they use reads the
         * body unconditionally, which the real one does not. Found by pointing DuckDB at this
         * server: 373 requests took 370 connections, and 368 of those were closed from here.
         *
         * A body that exists and was ignored is a different case and still closes: those bytes are
         * on the socket whether anybody wanted them or not.
         */
        var isDrained = !head.isChunked && (head.contentLength ?: 0L) == 0L
            private set

        /** Trailers that arrived after the final HTTP chunk, if the body was chunked. */
        var chunkedTrailers: Map<String, String> = emptyMap()
            private set

        override suspend fun forEach(consume: (ByteArray, Int, Int) -> Unit) {
            if (head.isChunked) forEachChunked(consume) else forEachSized(consume)
        }

        /**
         * One read, bounded by how long the client may go quiet.
         *
         * The timeout becomes [RequestTimeout] here rather than travelling as the cancellation it
         * arrives as: the session's own `catch` rethrows `CancellationException` untouched, so a
         * body timeout would kill the coroutine with the client still waiting for an answer — the
         * dropped connection this whole milestone is about.
         */
        private suspend fun readWithinIdleTimeout(buffer: ByteBuffer): Int =
            try {
                withTimeout(idleTimeout.toMillis()) { connection.readSome(buffer) }
            } catch (_: TimeoutCancellationException) {
                throw RequestTimeout("the body went quiet for ${idleTimeout.toMillis()} ms")
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
                val read = readWithinIdleTimeout(buffer)
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
                val read = readWithinIdleTimeout(buffer)
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

    companion object {
        // Not private, and only because two of these are part of the surface: the defaults are
        // named in the constructor and a test names them to shorten them. The rest stays private.

        /**
         * Twenty seconds for a whole request head, which nothing legitimate comes near.
         *
         * A head is at most a few kilobytes and clients send it in one write; twenty seconds is
         * two orders of magnitude of slack for a bad link, and still finite for a client that has
         * no intention of finishing. nginx ships sixty for the same limit and sits on the public
         * internet, where a slower link is likelier than an attack.
         */
        val DEFAULT_HEAD_TIMEOUT: Duration = Duration.ofSeconds(20)

        /**
         * A minute of silence inside a body, and it is a gap rather than a total: a five-gibibyte
         * upload over a slow link is legitimate and takes as long as it takes. A total would be a
         * size limit with a clock on it, and would refuse exactly the uploads this store exists for.
         */
        val DEFAULT_BODY_IDLE_TIMEOUT: Duration = Duration.ofSeconds(60)

        /**
         * What one live connection costs while it is reading a request.
         *
         * The read buffer plus the head the parser is allowed to accumulate — the two allocations
         * a connection makes before anybody has decided anything about it. It understates the peak
         * of a connection that is *serving* an object, and deliberately: what this bounds is how
         * many can be waiting at once, which is the number an idle-connection flood drives up.
         */
        const val BYTES_PER_CONNECTION: Int = 32 * 1024 + 64 * 1024

        /** What fraction of the heap connections may hold before the ceiling is reached. */
        private const val CONNECTION_HEAP_FRACTION = 0.25

        /**
         * The published number of connections, derived the way the object ceiling is derived.
         *
         * A quarter of the heap rather than all of it, because the index is the live set here and
         * connections are the transient part: a server that spent its whole heap on sockets would
         * be refusing writes long before it refused a connection.
         */
        fun ceilingForHeap(heapBytes: Long = Runtime.getRuntime().maxMemory()): Int =
            ((heapBytes * CONNECTION_HEAP_FRACTION) / BYTES_PER_CONNECTION)
                .toLong()
                .coerceIn(16L, Int.MAX_VALUE.toLong())
                .toInt()

        /**
         * A head for the two answers sent to a request that never arrived: the `408` of a client
         * that stopped writing, and the `503` of a connection over the ceiling.
         *
         * Invented so that both go out through [respond] rather than through a second way of
         * writing a response — one path means one place where framing can be wrong. `HTTP/1.1`
         * because that is what the status line says regardless, and nothing else about it reaches
         * the client.
         */
        private val unsentRequest =
            HttpRequestParser.Head("GET", "/", "HTTP/1.1", emptyList())

        private fun reasonFor(status: Int): String =
            when (status) {
                400 -> "Bad Request"
                431 -> "Request Header Fields Too Large"
                505 -> "HTTP Version Not Supported"
                else -> "Error"
            }
    }
}
