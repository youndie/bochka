package io.github.youndie.bochka.s3.sigv4

/**
 * Reads the raw HTTP requests the official vector suite is written in (`<case>.req`).
 *
 * A test fixture, not a server: it exists because the vectors are raw requests and there is no
 * parser yet to read them with. It deliberately does two things a real HTTP parser would refuse:
 *
 * * **a space inside the request target.** `normalize-path/get-space` is `GET /example space/
 *   HTTP/1.1`, and this is precisely why botocore's own runner skips that case — "a general HTTP
 *   parser cannot read a request line containing a space". Splitting on the first and last space
 *   instead of on every space reads it fine, and the case is worth having: it is the one that
 *   pins percent-encoding of the path;
 * * **obsolete line folding.** A header value continued on the next line is joined with a space.
 *   Real HTTP/1.1 deprecated this and the server will reject it (M-22) — but the suite has
 *   `get-header-value-multiline`, and refusing it here would mean dropping a vector because of a
 *   decision about a different layer.
 *
 * The line ending in these files is `\n`, not CRLF.
 */
object RawHttpRequest {
    data class Parsed(
        val method: String,
        val path: String,
        val query: String,
        val headers: List<Pair<String, String>>,
        val body: String,
    )

    fun parse(raw: String): Parsed {
        val lines = raw.split("\n")
        val requestLine = lines.first()

        val firstSpace = requestLine.indexOf(' ')
        val lastSpace = requestLine.lastIndexOf(' ')
        require(firstSpace > 0 && lastSpace > firstSpace) { "malformed request line: '$requestLine'" }
        val method = requestLine.substring(0, firstSpace)
        val target = requestLine.substring(firstSpace + 1, lastSpace)

        val headers = ArrayList<Pair<String, String>>()
        var i = 1
        while (i < lines.size && lines[i].isNotEmpty()) {
            val line = lines[i]
            if (line.startsWith(" ") || line.startsWith("\t")) {
                val (name, value) = headers.removeAt(headers.size - 1)
                headers.add(name to value + " " + line.trim())
            } else {
                val colon = line.indexOf(':')
                require(colon > 0) { "malformed header: '$line'" }
                headers.add(line.substring(0, colon) to line.substring(colon + 1))
            }
            i++
        }

        val body = if (i < lines.size) lines.drop(i + 1).joinToString("\n") else ""
        val question = target.indexOf('?')
        val path = if (question < 0) target else target.substring(0, question)
        val query = if (question < 0) "" else target.substring(question + 1)

        return Parsed(method, path, query, headers, body)
    }
}
