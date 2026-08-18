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
        // Always printed, whatever the log flag says: this is the server admitting a bug, and it
        // is the one line whose absence would leave nothing to look at.
        println("bochka failed ${head.method} ${head.path}: $cause")
        return delegate.failed(head, cause)
    }

    override fun screen(head: HttpRequestParser.Head): HttpResponse? {
        val response = delegate.screen(head)
        if (enabled && response != null) log(head, response.status, "screened")
        return response
    }

    override suspend fun handle(
        head: HttpRequestParser.Head,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val response = delegate.handle(head, body)
        if (enabled) log(head, response.status, "handled")
        return response
    }

    private fun log(
        head: HttpRequestParser.Head,
        status: Int,
        stage: String,
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
        println("bochka $stage ${head.method} ${head.target} -> $status framing=$framing${locks(head)}")
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
        /** Headers that decide whether a write or a delete is allowed, rather than what it carries. */
        val LOCK_HEADERS =
            listOf(
                "x-amz-bypass-governance-retention",
                "x-amz-object-lock-mode",
                "x-amz-object-lock-retain-until-date",
                "x-amz-object-lock-legal-hold-status",
                "x-amz-bucket-object-lock-enabled",
            )

        const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
