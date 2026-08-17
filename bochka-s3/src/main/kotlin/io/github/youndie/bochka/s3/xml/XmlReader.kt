package io.github.youndie.bochka.s3.xml

/**
 * Reads the two request bodies S3 takes as XML: the batch delete and the multipart completion.
 *
 * Hand-rolled, and here that buys something a library would have to be configured out of: this
 * reader has **no** entity resolution, no DOCTYPE and no external references, because none of it is
 * implemented. A `<!DOCTYPE …>` is refused rather than skipped — an XML parser on an unauthenticated
 * request path is one of the few places where "we ignore what we do not understand" is a
 * vulnerability rather than a courtesy.
 *
 * Only the five predefined entities are recognised, and a numeric character reference is not: S3
 * clients do not emit them, and accepting a syntax nobody sends is surface with no purpose.
 *
 * The reader is deliberately dumb about structure — it walks elements and hands out text — because
 * both documents are two levels deep. It is not a general parser and must not become one.
 */
class XmlReader(
    private val text: String,
) {
    private var pos = 0

    class MalformedXmlException(
        message: String,
    ) : IllegalArgumentException(message)

    /**
     * Walks the direct children of the document root, calling [onElement] with each child's name.
     * The handler either consumes the element's content with [textOf] / [children] or ignores it,
     * in which case it is skipped whole — an unknown element is not an error, because S3 request
     * bodies grow members over time and refusing them would break clients that send newer ones.
     */
    fun root(
        expected: String,
        onElement: (String) -> Unit,
    ) {
        skipProlog()
        val name = openTag() ?: throw MalformedXmlException("no root element")
        if (name != expected) throw MalformedXmlException("expected root <$expected>, got <$name>")
        children(onElement)
    }

    fun children(onElement: (String) -> Unit) {
        while (true) {
            skipSpace()
            if (pos >= text.length) throw MalformedXmlException("unexpected end of document")
            if (text.startsWith("</", pos)) {
                closeTag()
                return
            }
            val name = openTag() ?: throw MalformedXmlException("expected an element at $pos")
            val before = pos
            onElement(name)
            // The handler did not consume the element: skip its content and its closing tag.
            if (pos == before) {
                skipElementBody(name)
            }
        }
    }

    /** Text content of the element whose opening tag was just read, and its closing tag. */
    fun textOf(name: String): String {
        val start = pos
        val end = text.indexOf('<', start)
        if (end < 0) throw MalformedXmlException("unterminated <$name>")
        pos = end
        closeTag()
        return unescape(text.substring(start, end))
    }

    private fun skipElementBody(name: String) {
        var open = 1
        while (open > 0) {
            val next = text.indexOf('<', pos)
            if (next < 0) throw MalformedXmlException("unterminated <$name>")
            pos = next
            if (text.startsWith("</", pos)) {
                closeTag()
                open--
            } else {
                if (openTag() == null) throw MalformedXmlException("malformed markup in <$name>")
                open++
            }
        }
    }

    private fun skipProlog() {
        skipSpace()
        if (text.startsWith("<?", pos)) {
            val end = text.indexOf("?>", pos)
            if (end < 0) throw MalformedXmlException("unterminated declaration")
            pos = end + 2
            skipSpace()
        }
        if (text.startsWith("<!", pos)) {
            // Covers <!DOCTYPE and <!-- alike. Both are refused: the first is an attack surface,
            // the second has no business in a request body and allowing it means writing a scanner
            // for it.
            throw MalformedXmlException("declarations and comments are not accepted in a request body")
        }
    }

    /** Reads `<name>`; returns null if what is at [pos] is not an opening tag. */
    private fun openTag(): String? {
        if (pos >= text.length || text[pos] != '<' || text.startsWith("</", pos)) return null
        val end = text.indexOf('>', pos)
        if (end < 0) throw MalformedXmlException("unterminated tag at $pos")
        val raw = text.substring(pos + 1, end)
        if (raw.endsWith("/")) throw MalformedXmlException("self-closing elements are not accepted")
        // Attributes are not read: no element in either document carries one that means anything,
        // and silently dropping an attribute we do not understand is better than pretending to
        // support it. The name is everything up to the first space.
        val name = raw.substringBefore(' ')
        if (name.isEmpty()) throw MalformedXmlException("empty element name at $pos")
        pos = end + 1
        return name
    }

    private fun closeTag() {
        val end = text.indexOf('>', pos)
        if (end < 0) throw MalformedXmlException("unterminated closing tag at $pos")
        pos = end + 1
    }

    private fun skipSpace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun unescape(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = raw.indexOf(';', i)
            if (semi < 0) throw MalformedXmlException("unterminated entity at $i")
            when (val entity = raw.substring(i + 1, semi)) {
                "amp" -> out.append('&')
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                else -> throw MalformedXmlException("unknown entity &$entity;")
            }
            i = semi + 1
        }
        return out.toString()
    }
}
