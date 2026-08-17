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
