package io.github.youndie.bochka.http

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many connections at once, and every answer has to belong to the question that asked it.
 *
 * Everything else in this repository talks to the server one request at a time, which is the shape
 * that finds none of the failures a session loop actually has: a response written to the wrong
 * socket, two coroutines interleaving into one connection, a buffer reused while another request
 * still points into it. None of those show up sequentially and all of them are silent — the client
 * gets *a* well-formed answer, just not its own.
 *
 * This lives in the gate rather than in the live-client harness because it is fast and because it
 * tests the part that outlives the milestone: the storage underneath is a draft due to be replaced,
 * the session loop is not.
 */
class HttpServerConcurrencyTest {
    /** Answers with the path it was asked about, so a mismatched answer is visible rather than plausible. */
    private class Echo : HttpHandler {
        override fun failed(
            head: HttpRequestParser.Head,
            cause: Throwable,
        ): HttpResponse = HttpResponse(500, "Error", body = (cause.message ?: "boom").toByteArray())

        override fun screen(head: HttpRequestParser.Head): HttpResponse? = null

        override suspend fun handle(
            head: HttpRequestParser.Head,
            body: HttpHandler.RequestBody,
        ): HttpResponse {
            val collected = java.io.ByteArrayOutputStream()
            body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
            val answer = "${head.path}:${collected.size()}"
            return HttpResponse(200, "OK", body = answer.toByteArray())
        }
    }

    private fun request(
        socket: Socket,
        reader: BufferedReader,
        path: String,
        payload: String,
    ): String {
        socket.getOutputStream().write(
            ("PUT $path HTTP/1.1\r\nHost: h\r\nContent-Length: ${payload.length}\r\n\r\n$payload")
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        socket.getOutputStream().flush()

        var length = 0
        while (true) {
            val line = reader.readLine() ?: error("connection closed mid-response")
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toInt()
            }
        }
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(body, 0, read)
    }

    @Test
    fun `thirty two connections at once each get their own answers`() {
        val connections = 32
        val perConnection = 20

        HttpServer(Echo()).use { server ->
            val start = CountDownLatch(1)
            val done = CountDownLatch(connections)
            val problems = ConcurrentLinkedQueue<String>()

            repeat(connections) { id ->
                Thread {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 5000)
                            socket.soTimeout = 15_000
                            socket.tcpNoDelay = true
                            val reader =
                                BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                            start.await()
                            repeat(perConnection) { n ->
                                // The path is unique per request, so an answer from somebody else's
                                // request is not merely wrong, it is identifiable.
                                val path = "/conn-$id/req-$n"
                                val payload = "$id-$n".repeat(id + 1)
                                val answer = request(socket, reader, path, payload)
                                val expected = "$path:${payload.length}"
                                if (answer != expected) problems += "expected '$expected', got '$answer'"
                            }
                        }
                    } catch (e: Throwable) {
                        problems += "connection $id: ${e::class.simpleName}: ${e.message}"
                    } finally {
                        done.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }

            start.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS), "connections did not finish: ${problems.take(3)}")
            assertTrue(problems.isEmpty(), "${problems.size} problems, first three: ${problems.take(3)}")
        }
    }

    @Test
    fun `one connection reused a hundred times keeps its answers in order`() {
        // Keep-alive is where a leftover byte from the previous body shows up: the next request
        // starts in the middle of the last one, and the answer that comes back is well-formed and
        // belongs to nothing.
        HttpServer(Echo()).use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 5000)
                socket.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))

                for (n in 1..100) {
                    // The payload length changes every time, so a body boundary read one byte off
                    // desynchronises the connection instead of accidentally lining up.
                    val payload = "x".repeat(n)
                    assertEquals("/req-$n:$n", request(socket, reader, "/req-$n", payload))
                }
            }
        }
    }

    @Test
    fun `a connection that dies mid-request does not disturb the others`() {
        // A client vanishing is ordinary, and the failure it can cause is not: if the session loop
        // lets that exception escape, it takes the accept loop or the selector with it and every
        // other connection stops being served.
        HttpServer(Echo()).use { server ->
            repeat(20) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 5000)
                    socket.getOutputStream().write(
                        "PUT /half HTTP/1.1\r\nHost: h\r\nContent-Length: 100\r\n\r\nonly-".toByteArray(),
                    )
                    socket.getOutputStream().flush()
                    socket.setSoLinger(true, 0)
                }
            }

            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 5000)
                socket.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                assertEquals("/after:5", request(socket, reader, "/after", "hello"))
            }
        }
    }
}
