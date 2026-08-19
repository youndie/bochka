package io.github.youndie.bochka.http

import java.nio.charset.StandardCharsets

/**
 * A response, whose body is either bytes already in hand or a stretch of a file.
 *
 * Both cases exist because the two are genuinely different responses. Everything the server says
 * about itself — XML documents, errors — is small and assembled; the body of a `GET` is an object
 * that can be five gigabytes, and reading it into a `ByteArray` to answer would defeat the point of
 * never holding one. The file case is what M-59 hands to `transferTo`.
 */
data class HttpResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = EMPTY,
    /**
     * The body as a stretch of a file, when there is one. Mutually exclusive with [body]; the
     * server writes the head, then the file.
     */
    val file: FileSlice? = null,
    /**
     * Set when the connection cannot be reused — a refusal whose body was never read, for
     * instance. Keeping such a connection alive means the next request on it starts in the middle
     * of the last one's body.
     */
    val close: Boolean = false,
    /**
     * Stated instead of the body's own size, for a response that deliberately does not carry one.
     *
     * `HEAD` is the case: it must announce the length the `GET` would have had. Answering zero is
     * not a cosmetic slip — a client that checks the size after an upload concludes the object is
     * empty and deletes it as a failed transfer, which is exactly what rclone did here while three
     * other clients said everything was fine.
     */
    val contentLength: Long? = null,
) {
    /**
     * A stretch of a file to answer with: the whole object, or the part a `Range` asked for.
     *
     * Held as a path and not an open channel because a response outlives neither — the server opens
     * it when it writes it, and a handler that opened it would have to close it on every path a
     * response can fail on.
     */
    data class FileSlice(
        val path: java.nio.file.Path,
        val offset: Long,
        val length: Long,
        /**
         * A transformation applied to the bytes on their way to the socket, or null for the fast
         * path.
         *
         * Present for exactly one thing: an object encrypted with a customer key (M26). Those
         * bytes cannot go out with `transferTo` — the kernel would send the ciphertext — so they
         * are read into the process, turned back into the object and written. That is the trade
         * SSE-C costs, it is measured (`docs/measurements.md`), and it is stated here rather than
         * discovered: the presence of this field **is** the slow path.
         */
        val through: Filter? = null,
    )

    /**
     * Bytes on their way out, changed in place.
     *
     * In place and not returning a new array, because this sits between the page cache and the
     * socket on every byte of an object: allocating per chunk here is the one place in this server
     * where it would show.
     */
    fun interface Filter {
        fun apply(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        )
    }

    /** Whether the body goes on the wire. `HEAD` answers with the headers of a `GET` and no body. */
    fun render(withBody: Boolean = true): ByteArray {
        val head =
            buildString {
                append("HTTP/1.1 ")
                append(status)
                append(' ')
                append(reason)
                append("\r\n")
                for ((name, value) in headers) {
                    append(name)
                    append(": ")
                    append(value)
                    append("\r\n")
                }
                // Always stated, even at zero: a response without it makes the client wait for the
                // connection to close before it believes the body ended.
                append("Content-Length: ")
                append(contentLength ?: file?.length ?: body.size.toLong())
                append("\r\n")
                if (close) append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)

        if (!withBody || body.isEmpty()) return head
        return head + body
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is HttpResponse &&
                    status == other.status &&
                    reason == other.reason &&
                    headers == other.headers &&
                    close == other.close &&
                    contentLength == other.contentLength &&
                    file == other.file &&
                    body.contentEquals(other.body)
            )

    override fun hashCode(): Int =
        (((status * 31 + reason.hashCode()) * 31 + headers.hashCode()) * 31 + body.contentHashCode()) * 31 +
            close.hashCode()

    companion object {
        private val EMPTY = ByteArray(0)

        /** The interim answer to `Expect: 100-continue`; it has no headers and no body by definition. */
        val CONTINUE: ByteArray = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
    }
}
