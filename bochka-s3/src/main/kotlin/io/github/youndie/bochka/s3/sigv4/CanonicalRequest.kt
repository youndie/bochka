package io.github.youndie.bochka.s3.sigv4

import io.github.youndie.bochka.s3.UriCodec

/**
 * The canonical request — the string both sides hash before signing.
 *
 * ```
 * <METHOD>\n<CANONICAL_URI>\n<CANONICAL_QUERY>\n<CANONICAL_HEADERS>\n\n<SIGNED_HEADERS>\n<PAYLOAD_HASH>
 * ```
 *
 * `docs/spec/reference/botocore-auth.py:370`.
 *
 * Written once with two path modes rather than only the S3 one, and that is what makes it testable:
 * the 34 official vectors describe **generic** SigV4, and without [PathMode.NORMALISED] there would
 * be nothing to run them against — the only check left would be "it agrees with one server".
 */
object CanonicalRequest {
    /**
     * How the path reaches the canonical request. The single difference between generic SigV4 and
     * S3, and the reason the official `normalize-path/` vectors must give a *different* answer in
     * S3 mode (`botocore-auth.py:538`).
     */
    enum class PathMode {
        /** S3: the path is signed exactly as it travelled. */
        VERBATIM,

        /** Everything else: dot segments removed, then percent-encoded with `/~` left alone. */
        NORMALISED,
    }

