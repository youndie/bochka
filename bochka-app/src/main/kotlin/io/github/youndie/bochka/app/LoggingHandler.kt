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
        println("bochka $stage ${head.method} ${head.target} -> $status framing=$framing")
    }

    private companion object {
        const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
