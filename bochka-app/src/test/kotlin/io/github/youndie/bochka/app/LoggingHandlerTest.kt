package io.github.youndie.bochka.app

import io.github.youndie.bochka.http.HttpHandler
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.http.HttpResponse
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The line a request leaves behind, and the one a failure used to not leave (M-205).
 *
 * A `500` did reach standard output — `failed` has always printed the cause — but in a **different
 * shape** from every other answer: no status, no query, no framing. So the log said nothing to
 * anybody counting by status or grouping by route, and `-> 500` matched nothing in a log full of
 * them. The backlog recorded that as "does not reach the log at all", which was the observation
 * (`grep -> 500` finding nothing) rather than the fact.
 *
 * The query mattered most of the three. `bochka failed PUT /bucket/key` names half a dozen
 * different operations — `?acl`, `?tagging`, `?uploads`, a plain write — and which one it was is
 * the first question anybody asks.
 */
class LoggingHandlerTest {
    private val head =
        HttpRequestParser.Head(
            method = "PUT",
            target = "/photos/holiday.jpg?tagging",
            version = "HTTP/1.1",
            headers = listOf("host" to "s3.example.com", "x-amz-content-sha256" to "UNSIGNED-PAYLOAD"),
        )

    /** A handler that throws where the server would catch, and answers `500` the way S3Handler does. */
    private class Throwing(
        private val boom: Throwable = IllegalStateException("the index said no"),
    ) : HttpHandler {
        override fun screen(head: HttpRequestParser.Head): HttpResponse? = null

        override suspend fun handle(
            head: HttpRequestParser.Head,
            body: HttpHandler.RequestBody,
        ): HttpResponse = throw boom

        override fun failed(
            head: HttpRequestParser.Head,
            cause: Throwable,
        ): HttpResponse = HttpResponse(500, "Internal Server Error", body = "<Error/>".toByteArray())
    }

    private fun printed(work: () -> Unit): String {
        val collected = ByteArrayOutputStream()
        val before = System.out
        System.setOut(PrintStream(collected, true))
        try {
            work()
        } finally {
            System.setOut(before)
        }
        return collected.toString()
    }

    @Test
    fun `a failure leaves the same shaped line as every other answer`() {
        val logging = LoggingHandler(Throwing(), enabled = true)

        val output = printed { logging.failed(head, IllegalStateException("the index said no")) }

        // The three things the old line dropped, and the reason it was invisible to every question
        // anybody asks a log.
        assertContains(output, "-> 500")
        assertContains(output, "/photos/holiday.jpg?tagging")
        assertContains(output, "framing=UNSIGNED-PAYLOAD")
        // And the one thing it did carry, which is worth keeping.
        assertContains(output, "the index said no")
    }

    @Test
    fun `and it is printed whether or not logging was asked for`() {
        // A server admitting a bug is not a logging preference: with `BOCHKA_LOG` off there would
        // otherwise be nothing at all to look at, and the client's 500 would be the only evidence.
        val logging = LoggingHandler(Throwing(), enabled = false)

        val output = printed { logging.failed(head, IllegalStateException("the index said no")) }

        assertContains(output, "-> 500")
        assertContains(output, "the index said no")
    }

    @Test
    fun `a request that never became one still names its status`() {
        // M-229: the server answers this without the handler, so the only thing the log can carry
        // is what it was and what it got. The status column stays, because a line without one is
        // invisible to counting — which was the whole of M-205.
        val logging = LoggingHandler(Throwing(), enabled = true)

        val output = printed { logging.malformed(400, IllegalArgumentException("no method")) }

        assertContains(output, "-> 400")
        assertContains(output, "no method")
    }

    @Test
    fun `the client's mistakes stay under the flag, unlike the server's`() {
        // The difference is whose fault it is. A 500 is this server admitting a bug and prints
        // regardless; a head that will not parse and a connection that hangs up are ordinary
        // traffic on the open internet, and printing every one unasked loses the log.
        val quiet = LoggingHandler(Throwing(), enabled = false)

        val malformed = printed { quiet.malformed(400, IllegalArgumentException("no method")) }
        val abandoned = printed { quiet.abandoned(java.io.IOException("connection reset")) }
        val failure = printed { quiet.failed(head, IllegalStateException("the index said no")) }

        assertTrue(malformed.isEmpty(), malformed)
        assertTrue(abandoned.isEmpty(), abandoned)
        assertContains(failure, "-> 500")
    }

    @Test
    fun `an ordinary answer still logs once, and only when asked`() {
        val plain =
            object : HttpHandler {
                override fun screen(head: HttpRequestParser.Head): HttpResponse? = null

                override suspend fun handle(
                    head: HttpRequestParser.Head,
                    body: HttpHandler.RequestBody,
                ): HttpResponse = HttpResponse(200, "OK")

                override fun failed(
                    head: HttpRequestParser.Head,
                    cause: Throwable,
                ): HttpResponse = HttpResponse(500, "Internal Server Error")
            }
        val empty =
            object : HttpHandler.RequestBody {
                override suspend fun forEach(consume: (ByteArray, Int, Int) -> Unit) = Unit
            }

        val on = printed { runBlocking { LoggingHandler(plain, enabled = true).handle(head, empty) } }
        val off = printed { runBlocking { LoggingHandler(plain, enabled = false).handle(head, empty) } }

        assertEquals(1, on.lines().count { it.contains("-> 200") }, on)
        assertTrue(off.isEmpty(), "with logging off an ordinary answer says nothing: $off")
    }
}
