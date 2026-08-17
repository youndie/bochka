package io.github.youndie.bochka.http

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
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
            body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
            bodySeen.set(collected.toByteArray())
            return HttpResponse(200, "OK", body = "ok:${collected.size()}".toByteArray())
        }
    }

    private fun withServer(
        handler: HttpHandler,
        block: (Socket, BufferedReader) -> Unit,
    ) {
        HttpServer(handler).use { server ->
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
}