    /**
     * A request reduced to what the signature covers.
     *
     * [path] and [query] are **raw**, as they appeared on the request line, and they are *bytes
     * widened to chars* — read the line as ISO-8859-1 and every byte survives as one char. Any
     * place that treats them as text encodes them a second time; the vector `get-utf8` is the one
     * that notices.
     *
     * The path is used as it arrived (in S3 mode). Decoding it and encoding it back would be a
     * different string whenever the client's encoder disagrees with ours by one character — `%7E`
     * against `~` is the usual one — and worse, it would turn a key containing `%2F` into one
     * containing `/`.
     *
     * [headers] keeps arrival order and allows repeats: both matter, because duplicate values are
     * joined in the order they arrived.
     */
    data class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: List<Pair<String, String>>,
    )

    fun build(
        request: Request,
        signedHeaders: List<String>,
        payloadHash: String,
        mode: PathMode,
    ): String =
        buildString {
            append(request.method).append('\n')
            append(canonicalUri(request.path, mode)).append('\n')
            append(canonicalQuery(request.query)).append('\n')
            append(canonicalHeaders(request.headers, signedHeaders)).append('\n')
            append(signedHeaders.joinToString(";")).append('\n')
            append(payloadHash)
        }

    fun canonicalUri(
        path: String,
        mode: PathMode,
    ): String {
        if (path.isEmpty()) return "/"
        return when (mode) {
            PathMode.VERBATIM -> path
            PathMode.NORMALISED -> encodeNormalised(removeDotSegments(path))
        }
    }

    /**
     * Pairs decoded, re-encoded with the unreserved set, sorted by encoded name and then by encoded
     * value; a name with no value gets an empty one (`botocore-auth.py:268`).
     *
     * **Unlike the path, the query is normalised**, and the vectors are what settles it: the wire
     * form in `get-vanilla-utf8-query` carries a raw UTF-8 byte in the query — `/?ሴ=bar` — and
     * the canonical form is `%E1%88%B4=bar`. So the server cannot simply take the token as it
     * arrived. The reference server does the same thing by a different route (`req.Form.Encode()`
     * in `minio/minio`, `cmd/signature-v4.go:386`, then `+` replaced by `%20`).
     *
     * The path must **not** get this treatment, and the difference is not a style choice: decoding
     * and re-encoding a path turns a key containing `%2F` into one containing `/`, which is a
     * different object. That is a real defect in servers that normalise both.
     *
     * `+` decodes to a space here, because in a query it is one; a literal plus arrives as `%2B`.
     */
    fun canonicalQuery(query: String): String {
        if (query.isEmpty()) return ""
        val pairs =
            query
                .split('&')
                .filter { it.isNotEmpty() }
                .map { token ->
                    val eq = token.indexOf('=')
                    val rawName = if (eq < 0) token else token.substring(0, eq)
                    val rawValue = if (eq < 0) "" else token.substring(eq + 1)
                    normalise(rawName) to normalise(rawValue)
                }.sortedWith(compareBy({ it.first }, { it.second }))
        return pairs.joinToString("&") { (name, value) -> "$name=$value" }
    }

    private fun normalise(component: String): String =
        UriCodec.encodeQueryComponent(UriCodec.decode(component, plusIsSpace = true))

    /**
     * `name:value\n` per signed header, names lowercase and sorted, values trimmed with runs of
     * whitespace collapsed (`botocore-auth.py:301`).
     *
     * Repeats are joined with `,` **in arrival order** — not sorted. The vector
     * `get-header-key-duplicate` pins this: three values `value2`, `value2`, `value1` come out as
     * `value2,value2,value1`.
     */
    fun canonicalHeaders(
        headers: List<Pair<String, String>>,
        signedHeaders: List<String>,
    ): String {
        val wanted = signedHeaders.toHashSet()
        val collected = LinkedHashMap<String, MutableList<String>>()
        for ((name, value) in headers) {
            val lower = name.lowercase()
            if (lower !in wanted) continue
            collected.getOrPut(lower) { ArrayList(1) }.add(collapse(value))
        }
        return buildString {
            for (name in signedHeaders) {
                val values = collected[name] ?: continue
                append(name).append(':').append(values.joinToString(",")).append('\n')
            }
        }
    }

    /** Trims the edges and collapses inner runs of whitespace to one space. */
    private fun collapse(value: String): String {
        val out = StringBuilder(value.length)
        var space = false
        for (c in value.trim()) {
            if (c == ' ' || c == '\t') {
                space = true
            } else {
                if (space && out.isNotEmpty()) out.append(' ')
                space = false
                out.append(c)
            }
        }
        return out.toString()
    }

    /** RFC 3986 remove_dot_segments, which is what `normalize_url_path` does (`:385`). */
    private fun removeDotSegments(path: String): String {
        val absolute = path.startsWith("/")
        val trailing = path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..")
        val out = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(segment)
            }
        }
        val body = out.joinToString("/")
        return buildString {
            if (absolute) append('/')
            append(body)
            if (trailing && body.isNotEmpty()) append('/')
        }
    }

    /**
     * `quote(path, safe='/~')` (`botocore-auth.py:385`) — the unreserved set plus `/` and `~`.
     *
     * Note this is generic SigV4 only, so it never runs against a real S3 request; it exists to
     * make the official vectors runnable. Their inputs carry raw bytes in the path — a literal
     * space, a literal UTF-8 character — which a real client would have encoded already.
     */
    private fun encodeNormalised(path: String): String {
        val out = StringBuilder(path.length)
        // ISO-8859-1, not UTF-8. The path is wire bytes widened to chars, so a UTF-8 sequence is
        // already three chars here; encoding it as UTF-8 again turns `/ሴ` into `%C3%A1%C2%88%C2%B4`
        // instead of `%E1%88%B4`. The vector `get-utf8` is what catches it, and it caught it.
        for (b in path.toByteArray(Charsets.ISO_8859_1)) {
            val v = b.toInt() and 0xFF
            val unreserved =
                (v >= 'A'.code && v <= 'Z'.code) ||
                    (v >= 'a'.code && v <= 'z'.code) ||
                    (v >= '0'.code && v <= '9'.code) ||
                    v == '-'.code || v == '_'.code || v == '.'.code || v == '~'.code || v == '/'.code
            if (unreserved) {
                out.append(v.toChar())
            } else {
                out.append('%')
                out.append("0123456789ABCDEF"[v shr 4])
                out.append("0123456789ABCDEF"[v and 0x0F])
            }
        }
        return out.toString()
    }
}
