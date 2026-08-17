package io.github.youndie.bochka.http

/**
 * Reads the head of an HTTP/1.1 request: the request line and the headers, and nothing else.
 *
 * Fed rather than read from, like the body decoder next to it, and for the same reason: on a
 * selector there is no thread to block in a `read()`, and a parser that only works on whole
 * requests only works in tests.
 *
 * **The target is kept exactly as it arrived.** Bytes are widened to chars — read the socket as
 * ISO-8859-1 and every byte survives — because the signature covers the path as it travelled and
 * anything that decodes it first has already changed it (research, §1.13). The same string is
 * handed to the signature layer and to the router.
 *
 * This is an unauthenticated input path — the frames arrive before anything is verified — so what
 * it refuses matters as much as what it accepts. Beyond the size limits, three refusals are here
 * because they are how requests get smuggled past a proxy into a server that is more forgiving than
 * the proxy was:
 *
 * * **`Content-Length` together with `Transfer-Encoding`.** Two answers to "where does this body
 *   end", and a pair of implementations that disagree about which one wins is a request boundary
 *   the front and the back see differently.
 * * **Obsolete line folding.** A header value continued on the next line was deprecated by
 *   RFC 7230 precisely for this. The official signature vectors contain one, which is why the test
 *   fixture there reads it and this does not — a fixture may be generous where a server may not.
 * * **Whitespace before the colon.** `Content-Length : 5` is a header to some parsers and a
 *   malformed line to others.
 */
