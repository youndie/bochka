package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.S3Error

/**
 * `multipart/form-data` — the fifth form of the input path, and the only one that turns its rule
 * upside down.
 *
 * All the rest of the input is built on refusal being possible **before** the body is read: the
 * signature lives in the headers, and a `403` does not cost five gigabytes (§1.2). In a form the
 * policy and the signature lie **inside the body**, between the fields — authenticating without
 * reading it is impossible in principle. That is the protocol's shape rather than an omission of
 * ours, and there is one defence against it: [LIMIT] before parsing, and `content-length-range`
 * from the policy itself afterwards.
 *
 * The `file` field is last by definition: S3 ignores everything after it and clients rely on that.
 * So the parsing runs in order and stops there.
 */
object PostForm {
    /**
     * The bound on the whole form, the file's contents aside.
     *
     * Twenty kilobytes is a policy, a signature and a dozen fields with room to spare; a form
     * larger than that is either a mistake or an attempt to make the server parse what it never
     * meant to read. Checked **before** parsing, because afterwards is too late.
     */
    const val LIMIT: Int = 20 * 1024

    class Malformed(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    /**
     * A parsed form: the fields before `file`, and the file's own bounds inside the original array.
     *
     * The file's contents are **not copied** — an offset and a length are kept. The form arrives
     * whole in memory (there is no other way to check the signature), and making a second copy of a
     * gigabyte for convenience would double the one place this server is forced to hold a body.
     */
    data class Parsed(
        val fields: Map<String, String>,
        val fileOffset: Int,
        val fileLength: Int,
        val fileName: String?,
    ) {
        operator fun get(name: String): String? = fields[name.lowercase()]
    }

    /** `multipart/form-data; boundary=…` — the boundary from the header; without it there is
     *  nothing to parse. */
    fun boundaryOf(contentType: String?): String? {
        val value = contentType ?: return null
        if (!value.startsWith("multipart/form-data", ignoreCase = true)) return null
        val marker = value.indexOf("boundary=", ignoreCase = true)
        if (marker < 0) return null
        return value
            .substring(marker + "boundary=".length)
            .trim()
            .trim('"')
            .takeIf { it.isNotEmpty() }
    }

    fun parse(
        body: ByteArray,
        boundary: String,
    ): Parsed {
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val fields = LinkedHashMap<String, String>()
        var fileOffset = -1
        var fileLength = 0
        var fileName: String? = null

        var at = indexOf(body, delimiter, 0)
        if (at < 0) throw Malformed(S3Error.MALFORMED_POST_REQUEST, "the body has no form boundary")

        while (at >= 0) {
            var cursor = at + delimiter.size
            // `--` after the boundary is the end of the form. Checked before the line break,
            // because the last boundary may not have one.
            if (cursor + 1 < body.size && body[cursor] == '-'.code.toByte() && body[cursor + 1] == '-'.code.toByte()) {
                break
            }
            cursor = skipEndOfLine(body, cursor) ?: break

            val headerEnd = indexOf(body, DOUBLE_EOL, cursor)
            if (headerEnd < 0) throw Malformed(S3Error.MALFORMED_POST_REQUEST, "a part of the form has no headers")
            val headers = String(body, cursor, headerEnd - cursor, Charsets.ISO_8859_1)
            val contentStart = headerEnd + DOUBLE_EOL.size

            val next = indexOf(body, delimiter, contentStart)
            if (next <
                0
            ) {
                throw Malformed(S3Error.MALFORMED_POST_REQUEST, "a part of the form is not closed by a boundary")
            }
            // A line break precedes the boundary, and it belongs to the delimiter rather than to the
            // content.
            val contentEnd = trimTrailingEndOfLine(body, contentStart, next)

            val name = dispositionValue(headers, "name")?.lowercase()
            if (name == "file") {
                fileOffset = contentStart
                fileLength = contentEnd - contentStart
                fileName = dispositionValue(headers, "filename")
                // S3 ignores everything after `file`, and clients rely on that.
                break
            }
            if (name != null) {
                fields[name] = String(body, contentStart, contentEnd - contentStart, Charsets.UTF_8)
            }
            at = next
        }

        if (fileOffset < 0) throw Malformed(S3Error.MALFORMED_POST_REQUEST, "the form has no file field")
        return Parsed(fields, fileOffset, fileLength, fileName)
    }

    private val DOUBLE_EOL = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

    private fun skipEndOfLine(
        body: ByteArray,
        from: Int,
    ): Int? {
        var i = from
        if (i < body.size && body[i] == '\r'.code.toByte()) i++
        if (i < body.size && body[i] == '\n'.code.toByte()) i++
        return if (i > from) i else null
    }

    private fun trimTrailingEndOfLine(
        body: ByteArray,
        from: Int,
        to: Int,
    ): Int {
        var end = to
        if (end > from && body[end - 1] == '\n'.code.toByte()) end--
        if (end > from && body[end - 1] == '\r'.code.toByte()) end--
        return end
    }

    /** `Content-Disposition: form-data; name="key"; filename="a.txt"` — a value looked up by
     *  name. */
    private fun dispositionValue(
        headers: String,
        attribute: String,
    ): String? {
        for (line in headers.split("\r\n")) {
            if (!line.startsWith("Content-Disposition", ignoreCase = true)) continue
            val marker = line.indexOf("$attribute=\"", ignoreCase = true)
            if (marker < 0) continue
            val start = marker + attribute.length + 2
            val end = line.indexOf('"', start)
            if (end < 0) return null
            // The part's headers were decoded byte-for-byte as Latin-1, which is what keeps the
            // delimiter search honest — but `filename` carries whatever the file was called, and
            // a browser puts it there as raw UTF-8. Round-tripping through the bytes is what turns
            // `Ñ\u0081Ð½Ð¸Ð¼Ð¾Ðº.txt` back into the name the user picked. Without it the key stored is
            // mojibake, and it is mojibake **only** for non-ASCII names — which is how it survives
            // every test written in English.
            return String(line.substring(start, end).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }
        return null
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
        from: Int,
    ): Int {
        if (needle.isEmpty()) return from
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
