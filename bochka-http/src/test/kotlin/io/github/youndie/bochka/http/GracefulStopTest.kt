package io.github.youndie.bochka.http

import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stopping and being killed are different things, and until now they were not (M-292).
 *
 * `close()` was `scope.cancel()`: every request in flight was cancelled where it stood, and the
 * client got a socket that closed with no bytes in it — the same thing a `SIGKILL` gives, and the
 * same thing every SDK reads as a network failure and retries. `docker stop` sends `SIGTERM` and
 * waits ten seconds before `SIGKILL`, so those ten seconds were being spent on nothing.
 *
 * What a stop has to be instead is three steps in order: stop accepting, finish what was started,
 * then let go. The window is bounded, because "finish what was started" without a limit is a
 * process that will not stop.
 */
class GracefulStopTest {
    private class Slow(
        val started: AtomicBoolean = AtomicBoolean(false),
    ) : HttpHandler {
        override fun screen(head: HttpRequestParser.Head): HttpResponse? = null

        override suspend fun handle(
            head: HttpRequestParser.Head,
            body: HttpHandler.RequestBody,
        ): HttpResponse {
            started.set(true)
            // Long enough that the stop lands in the middle of it, short enough that a test which
            // fails does so quickly.
            delay(400)
            return HttpResponse(200, "OK", body = "finished\n".toByteArray())
        }

        override fun failed(
            head: HttpRequestParser.Head,
            cause: Throwable,
        ): HttpResponse = HttpResponse(500, "Error", body = (cause.message ?: "boom").toByteArray())
    }

    @Test
    fun `a request in flight when the stop begins is finished, not cancelled`() {
        val handler = Slow()
        val server = HttpServer(handler, shutdownGrace = Duration.ofSeconds(5))
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000)
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()

            while (!handler.started.get()) Thread.sleep(5)
            val stopping = Thread { server.close() }
            stopping.start()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val status = reader.readLine()
            assertEquals("HTTP/1.1 200 OK", status, "the request in flight was cancelled rather than finished")
            stopping.join(10_000)
            assertTrue(!stopping.isAlive, "the stop never returned")
        }
    }

    @Test
    fun `nothing new is accepted once the stop has begun`() {
        // The other half, and the one that makes the first half finite: a server that finishes what
        // it started while still taking new work has not begun stopping at all.
        val handler = Slow()
        val server = HttpServer(handler, shutdownGrace = Duration.ofSeconds(5))
        val port = server.boundPort

        Socket().use { held ->
            held.connect(InetSocketAddress("127.0.0.1", port), 2000)
            held.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
            held.getOutputStream().flush()
            while (!handler.started.get()) Thread.sleep(5)

            val stopping = Thread { server.close() }
            stopping.start()
            Thread.sleep(50)

            val refused =
                runCatching {
                    Socket().use { late ->
                        late.connect(InetSocketAddress("127.0.0.1", port), 500)
                        late.soTimeout = 1000
                        late.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                        late.getOutputStream().flush()
                        late.getInputStream().read()
                    }
                }
            assertTrue(
                refused.isFailure || refused.getOrNull() == -1,
                "a connection made after the stop began was served: ${refused.getOrNull()}",
            )
            stopping.join(10_000)
        }
    }

    @Test
    fun `the window is a limit rather than a wish`() {
        // A stop that waits for a client which never finishes is a process that does not stop, and
        // `docker stop` answers that with `SIGKILL` after ten seconds. The window is what turns
        // "finish what you started" into something with an end.
        val handler = Slow()
        val server = HttpServer(handler, shutdownGrace = Duration.ofMillis(100))
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000)
            socket.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            while (!handler.started.get()) Thread.sleep(5)

            val began = System.nanoTime()
            server.close()
            val took = (System.nanoTime() - began) / 1_000_000

            assertTrue(took < 2_000, "the stop waited ${took}ms on a window of 100ms")
        }
    }
}