class HttpRequestParser(
    private val limits: Limits = Limits.DEFAULT,
) {
    data class Limits(
        val requestLineBytes: Int,
        val headerLineBytes: Int,
        val headerCount: Int,
        val headBytes: Int,
    ) {
        companion object {
            /**
             * Eight kilobytes for a line is what the common servers allow, and a presigned URL with
             * a long key plus its query is the honest case that needs room. The head as a whole is
             * bounded separately so that a thousand short headers cannot do what one long one
             * cannot.
             */
            val DEFAULT =
                Limits(
                    requestLineBytes = 8 * 1024,
                    headerLineBytes = 8 * 1024,
                    headerCount = 100,
                    headBytes = 64 * 1024,
                )
        }
    }

    class Malformed(
        val status: Int,
        message: String,
    ) : IllegalArgumentException(message)

    data class Head(
        val method: String,
        val target: String,
        val version: String,
        val headers: List<Pair<String, String>>,
    ) {
        val path: String get() = target.substringBefore('?')

        val query: String get() = target.substringAfter('?', "")

        fun header(name: String): String? {
            val found = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }
            return found?.second
        }

        /**
         * `null` when there is no body and when the length is not stated — the two are told apart
         * by [isChunked], because a chunked body has no length until it ends.
         */
        val contentLength: Long? get() = header("content-length")?.trim()?.toLongOrNull()

        val isChunked: Boolean
            get() = transferEncodings().contains("chunked")

        internal fun transferEncodings(): List<String> {
            val raw = header("transfer-encoding") ?: return emptyList()
            return raw.lowercase().split(',').map { it.trim() }
        }

        /** Whether the client is waiting for `100 Continue` before it sends the body. */
        val expectsContinue: Boolean
            get() {
                val expect = header("expect")?.trim()
                return expect.equals("100-continue", ignoreCase = true)
            }

        /**
         * HTTP/1.1 keeps the connection unless told otherwise; HTTP/1.0 closes it unless told
         * otherwise. Getting this backwards for 1.0 clients leaves sockets in `CLOSE_WAIT` until
         * the process runs out of them.
         */
        val keepAlive: Boolean
            get() {
                val connection = header("connection")?.lowercase()?.trim()
                return if (version == "HTTP/1.0") {
                    connection == "keep-alive"
                } else {
                    connection != "close"
                }
            }
    }

    private val line = StringBuilder(256)
    private val headers = ArrayList<Pair<String, String>>(16)
    private var requestLine: Triple<String, String, String>? = null
    private var consumed = 0
    private var complete = false

    val isComplete: Boolean get() = complete

    var head: Head? = null
        private set

    /**
     * Consumes bytes until the head ends, and returns how many it took. Anything left over is the
     * body and belongs to the caller — the parser never looks at it.
     */
    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ): Int {
        if (complete) return 0
        var at = offset
        val end = offset + length
        while (at < end) {
            val b = bytes[at++]
            consumed++
            if (consumed > limits.headBytes) throw Malformed(431, "request head over ${limits.headBytes} bytes")
            if (b != LF) {
                line.append((b.toInt() and 0xFF).toChar())
                val limit = if (requestLine == null) limits.requestLineBytes else limits.headerLineBytes
                if (line.length > limit) throw Malformed(431, "line over $limit bytes")
                continue
            }
            if (line.isEmpty() || line.last() != CR) throw Malformed(400, "a line must end with CRLF")
            val text = line.substring(0, line.length - 1)
            line.setLength(0)

            if (requestLine == null) {
                requestLine = parseRequestLine(text)
            } else if (text.isEmpty()) {
                finish()
                return at - offset
            } else {
                parseHeaderLine(text)
            }
        }
        return at - offset
    }

    private fun parseRequestLine(text: String): Triple<String, String, String> {
        // Exactly two spaces, and the target may not contain one. A request line with a space in
        // the path is what the signature suite's `get-space` case has, and it is legal there only
        // because that file is a fixture rather than something a client sends.
        val parts = text.split(' ')
        if (parts.size != 3) throw Malformed(400, "request line is not '<method> <target> <version>'")
        val (method, target, version) = parts
        if (method.isEmpty() || method.any { it !in 'A'..'Z' }) throw Malformed(400, "method '$method'")
        if (!target.startsWith('/')) throw Malformed(400, "target '$target' is not an origin-form path")
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw Malformed(505, "version '$version'")
        return Triple(method, target, version)
    }

    private fun parseHeaderLine(text: String) {
        if (text[0] == ' ' || text[0] == '\t') throw Malformed(400, "obsolete line folding is not accepted")
        val colon = text.indexOf(':')
        if (colon <= 0) throw Malformed(400, "header without a name")
        val name = text.substring(0, colon)
        if (name.last() == ' ' || name.last() == '\t') throw Malformed(400, "whitespace before the colon")
        if (name.any { it.code <= 0x20 || it.code >= 0x7F }) throw Malformed(400, "header name '$name'")
        if (headers.size >= limits.headerCount) throw Malformed(431, "over ${limits.headerCount} headers")
        headers.add(name to text.substring(colon + 1).trim())
    }

    private fun finish() {
        val (method, target, version) = requestLine ?: throw Malformed(400, "no request line")

        val lengths = headers.filter { it.first.equals("content-length", ignoreCase = true) }
        if (lengths.map { it.second.trim() }.distinct().size > 1) {
            throw Malformed(400, "conflicting Content-Length headers")
        }
        lengths.firstOrNull()?.let {
            val value = it.second.trim().toLongOrNull()
            if (value == null || value < 0) throw Malformed(400, "Content-Length '${it.second}'")
        }

        val head = Head(method, target, version, headers.toList())
        if (head.isChunked && lengths.isNotEmpty()) {
            throw Malformed(400, "Content-Length together with Transfer-Encoding")
        }
        val encodings = head.transferEncodings()
        if (encodings.isNotEmpty() && encodings.last() != "chunked") {
            throw Malformed(400, "Transfer-Encoding must end in chunked")
        }
        if (head.header("host") == null && version == "HTTP/1.1") {
            // Host is what tells a virtual-hosted request from a path-style one, and it is signed.
            throw Malformed(400, "HTTP/1.1 request without Host")
        }

        this.head = head
        complete = true
    }

    private companion object {
        const val CR = '\r'
        const val LF = '\n'.code.toByte()
    }
}
