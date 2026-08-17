package io.github.youndie.bochka.s3.xml

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Writes the handful of flat documents S3 answers with.
 *
 * Hand-rolled, for the same reason the client next door hand-rolled its reader: the shapes are few
 * and flat, and a library would be the only dependency in the module. The bodies are small — a page
 * of a listing is a thousand entries — so there is no streaming.
 *
 * **It writes bytes, not a `String`, and that is forced rather than chosen.** An object key is a
 * byte string that need not be valid UTF-8 (Р3), so a writer that took `String` would decode it on
 * the way in and hand the client a *different key* — with replacement characters where the original
 * bytes were. Everything else here is ASCII, so the two forms cost the same.
 *
 * Element names and the namespace are not invented: they come from the reference server's own
 * response structs (`minio/minio`, `cmd/api-response.go`), which is the only place they are written
 * down formally — the machine model names the *members* of each shape but not the root element.
 */
class XmlWriter(
    expectedSize: Int = 256,
) {
    private val out = ByteArrayOutputStream(expectedSize)

    fun document(
        root: String,
        namespace: String? = S3_NAMESPACE,
        body: XmlWriter.() -> Unit,
    ): ByteArray {
        ascii(DECLARATION)
        ascii("<")
        ascii(root)
        if (namespace != null) {
            ascii(" xmlns=\"")
            ascii(namespace)
            ascii("\"")
        }
        ascii(">")
        body()
        ascii("</")
        ascii(root)
        ascii(">")
        return out.toByteArray()
    }

    fun element(
        name: String,
        body: XmlWriter.() -> Unit,
    ) {
        ascii("<")
        ascii(name)
        ascii(">")
        body()
        ascii("</")
        ascii(name)
        ascii(">")
    }

    /** Writes nothing when [value] is null — S3 leaves absent members out rather than empty. */
    fun text(
        name: String,
        value: String?,
    ) {
        if (value == null) return
        raw(name, value.toByteArray(StandardCharsets.UTF_8))
    }

    fun text(
        name: String,
        value: Long,
    ) = raw(name, value.toString().toByteArray(StandardCharsets.US_ASCII))

    fun text(
        name: String,
        value: Boolean,
    ) = raw(name, value.toString().toByteArray(StandardCharsets.US_ASCII))

    /**
     * The one that matters: bytes go out as they came in, with only the five entities escaped.
     *
     * Not escaped, on purpose: the control bytes a key may legally contain. "An object key can
     * contain any Unicode character. However, the XML 1.0 parser can't parse certain characters,
     * such as characters with an ASCII value from 0 to 10" — and the model's answer to that is not
     * for the server to mangle the key, it is `encoding-type=url`
     * (`docs/spec/s3-service-2.json`, `shapes.EncodingType`). A caller who lists such keys without
     * asking for encoding gets a document their parser refuses, exactly as they would from S3.
     * Substituting bytes here would hand them a wrong key instead of a broken document — quieter,
     * and much worse.
     */
    fun raw(
        name: String,
        value: ByteArray,
    ) {
        ascii("<")
        ascii(name)
        ascii(">")
        // The five entities are ASCII, and no byte of a multi-byte UTF-8 sequence is ASCII, so
        // escaping byte by byte is safe for text that happens to be UTF-8 and harmless for text
        // that is not.
        for (b in value) {
            when (b) {
                AMP -> ascii("&amp;")
                LT -> ascii("&lt;")
                GT -> ascii("&gt;")
                QUOT -> ascii("&quot;")
                APOS -> ascii("&apos;")
                else -> out.write(b.toInt())
            }
        }
        ascii("</")
        ascii(name)
        ascii(">")
    }

    private fun ascii(s: String) {
        for (c in s) out.write(c.code)
    }

    companion object {
        const val DECLARATION: String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"

        /** `cmd/api-response.go`: every result element carries it. `<Error>` is the exception. */
        const val S3_NAMESPACE: String = "http://s3.amazonaws.com/doc/2006-03-01/"

        private const val AMP = '&'.code.toByte()
        private const val LT = '<'.code.toByte()
        private const val GT = '>'.code.toByte()
        private const val QUOT = '"'.code.toByte()
        private const val APOS = '\''.code.toByte()
    }
}
