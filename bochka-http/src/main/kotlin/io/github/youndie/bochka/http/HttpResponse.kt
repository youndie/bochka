package io.github.youndie.bochka.http

import java.nio.charset.StandardCharsets

/**
 * A response with its body already in hand.
 *
 * Enough for everything the server answers except `GET` of an object, which will hand the socket to
 * `transferTo` instead of building a byte array (M-59). That one is deliberately not modelled yet:
 * a "body or file" union invented before the file path exists would be shaped by guesswork.
 */
data class HttpResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = EMPTY,
    /**
     * Set when the connection cannot be reused — a refusal whose body was never read, for
     * instance. Keeping such a connection alive means the next request on it starts in the middle
     * of the last one's body.
     */
    val close: Boolean = false,
) {
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
                append(body.size)
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
