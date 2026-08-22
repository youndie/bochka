package io.github.youndie.bochka.http

/**
 * What the server calls once a request head has arrived.
 *
 * The interface has two methods rather than one, and the split is the whole point (research, §1.2).
 * A client sends `Expect: 100-continue` on any file-like body and waits a second for an answer, so
 * the server has to be able to refuse **before** the body exists — and that is only possible if
 * deciding-from-the-head is a separate thing the handler can do.
 *
 * With one `handle(head, body)` method the shape quietly forces the opposite: the handler receives
 * a body it must read to reach the code that would have refused, and a `403` for bad credentials
 * ends up costing the five gigabytes it is refusing.
 */
interface HttpHandler {
    /**
     * Decide from the head alone.
     *
     * Return a response to answer immediately and never read the body — signature failures,
     * unknown buckets, routes that do not exist. Return `null` to accept the body.
     */
    fun screen(head: HttpRequestParser.Head): HttpResponse?

    /** Called with the body once it has been accepted. */
    suspend fun handle(
        head: HttpRequestParser.Head,
        body: RequestBody,
    ): HttpResponse

    /**
     * What to answer when [handle] threw something nobody expected.
     *
     * There has to be an answer. The alternative — letting the exception out and closing the
     * connection — reaches the client as "the server hung up", which is the least actionable
     * failure a server can produce: it is indistinguishable from a network fault, and every SDK
     * retries it. A batch delete of more than a thousand keys arrived here first, and what the
     * suite saw was `ConnectionClosedError`.
     *
     * The handler answers rather than the server because the shape of an error is the protocol's
     * business, and this layer does not know what one looks like.
     */
    fun failed(
        head: HttpRequestParser.Head,
        cause: Throwable,
    ): HttpResponse

    /**
     * A request the server answered **without** this handler: it did not parse (M-229).
     *
     * Every other answer passes through [screen], [handle] or [failed], and the app layer learns
     * about it by wrapping them. A request whose head is malformed never becomes a head at all, so
     * there is nothing to wrap — the server writes the `400` or `431` itself and the client sees
     * it, while the log above says nothing. That is the state where diagnosis becomes guessing.
     *
     * **This tells; it does not answer.** No return value, because the request never became one:
     * there is no route, no bucket, nothing for the protocol layer to shape an error document
     * around, and putting an S3 `<Error>` behind a request that is not yet S3 would be inventing a
     * context. The default does nothing, so a handler that does not care stays as it was.
     */
    fun malformed(
        status: Int,
        cause: Throwable,
    ) {
    }

    /**
     * A connection that died with nobody left to answer (M-229).
     *
     * One connection dying is not the server dying, so this is caught and the loop goes on — but
     * it used to be caught and **dropped**, with a comment saying logging would arrive with the
     * app module. It has arrived; this is the way through. Nothing is written to the client,
     * because by this point there is usually no client left to write to.
     */
    fun abandoned(cause: Throwable) {
    }

    /**
     * The body, handed over as it arrives rather than as one array: an object can be five
     * gigabytes, and the point of the whole design is never to hold one.
     */
    interface RequestBody {
        /**
         * Calls [consume] with each piece as it arrives, until the body ends. The arrays passed in
         * are reused — copy anything kept beyond the call.
         */
        suspend fun forEach(consume: (ByteArray, Int, Int) -> Unit)
    }
}
