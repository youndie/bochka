package io.github.youndie.bochka.embedded

import io.github.youndie.bochka.http.HttpHandler
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.http.HttpResponse
import io.github.youndie.bochka.s3.sigv4.S3Error
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Refusals to order — the one thing a mock is structurally better at than a real server.
 *
 * A real store cannot be asked to answer `503`: it answers the truth. And client code nobody can
 * knock over is **untested on retries** — which is learned in production, the first time a retry is
 * actually needed. A mock can do this from birth, and it is its one genuine advantage; here it is
 * taken and nothing is given up for it.
 *
 * A wrapper around the handler rather than a change to the server: the request travels the same
 * path it travels in production, and a disabled wrapper is one field read. The refusal is injected
 * **before** parsing and the signature, because a client checking its retries usually checks them
 * on a five-hundred rather than on a refusal of access.
 */
internal class InjectedFailures(
    private val next: HttpHandler,
) : HttpHandler {
    private val remaining = AtomicInteger(0)
    private val status = AtomicReference(503)

    /**
     * Answer [status] to the next [times] requests, whatever they are.
     *
     * A status this wrapper cannot name an error code for is **refused** (M-231). A client branches
     * on the code rather than on the number, and an ordered `403` that arrives as `InternalError`
     * turns a test about a refusal of access into a test about a server failure — a green one at
     * that.
     */
    fun failNext(
        status: Int,
        times: Int,
    ) {
        require(status in INJECTABLE) {
            "no S3 error code follows from status $status alone; injectable statuses are " +
                INJECTABLE.keys.sorted().joinToString(", ")
        }
        this.status.set(status)
        remaining.set(times)
    }

    fun clear() = remaining.set(0)

    override fun screen(head: HttpRequestParser.Head): HttpResponse? {
        // `getAndUpdate` rather than "read and decrement": otherwise two simultaneous requests both
        // see a one, and a single ordered refusal happens twice. A test that drifts like that is
        // worse than no test.
        val before = remaining.getAndUpdate { if (it > 0) it - 1 else 0 }
        if (before <= 0) return next.screen(head)

        val code = status.get()
        return HttpResponse(
            code,
            reasonFor(code),
            headers = listOf("Content-Type" to "application/xml"),
            body = document(code),
            contentLength = document(code).size.toLong(),
        )
    }

    override suspend fun handle(
        head: HttpRequestParser.Head,
        body: HttpHandler.RequestBody,
    ): HttpResponse = next.handle(head, body)

    override fun failed(
        head: HttpRequestParser.Head,
        cause: Throwable,
    ): HttpResponse = next.failed(head, cause)

    private fun reasonFor(code: Int) = INJECTABLE.getValue(code).reason

    /**
     * An error body of the real shape rather than an empty answer.
     *
     * A client checking its retries parses the answer — and on an empty body it fails inside its
     * own parser instead of retrying. That already happened here with a `412` and no body: an error
     * status without an error document is a different breakage rather than a shorter one.
     *
     * The body is the only place the code is visible on the wire, and therefore the only thing it
     * can be checked against at all: a `HEAD` has no body, and there the client names the code
     * **itself**, from the status.
     */
    private fun document(code: Int): ByteArray =
        (
            """<?xml version="1.0" encoding="UTF-8"?><Error><Code>""" +
                INJECTABLE.getValue(code).code +
                "</Code><Message>injected by the test</Message><Resource></Resource>" +
                "<RequestId>injected</RequestId><HostId></HostId></Error>"
        ).toByteArray()

    private class Injected(
        val code: String,
        val reason: String,
    )

    companion object {
        /**
         * The statuses that can be ordered, and the error code for each.
         *
         * The list is short by design rather than by omission: the refusal is injected **before** the
         * request is parsed, so the wrapper does not know whether it was about a bucket or a key.
         * For the statuses whose code does not follow from the status — `404` (`NoSuchBucket`,
         * `NoSuchKey`, `NoSuchUpload`) and `409` (`BucketAlreadyExists`, `OperationAborted`) — the
         * choice would be an invention, and a test telling those cases apart would be written
         * against an invented answer. A refusal like that is asked of the server by setting up
         * state, not of the double.
         *
         * The names come from [S3Error] wherever they exist there: a code has one home, and a
         * rename in the server does not leave the double answering with the old name.
         */
        private val INJECTABLE =
            mapOf(
                400 to Injected(S3Error.INVALID_REQUEST.code, "Bad Request"),
                403 to Injected(S3Error.ACCESS_DENIED.code, "Forbidden"),
                405 to Injected(S3Error.METHOD_NOT_ALLOWED.code, "Method Not Allowed"),
                // `408`, `429`, `502`, `503` and `504` are the ones a client retries by itself,
                // which is exactly what a primed refusal is set up for. `502` and `504` have no
                // code of their own: those are answered not by S3 but by whatever stands in front
                // of it — but a client that treats them as a failure treats them so here too.
                408 to Injected("RequestTimeout", "Request Timeout"),
                412 to Injected(S3Error.PRECONDITION_FAILED.code, "Precondition Failed"),
                429 to Injected("SlowDown", "Too Many Requests"),
                500 to Injected(S3Error.INTERNAL_ERROR.code, "Internal Server Error"),
                501 to Injected(S3Error.NOT_IMPLEMENTED.code, "Not Implemented"),
                502 to Injected(S3Error.INTERNAL_ERROR.code, "Bad Gateway"),
                503 to Injected("ServiceUnavailable", "Service Unavailable"),
                504 to Injected(S3Error.INTERNAL_ERROR.code, "Gateway Timeout"),
                507 to Injected(S3Error.INSUFFICIENT_STORAGE.code, "Insufficient Storage"),
            )
    }
}
