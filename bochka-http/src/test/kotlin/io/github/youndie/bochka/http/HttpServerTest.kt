package io.github.youndie.bochka.http

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server against a real socket, spoken to by a client that shares no code with it.
 *
 * The neighbouring broker's rule, and it earned it: the only bug of an entire milestone was found
 * by a smoke test that talked to the built distribution from Python, because everything else
 * started the server from inside its own process and agreed with itself about the protocol.
 */
class HttpServerTest {
    private class Recording(
        val screenWith: (HttpRequestParser.Head) -> HttpResponse? = { null },
        /**
         * Whether the handler reads the request body, which the real one does not always do.
         *
         * It read it unconditionally, and that hid a defect for the life of the project: reading
         * the body is what marks the socket clean, so a `GET` — which has no body and whose
         * handler never asks for one — left the connection looking unfinished and the server
         * closed it. Every test here answered from a handler that drained, so every test agreed
         * that connections are kept.
         */
        val drainsBody: Boolean = true,
    ) : HttpHandler {
        val bodySeen = AtomicReference(ByteArray(0))
        val handleCalled = AtomicBoolean(false)

        override fun failed(
            head: HttpRequestParser.Head,
            cause: Throwable,
        ): HttpResponse = HttpResponse(500, "Error", body = (cause.message ?: "boom").toByteArray())

        override fun screen(head: HttpRequestParser.Head): HttpResponse? = screenWith(head)

        override suspend fun handle(
            head: HttpRequestParser.Head,
            body: HttpHandler.RequestBody,
        ): HttpResponse {
            handleCalled.set(true)
            val collected = java.io.ByteArrayOutputStream()
            if (drainsBody) {
                body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
            }
            bodySeen.set(collected.toByteArray())
            return HttpResponse(200, "OK", body = "ok:${collected.size()}".toByteArray())
        }
    }

