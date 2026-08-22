package io.github.youndie.bochka.http

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two answers the server used to give with nobody watching (M-229).
 *
 * Every other response passes the handler, and the app layer wraps the handler to log it. These
 * two do not: a request that will not parse is answered from inside the server, and a session that
 * throws is caught by a `catch` that swallows. The client sees a `400` and a dropped connection;
 * the operator's log has neither, which is the state where diagnosis turns into guessing — the
 * same complaint M-205 made about a `500`, one layer further down.
 *
 * The server still does not log: `bochka-http` has no logging and is not acquiring any, for the
 * reason its own comment gave — a `printStackTrace` here would be a decision made by accident.
 * What it gains is a way to **say** so, and the app layer decides what that means.
 */
class SilentAnswerTest {
    private class Silent : HttpHandler {
        val told = CopyOnWriteArrayList<String>()

        override fun screen(head: HttpRequestParser.Head): HttpResponse? = null

        override suspend fun handle(
            head: HttpRequestParser.Head,
            body: HttpHandler.RequestBody,
        ): HttpResponse = HttpResponse(200, "OK")

        override fun failed(
            head: HttpRequestParser.Head,
            cause: Throwable,
        ): HttpResponse = HttpResponse(500, "Error")

        override fun malformed(
            status: Int,
            cause: Throwable,
        ) {
            told += "malformed $status ${cause.message}"
        }

        override fun abandoned(cause: Throwable) {
            told += "abandoned $cause"
        }
    }

    private fun talk(
        handler: HttpHandler,
        say: String,
    ) {
        HttpServer(handler).use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000)
                socket.soTimeout = 5000
                socket.getOutputStream().write(say.toByteArray(StandardCharsets.ISO_8859_1))
                socket.getOutputStream().flush()
                BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                    .readLine()
            }
            // The answer is written before the telling on the same coroutine, but the client reads
            // it from another thread; give the session a moment to finish rather than racing it.
            Thread.sleep(200)
        }
    }

    @Test
    fun `a request that will not parse is answered and said out loud`() {
        val handler = Silent()

        // No method, no version: refused by the parser, answered by the server, and until now
        // invisible to everything above it.
        talk(handler, "nonsense\r\n\r\n")

        assertTrue(
            handler.told.any { it.startsWith("malformed 4") },
            "the server answered a malformed request and told nobody: ${handler.told}",
        )
    }

    @Test
    fun `the handler is not asked to answer what it never saw`() {
        // `malformed` returns nothing on purpose. The response is the server's — the request never
        // became a head, so there is no route, no bucket and nothing for the protocol layer to
        // shape. Telling and answering are different jobs, and mixing them here would put an S3
        // error document behind a request that is not yet S3.
        val handler = Silent()

        talk(handler, "GET /x HTTP/1.1\r\n" + "X".repeat(70_000) + ": y\r\n\r\n")

        assertTrue(handler.told.isNotEmpty(), "an oversized head was answered silently")
    }
}
