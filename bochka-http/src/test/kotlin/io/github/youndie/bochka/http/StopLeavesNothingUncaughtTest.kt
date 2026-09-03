package io.github.youndie.bochka.http

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stopping the server reports nothing to the JVM's uncaught handler.
 *
 * The accept loop runs as a bare `scope.launch { acceptLoop() }`, and [HttpServer.close] closes the
 * server channel **first**, waits out the grace period, and only then cancels the scope. A loop
 * woken inside that window calls `accept()` on a closed channel and throws — out of the coroutine,
 * past a `SupervisorJob` with no handler, into the thread's default uncaught handler.
 *
 * In a server that is what a shutdown looks like: a stack trace on the way out, for something that
 * is not a failure. In a test JVM it is worse than untidy — `runTest` collects exceptions the
 * platform reported before it started and fails **the next test that happens to use it**, whichever
 * that is. That is how this was found: `PolicyConditionKeysTest` failed on CI with
 * `UncaughtExceptionsBeforeTest`, having nothing to do with it, and passed everywhere else.
 *
 * The assertion is about the property rather than the timing: a connection is made and answered
 * first, so the loop has certainly gone around and is certainly suspended when the close begins.
 */
class StopLeavesNothingUncaughtTest {
    private val previous = Thread.getDefaultUncaughtExceptionHandler()
    private val caught = CopyOnWriteArrayList<Throwable>()

    @AfterTest
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(previous)

    private object Ok : HttpHandler {
        override fun screen(head: HttpRequestParser.Head): HttpResponse? = null

        override suspend fun handle(
            head: HttpRequestParser.Head,
            body: HttpHandler.RequestBody,
        ) = HttpResponse(200, "OK", body = "ok".toByteArray())

        override fun failed(
            head: HttpRequestParser.Head,
            cause: Throwable,
        ) = HttpResponse(500, "Internal Server Error")
    }

    @Test
    fun `closing the server tells nobody about a channel it closed itself`() {
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> caught += thrown }

        val server = HttpServer(Ok)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2000)
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
            val answer = socket.getInputStream().readNBytes(12)
            assertTrue(String(answer).startsWith("HTTP/1.1 200"), String(answer))
        }
        server.close()

        // The window this is about is the grace wait inside `close`, which is over by the time it
        // returns; a moment more so a report on another thread has somewhere to arrive.
        Thread.sleep(200)

        assertEquals(
            emptyList(),
            caught.map { "${it::class.simpleName}: ${it.message}" },
            "stopping the server reported an exception nobody asked about",
        )
    }
}
