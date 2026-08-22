package io.github.youndie.bochka.app

import io.github.youndie.bochka.http.HttpHandler
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.http.HttpResponse

/**
 * One line per request, on standard output, when `BOCHKA_LOG=1`.
 *
 * It exists to make a claim checkable rather than to be a logging system. M3 says a `PUT` has to be
 * accepted **four different ways**, and there is no way to see from the outside which framing a
 * client chose — `aws s3 cp` picks one on its own and changes its mind between versions. The line
 * prints `x-amz-content-sha256`, so the harness can grep for all four and fail if a client quietly
 * stopped exercising one.
 *
 * Real logging — levels, structure, somewhere to send it — is M11 along with everything else about
 * running this thing.
 */
class LoggingHandler(
    private val delegate: HttpHandler,
    private val enabled: Boolean,
) : HttpHandler {
    override fun failed(
        head: HttpRequestParser.Head,
        cause: Throwable,
    ): HttpResponse {
        val response = delegate.failed(head, cause)
        // The same shape as every other answer, and that is the whole of M-205. This used to print
        // `bochka failed PUT /photos/holiday.jpg: cause` — which reached the log, and answered none
        // of the questions a log is asked. No status, so counting `500`s found none and `grep
        // '\-> 500'` matched nothing in a log full of them. No query, so `?acl`, `?tagging`,
        // `?uploads` and a plain write were one line. No framing, so the one thing that most often
        // explains a failure was missing from the failures.
        //
        // Printed whatever the flag says, and that half was right from the start: a server
        // admitting a bug is not a logging preference. With `BOCHKA_LOG` off the client's `500`
        // would otherwise be the only evidence that anything happened.
        log(head, response.status, "failed", response, cause)
        return response
    }

    /**
     * A request the server answered without the handler seeing it (M-229).
     *
     * **Under the flag, unlike [failed], and the difference is whose fault it is.** A `500` is this
     * server admitting a bug and prints whatever the configuration says; a head that will not parse
     * is the client's doing, and a store on the open internet meets enough of them that printing
     * every one unasked would be a way to lose the log rather than to keep it.
     *
     * The status column is kept even though there is no method and no target to put beside it: the
     * whole of M-205 was that a line without `-> status` is invisible to every question a log is
     * asked.
     */
    override fun malformed(
        status: Int,
        cause: Throwable,
    ) {
        if (enabled) println("bochka malformed -> $status cause=$cause")
    }

    /**
     * A connection that died with nobody left to answer (M-229).
     *
     * Under the flag for the same reason, and more so: a client that hangs up mid-body is ordinary
     * traffic rather than an event, and this fires for every one of them.
     */
    override fun abandoned(cause: Throwable) {
        if (enabled) println("bochka abandoned cause=$cause")
    }

    override fun screen(head: HttpRequestParser.Head): HttpResponse? {
        val response = delegate.screen(head)
        if (enabled && response != null) log(head, response.status, "screened", response)
        return response
    }

    override suspend fun handle(
        head: HttpRequestParser.Head,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val response = delegate.handle(head, body)
        if (enabled) log(head, response.status, "handled", response)
        return response
    }

    private fun log(
        head: HttpRequestParser.Head,
        status: Int,
        stage: String,
        response: HttpResponse,
        /** What went wrong, when the stage is `failed`; the cause goes last so the columns stay greppable. */
        cause: Throwable? = null,
    ) {
        val payload = head.header("x-amz-content-sha256") ?: "-"
        val framing =
            when {
                payload.startsWith("STREAMING-") -> payload

                payload == "UNSIGNED-PAYLOAD" -> payload

                payload == "-" -> "NO-SHA256-HEADER"

                // Told apart on purpose: every GET and every listing carries the empty-body hash,
                // so counting it as a signed payload buries the handful of real ones under it and
                // the count stops meaning anything.
                payload == EMPTY_BODY_SHA256 -> "EMPTY-PAYLOAD"

                else -> "SIGNED-PAYLOAD"
            }
        println(
            "bochka $stage ${head.method} ${head.target} -> $status framing=$framing" +
                locks(head) + answered(response) + (cause?.let { " cause=$it" } ?: ""),
        )
    }

    /**
     * The lock headers of the **answer**, when it carries any.
     *
     * The request side alone left a question the log could not settle: a client complaining that a
     * header is missing and a server that never sent it look identical from here. Prefixed `->` so
     * the two directions cannot be read for one another.
     */
    private fun answered(response: HttpResponse): String {
        val stated =
            response.headers
                .filter { it.first.lowercase() in ANSWERED_LOCK_HEADERS }
                .map { "->${it.first.lowercase().removePrefix("x-amz-")}=${it.second}" }
        return if (stated.isEmpty()) "" else " " + stated.joinToString(" ")
    }

    /**
     * The lock headers, when the request carries any.
     *
     * The same reason the framing is here: they change what a request **means** and are invisible
     * from the outside. A `DELETE` refused with `403` and a `DELETE` refused with `403` after
     * asking to bypass governance are the same line otherwise — and telling them apart is the
     * difference between "the lock works" and "the bypass does not reach this path".
     *
     * Values, not just presence: `legal-hold=OFF` and no legal-hold header at all are different
     * requests, and a bug already lived in exactly that gap.
     *
     * Absent from the line when there are none, so the four framings stay greppable by column.
     */
    private fun locks(head: HttpRequestParser.Head): String {
        val stated =
            LOCK_HEADERS.mapNotNull { name ->
                head
                    .header(name)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "${name.removePrefix("x-amz-")}=$it" }
            }
        return if (stated.isEmpty()) "" else " " + stated.joinToString(" ")
    }

    private companion object {
        /** What the answer says about a lock, which is the other half of the same question. */
        val ANSWERED_LOCK_HEADERS =
            setOf(
                "x-amz-object-lock-mode",
                "x-amz-object-lock-retain-until-date",
                "x-amz-object-lock-legal-hold",
                "x-amz-version-id",
            )

        /** Headers that decide whether a write or a delete is allowed, rather than what it carries. */
        val LOCK_HEADERS =
            listOf(
                "x-amz-bypass-governance-retention",
                "x-amz-object-lock-mode",
                "x-amz-object-lock-retain-until-date",
                "x-amz-object-lock-legal-hold",
                "x-amz-bucket-object-lock-enabled",
            )

        const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