    private fun withServer(
        handler: HttpHandler,
        headTimeout: java.time.Duration = HttpServer.DEFAULT_HEAD_TIMEOUT,
        bodyIdleTimeout: java.time.Duration = HttpServer.DEFAULT_BODY_IDLE_TIMEOUT,
        block: (Socket, BufferedReader) -> Unit,
    ) {
        HttpServer(handler, headTimeout = headTimeout, bodyIdleTimeout = bodyIdleTimeout).use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000)
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                block(socket, reader)
            }
        }
    }

    /**
     * [expectBody] is not a convenience: a `HEAD` response states the `Content-Length` the `GET`
     * would have had and sends nothing, so a reader that always believes the header hangs. Real
     * clients know this; the first version of this helper did not, and the timeout it produced
     * looked exactly like a server that had stopped answering.
     */
    private fun readResponse(
        reader: BufferedReader,
        expectBody: Boolean = true,
    ): Pair<List<String>, String> {
        val lines = ArrayList<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            lines.add(line)
        }
        val header = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
        val stated = header?.substringAfter(':')?.trim()?.toInt() ?: 0
        val length = if (expectBody) stated else 0
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return lines to String(body, 0, read)
    }

    @Test
    fun `a request over a real socket is answered`() {
        val handler = Recording()
        withServer(handler) { socket, reader ->
            socket.getOutputStream().write(
                "PUT /photos/a.txt HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello".toByteArray(),
            )
            val (lines, body) = readResponse(reader)

            assertEquals("HTTP/1.1 200 OK", lines.first())
            assertEquals("ok:5", body)
            assertEquals("hello", String(handler.bodySeen.get()))
        }
    }

    @Test
    fun `a refusal from the head is answered without the body being sent`() {
        // M-23, and the reason the handler has two methods. A client with Expect: 100-continue is
        // waiting for permission; a server that has to read the body before deciding makes a 403
        // cost the upload it refuses.
        val handler =
            Recording(screenWith = { HttpResponse(403, "Forbidden", body = "denied".toByteArray()) })

        withServer(handler) { socket, reader ->
            socket.getOutputStream().write(
                "PUT /photos/big.bin HTTP/1.1\r\nHost: h\r\nContent-Length: 1048576\r\nExpect: 100-continue\r\n\r\n"
                    .toByteArray(),
            )
            val (lines, body) = readResponse(reader)

            assertEquals("HTTP/1.1 403 Forbidden", lines.first())
            assertEquals("denied", body)
            assertFalse(handler.handleCalled.get(), "the body must never have been asked for")
            assertTrue(lines.any { it.equals("Connection: close", ignoreCase = true) })
        }
    }

    @Test
    fun `an accepted request with expect gets a hundred continue first`() {
        val handler = Recording()
        withServer(handler) { socket, reader ->
            val request = "PUT /photos/a.txt HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())

            // The interim response comes before the body is sent — which is the whole mechanism:
            // botocore waits exactly one second for this line and then sends anyway.
            assertEquals("HTTP/1.1 100 Continue", reader.readLine())
            assertEquals("", reader.readLine())

            socket.getOutputStream().write("hello".toByteArray())
            val (lines, body) = readResponse(reader)

            assertEquals("HTTP/1.1 200 OK", lines.first())
            assertEquals("ok:5", body)
        }
    }

    @Test
    fun `two requests share one connection`() {
        val handler = Recording()
        withServer(handler) { socket, reader ->
            repeat(2) { i ->
                val request = "PUT /photos/$i HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nabc"
                socket.getOutputStream().write(request.toByteArray())
                val (lines, body) = readResponse(reader)
                assertEquals("HTTP/1.1 200 OK", lines.first(), "request $i")
                assertEquals("ok:3", body)
            }
        }
    }

    @Test
    fun `two reads share one connection, because a request with no body leaves nothing on the socket`() {
        // The test above proves the same thing for `PUT`, and that is the whole reason this one
        // exists: the connection was kept only when a handler had read a body, and a `GET` has no
        // body to read. Every read therefore ended its connection — found by pointing DuckDB at
        // this server, where 373 requests took 370 connections and 368 were closed from here.
        // The handler does not read the body, because the real one does not: a `GET` has none to
        // read. Every other test here uses a handler that reads unconditionally, which is why they
        // all agreed the connection was kept.
        val handler = Recording(drainsBody = false)
        withServer(handler) { socket, reader ->
            repeat(2) { i ->
                socket.getOutputStream().write("GET /photos/$i HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                val (lines, _) = readResponse(reader)
                assertTrue(
                    lines.isNotEmpty(),
                    "read $i got no answer at all: the connection ended with the request before it",
                )
                assertEquals("HTTP/1.1 200 OK", lines.first(), "read $i")
            }
        }
    }

    @Test
    fun `a client that stops halfway through the head is told 408 and let go`() {
        // A real slow client rather than a mocked clock: it writes half a head and then does
        // nothing, which is what a connection-exhaustion attack looks like and what a dead NAT
        // looks like too. Before the timeout existed this held the connection for as long as the
        // process lived — `bochka-http` had no time limit of any kind, only size limits.
        val handler = Recording()
        withServer(handler, headTimeout = java.time.Duration.ofMillis(300)) { socket, reader ->
            socket.getOutputStream().write("GET /photos HTTP/1.1\r\nHost: h\r\n".toByteArray())
            socket.getOutputStream().flush()

            val (lines, _) = readResponse(reader, expectBody = false)
            assertTrue(lines.isNotEmpty(), "the half-written head got no answer at all")
            assertEquals("HTTP/1.1 408 Request Timeout", lines.first())
            assertTrue(
                lines.any { it.equals("Connection: close", ignoreCase = true) },
                "a timed-out connection has to be closed, and the client has to be told: $lines",
            )
            assertEquals(-1, reader.read(), "the connection stayed open after the timeout")
        }
    }

    @Test
    fun `a body that stops arriving is told 408 rather than held`() {
        // The other half, and it is a different clock: the head arrived in time, so the request is
        // real and the handler is already reading. What must not happen is the slot being held for
        // ever by a client that promised ten bytes and sent one.
        val handler = Recording()
        withServer(handler, bodyIdleTimeout = java.time.Duration.ofMillis(300)) { socket, reader ->
            socket.getOutputStream().write(
                "PUT /photos/k HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\na".toByteArray(),
            )
            socket.getOutputStream().flush()

            val (lines, _) = readResponse(reader, expectBody = false)
            assertTrue(lines.isNotEmpty(), "the stalled body got no answer at all")
            assertEquals("HTTP/1.1 408 Request Timeout", lines.first())
        }
    }

    @Test
    fun `a connection over the ceiling is refused by name rather than in silence`() {
        // Two held open by doing nothing, which is what a connection at its limit looks like from
        // the server: alive, counted, and not yet a request. The third has to be told.
        val handler = Recording()
        HttpServer(handler, maxConnections = 2).use { server ->
            val held = (1..2).map { Socket().apply { connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000) } }
            try {
                Socket().use { third ->
                    third.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000)
                    third.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(third.getInputStream(), StandardCharsets.ISO_8859_1))
                    val (lines, _) = readResponse(reader, expectBody = false)

                    // Answered, not dropped. A server that simply stops accepting looks to a client
                    // exactly like a server that has died, and to an operator like nothing at all:
                    // the refusal has to carry a status somebody can find in a log.
                    assertTrue(lines.isNotEmpty(), "the connection over the ceiling was refused in silence")
                    assertEquals("HTTP/1.1 503 Service Unavailable", lines.first())
                    assertEquals(-1, reader.read(), "the refused connection was left open")
                }
            } finally {
                held.forEach { it.close() }
            }
        }
    }

    @Test
    fun `a slot comes back when its connection closes`() {
        // The half that makes the ceiling a ceiling rather than a lifetime budget. Without it the
        // count only rises, and a server that has served its limit refuses everybody for ever.
        val handler = Recording()
        HttpServer(handler, maxConnections = 1).use { server ->
            val first = Socket()
            first.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000)
            first.close()

            // The close has to reach the server before the next connection is counted, and there is
            // no callback for that: the socket is closed here, the server notices on its own thread.
            for (attempt in 1..50) {
                Socket().use { next ->
                    next.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000)
                    next.soTimeout = 1000
                    next.getOutputStream().write("GET /photos HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                    next.getOutputStream().flush()
                    val reader = BufferedReader(InputStreamReader(next.getInputStream(), StandardCharsets.ISO_8859_1))
                    val (lines, _) = readResponse(reader)
                    if (lines.firstOrNull() == "HTTP/1.1 200 OK") return
                }
                Thread.sleep(20)
            }
            throw AssertionError("the slot never came back after its connection closed")
        }
    }

    @Test
    fun `a connection whose body was left on the socket is not reused`() {
        // The danger is one sentence long: bytes of a body that nobody read are still on the
        // socket, and a connection reused after that reads them as the next request line. That is
        // the ambiguous framing this parser refuses by name, arriving through time instead of
        // through a header.
        val handler = Recording(drainsBody = false)
        withServer(handler) { socket, reader ->
            socket.getOutputStream().write(
                "PUT /photos/k HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello".toByteArray(),
            )
            socket.getOutputStream().flush()

            val (lines, _) = readResponse(reader)
            assertEquals("HTTP/1.1 200 OK", lines.first())
            assertTrue(
                lines.any { it.equals("Connection: close", ignoreCase = true) },
                "the answer did not say the connection was over: $lines",
            )
            assertEquals(-1, reader.read(), "the connection stayed open with five unread bytes on it")
        }
    }

    @Test
    fun `a body that was promised and never finished ends the connection`() {
        // The same class through the other door: the length is honest, the bytes stop coming. The
        // handler is inside `forEach` when they do, so what ends this is the idle clock (M-282) —
        // and what matters here is that the connection does not come back afterwards carrying the
        // three bytes that did arrive.
        val handler = Recording()
        withServer(handler, bodyIdleTimeout = java.time.Duration.ofMillis(300)) { socket, reader ->
            socket.getOutputStream().write(
                "PUT /photos/k HTTP/1.1\r\nHost: h\r\nContent-Length: 99\r\n\r\nabc".toByteArray(),
            )
            socket.getOutputStream().flush()

            val (lines, _) = readResponse(reader, expectBody = false)
            assertEquals("HTTP/1.1 408 Request Timeout", lines.first())
            assertEquals(-1, reader.read(), "an unfinished body left the connection open")
        }
    }

    @Test
    fun `a request split across packets is still one request`() {
        val handler = Recording()
        withServer(handler) { socket, reader ->
            val raw = "PUT /photos/a.txt HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello".toByteArray()
            for (b in raw) {
                socket.getOutputStream().write(byteArrayOf(b))
                socket.getOutputStream().flush()
            }
            val (lines, _) = readResponse(reader)

            assertEquals("HTTP/1.1 200 OK", lines.first())
            assertEquals("hello", String(handler.bodySeen.get()))
        }
    }

    @Test
    fun `a head request gets the headers and no body`() {
        val handler = Recording()
        withServer(handler) { socket, reader ->
            socket.getOutputStream().write("HEAD /photos/a.txt HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
            val (lines, body) = readResponse(reader, expectBody = false)

            assertEquals("HTTP/1.1 200 OK", lines.first())
            assertTrue(lines.any { it.startsWith("Content-Length: 4") }, lines.toString())
            assertEquals("", body, "HEAD carries the length of the body it is not sending")
        }
    }

    @Test
    fun `a malformed request gets a status rather than a dropped connection`() {
        val handler = Recording()
        withServer(handler) { socket, reader ->
            socket.getOutputStream().write("GET /a HTTP/1.1\r\nContent-Length : 5\r\n\r\n".toByteArray())
            val (lines, _) = readResponse(reader)

            assertEquals("HTTP/1.1 400 Bad Request", lines.first())
            assertFalse(handler.handleCalled.get())
        }
    }

    @Test
    fun `connection close is honoured`() {
        val handler = Recording()
        withServer(handler) { socket, reader ->
            socket.getOutputStream().write("GET /a HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n".toByteArray())
            readResponse(reader)

            assertEquals(-1, reader.read(), "the server should have closed the connection")
        }
    }

    /**
     * The read path this whole project is built around, and until now nothing in this module ran a
     * byte through it: `FileSlice` was constructed in `:bochka-app` and nowhere else, so `sendFile`
     * and `sendFiltered` were reached only end to end, through S3. Thirty mutations of their
     * arithmetic went unnoticed because no test here ever opened a file.
     *
     * The size is deliberate. A payload larger than the socket's send buffer makes `transferTo`
     * return short and the loop go round again — the branch the KDoc calls "not optional" and the
     * one a small fixture never enters.
     */
    private fun withFile(
        size: Int,
        block: (Path, ByteArray) -> Unit,
    ) {
        val bytes = ByteArray(size) { ('a' + it % 26).code.toByte() }
        val path = Files.createTempFile("bochka-send", ".bin")
        try {
            Files.write(path, bytes)
            block(path, bytes)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun serving(slice: (Path) -> HttpResponse.FileSlice) =
        { path: Path ->
            object : HttpHandler {
                override fun failed(
                    head: HttpRequestParser.Head,
                    cause: Throwable,
                ): HttpResponse = HttpResponse(500, "Error")

                override fun screen(head: HttpRequestParser.Head): HttpResponse? = null

                override suspend fun handle(
                    head: HttpRequestParser.Head,
                    body: HttpHandler.RequestBody,
                ): HttpResponse = HttpResponse(200, "OK", file = slice(path))
            }
        }

    @Test
    fun `a file goes out whole, over more socket writes than one`() {
        withFile(400_000) { path, bytes ->
            withServer(serving { HttpResponse.FileSlice(it, 0, bytes.size.toLong()) }(path)) { socket, reader ->
                socket.getOutputStream().write("GET /o HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                val (lines, body) = readResponse(reader)

                assertEquals("HTTP/1.1 200 OK", lines.first())
                assertTrue(lines.any { it == "Content-Length: ${bytes.size}" }, lines.toString())
                assertEquals(String(bytes, StandardCharsets.ISO_8859_1), body)
            }
        }
    }

    @Test
    fun `a range goes out as the range and not as the file`() {
        // offset and length are two longs the loop adds to and subtracts from, and every mutation
        // of that arithmetic survived: with offset zero and a length equal to the file, the wrong
        // answers and the right one are the same bytes.
        withFile(100_000) { path, bytes ->
            val offset = 12_345L
            val length = 50_000L
            withServer(serving { HttpResponse.FileSlice(it, offset, length) }(path)) { socket, reader ->
                socket.getOutputStream().write("GET /o HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                val (_, body) = readResponse(reader)

                val expected = String(bytes, offset.toInt(), length.toInt(), StandardCharsets.ISO_8859_1)
                assertEquals(expected, body)
            }
        }
    }

    @Test
    fun `a head states the length of the file and sends none of it`() {
        withFile(70_000) { path, bytes ->
            withServer(serving { HttpResponse.FileSlice(it, 0, bytes.size.toLong()) }(path)) { socket, reader ->
                socket.getOutputStream().write("HEAD /o HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                val (lines, body) = readResponse(reader, expectBody = false)

                assertTrue(lines.any { it == "Content-Length: ${bytes.size}" }, lines.toString())
                assertEquals("", body)
            }
        }
    }

    @Test
    fun `a filtered range is transformed, and the filter sees the range rather than the file`() {
        // The slow path (SSE-C, M26). It reads in 64 KiB chunks, so a range longer than one chunk
        // and starting away from zero is the only shape that tells a correct `position + read`
        // from an incorrect one.
        withFile(200_000) { path, bytes ->
            val offset = 5_000L
            val length = 150_000L
            val flip =
                HttpResponse.Filter { buffer, from, count ->
                    for (i in from until from + count) buffer[i] = (buffer[i].toInt() xor 0x20).toByte()
                }
            withServer(serving { HttpResponse.FileSlice(it, offset, length, through = flip) }(path)) { socket, reader ->
                socket.getOutputStream().write("GET /o HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                val (_, body) = readResponse(reader)

                val expected =
                    ByteArray(length.toInt()) { (bytes[offset.toInt() + it].toInt() xor 0x20).toByte() }
                assertEquals(String(expected, StandardCharsets.ISO_8859_1), body)
            }
        }
    }

    @Test
    fun `a closed server gives the port back and its selector thread ends`() {
        // `close` is three calls in a row and removing any one of them left the whole suite green:
        // the harness closes the server at the end of every test and never asks what closing did.
        // A server that keeps the port is what the next start collides with, and a selector thread
        // that outlives its server is one more thread on every run that leaks one.
        val server = HttpServer(Recording())
        val port = server.boundPort
        val before = Thread.getAllStackTraces().keys.count { it.name == "bochka-selector" && it.isAlive }

        server.close()

        // Bindable again: SO_REUSEADDR lets a port in TIME_WAIT be taken, never one still listening.
        java.net.ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)) }

        val deadline = System.nanoTime() + 2_000_000_000L
        var after = before
        while (System.nanoTime() < deadline) {
            after = Thread.getAllStackTraces().keys.count { it.name == "bochka-selector" && it.isAlive }
            if (after < before) break
            Thread.sleep(20)
        }
        assertTrue(after < before, "the selector thread outlived the server it belongs to")
    }
}
