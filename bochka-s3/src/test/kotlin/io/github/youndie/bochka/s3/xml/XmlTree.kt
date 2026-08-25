package io.github.youndie.bochka.s3.xml

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A document as the client's parser sees it, flattened into something a diff can show.
 *
 * Written because the tests next door used to read these documents as **characters**. A
 * `contains("<Key>a.txt</Key>")` is blind to what the root element declares, to anything the
 * builder emits beside the fragment being matched, and to an element written twice — and the
 * repository has already paid for exactly that: `<PostResponse>` went out with a namespace, the
 * substring test stayed green, and the foreign case failed because a client reads the answer as
 * XML rather than as text.
 *
 * So the comparison is made against a parse. The form is one line per element, deepest value
 * last, siblings of the same name numbered from one:
 *
 * ```
 * ListBucketResult @ http://s3.amazonaws.com/doc/2006-03-01/
 * ListBucketResult/Name = photos
 * ListBucketResult/Contents[1]/Key = a.txt
 * ```
 *
 * The namespace is on the first line rather than implied, because "no namespace" is a **stated**
 * property of `<Error>` and not an absence nobody looked at.
 */
object XmlTree {
    fun canonical(document: ByteArray): String {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Not hardening for its own sake: this has to be the plain parser a client would
                // use, because a document that only parses with entity resolution turned on is a
                // document answering a different question than the one being asked.
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(document)).documentElement
        val lines = ArrayList<String>()
        lines += "${root.localName} @ ${root.namespaceURI ?: "no namespace"}"
        walk(root, root.localName, lines)
        return lines.joinToString("\n")
    }

    private fun walk(
        element: Element,
        path: String,
        lines: MutableList<String>,
    ) {
        for (index in 0 until element.attributes.length) {
            val attribute = element.attributes.item(index)
            if (attribute.nodeName.startsWith("xmlns")) continue
            lines += "$path/@${attribute.nodeName} = ${attribute.nodeValue}"
        }

        val children = element.childNodes
        val elements = (0 until children.length).mapNotNull { children.item(it) as? Element }
        if (elements.isEmpty()) {
            // A leaf, and an empty one is written too: `<Prefix></Prefix>` present and empty is a
            // different document from `<Prefix>` absent, and each is correct somewhere.
            // No dangling space on an empty value: the expected forms live in source, and trailing
            // whitespace there is invisible to a reader and stripped by the formatter.
            val value = text(element)
            lines += if (value.isEmpty()) "$path =" else "$path = $value"
            return
        }

        val counts = elements.groupingBy { it.localName }.eachCount()
        val seen = HashMap<String, Int>()
        for (child in elements) {
            val name = child.localName
            val at = seen.merge(name, 1, Int::plus)!!
            walk(child, if (counts.getValue(name) > 1) "$path/$name[$at]" else "$path/$name", lines)
        }
    }

    private fun text(element: Element): String =
        buildString {
            val children = element.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                    append(child.nodeValue)
                }
            }
        }
}
