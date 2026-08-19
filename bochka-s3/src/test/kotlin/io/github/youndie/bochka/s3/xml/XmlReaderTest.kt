package io.github.youndie.bochka.s3.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The reader's own rules, as opposed to any one document's.
 *
 * Two of them are refusals and stay refusals — a `<!DOCTYPE>` and an unknown entity — and one used
 * to be a refusal and is not: an element written `<Filter/>`.
 */
class XmlReaderTest {
    @Test
    fun `an empty element is a member with no content, not a malformed document`() {
        // Verbatim from botocore's own serializer, which is where this was measured rather than
        // guessed: `client.put_bucket_lifecycle_configuration(..., Filter={})` puts `<Filter />`
        // on the wire, and `Prefix: ''` puts `<Prefix />`. Both are the standard client writing
        // the only form it has for "an empty structure" and "an empty string".
        val body =
            "<LifecycleConfiguration><Rule><ID>rule1</ID><Filter /><Status>Enabled</Status></Rule>" +
                "</LifecycleConfiguration>"

        var id: String? = null
        var status: String? = null
        var sawFilter = false
        var filterChildren = 0
        val reader = XmlReader(body)
        reader.root("LifecycleConfiguration") { _ ->
            reader.children { field ->
                when (field) {
                    "ID" -> {
                        id = reader.textOf(field)
                    }

                    "Status" -> {
                        status = reader.textOf(field)
                    }

                    "Filter" -> {
                        sawFilter = true
                        reader.children { filterChildren++ }
                    }
                }
            }
        }

        // The element was reported, it had nothing in it, and everything after it was still read:
        // the earlier refusal lost the whole document over a member that carries no information.
        assertEquals("rule1", id)
        assertEquals("Enabled", status)
        assertEquals(true, sawFilter)
        assertEquals(0, filterChildren)
    }

    @Test
    fun `an empty element read as text is the empty string`() {
        val reader = XmlReader("<Filter><Prefix /><ObjectSizeGreaterThan>2000</ObjectSizeGreaterThan></Filter>")
        var prefix: String? = null
        var size: String? = null
        reader.root("Filter") { field ->
            when (field) {
                "Prefix" -> prefix = reader.textOf(field)
                "ObjectSizeGreaterThan" -> size = reader.textOf(field)
            }
        }

        // Not null and not absent: the empty prefix is a prefix, and it matches every key.
        assertEquals("", prefix)
        assertEquals("2000", size)
    }

    @Test
    fun `an empty element the handler ignores is stepped over rather than descended into`() {
        // The other half of the same flag: nobody reads `<Filter/>` here, so the walk has to drop
        // it by itself. Counting it as an opening tag would leave the walk one closing tag short
        // and swallow `<Status>` looking for it.
        val reader = XmlReader("<Rule><Filter /><Status>Enabled</Status></Rule>")
        var status: String? = null
        reader.root("Rule") { field ->
            if (field == "Status") status = reader.textOf(field)
        }

        assertEquals("Enabled", status)
    }

    @Test
    fun `a doctype is still refused`() {
        // Unchanged and deliberately so: an empty element carries no information, a DOCTYPE is an
        // instruction to go and fetch something.
        assertFailsWith<XmlReader.MalformedXmlException> {
            XmlReader("<!DOCTYPE x><Delete></Delete>").root("Delete") { }
        }
    }
}
