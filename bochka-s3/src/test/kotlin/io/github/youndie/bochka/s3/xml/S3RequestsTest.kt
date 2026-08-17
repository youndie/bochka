package io.github.youndie.bochka.s3.xml

import io.github.youndie.bochka.core.ObjectKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shapes: `docs/spec/s3-service-2.json`, `shapes.Delete.members` and
 * `shapes.CompletedMultipartUpload.members`.
 */
class S3RequestsTest {
    @Test
    fun `a batch delete is a flat list of keys`() {
        val body =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <Delete>
              <Object><Key>a.txt</Key></Object>
              <Object><Key>dir/b.txt</Key></Object>
            </Delete>
            """.trimIndent().toByteArray()

        val parsed = S3Requests.parseDelete(body)

        assertEquals(listOf(ObjectKey.of("a.txt"), ObjectKey.of("dir/b.txt")), parsed.keys)
        assertFalse(parsed.quiet)
    }

    @Test
    fun `quiet mode is read and a version id is ignored rather than refused`() {
        // VersionId is a member the client is allowed to send. Versioning is out of scope here, so
        // it is dropped — refusing it would break a client for a feature we simply do not have.
        val body =
            """
            <Delete>
              <Object><Key>a.txt</Key><VersionId>null</VersionId></Object>
              <Quiet>true</Quiet>
            </Delete>
            """.trimIndent().toByteArray()

        val parsed = S3Requests.parseDelete(body)

        assertEquals(listOf(ObjectKey.of("a.txt")), parsed.keys)
        assertTrue(parsed.quiet)
    }

    @Test
    fun `a key keeps the bytes it arrived with`() {
        val body = "<Delete><Object><Key>a&amp;b&lt;c&gt;d</Key></Object></Delete>".toByteArray()

        val key = S3Requests.parseDelete(body).keys.single()

        assertContentEquals("a&b<c>d".toByteArray(), key.toByteArray())
    }

    @Test
    fun `a completion is a list of part numbers and etags with the quotes kept`() {
        val body =
            """
            <CompleteMultipartUpload>
              <Part><PartNumber>1</PartNumber><ETag>"abc"</ETag></Part>
              <Part><PartNumber>2</PartNumber><ETag>"def"</ETag></Part>
            </CompleteMultipartUpload>
            """.trimIndent().toByteArray()

        val parts = S3Requests.parseCompleteMultipartUpload(body)

        assertEquals(
            listOf(
                S3Requests.CompletedPart(1, "\"abc\""),
                S3Requests.CompletedPart(2, "\"def\""),
            ),
            parts,
        )
    }

    @Test
    fun `parts out of order are parsed, not refused`() {
        // Out of order is InvalidPartOrder — a 400 with a code, decided where the upload is
        // completed. Refusing it here would give the client "malformed XML" for a document that is
        // perfectly well-formed.
        val body =
            """
            <CompleteMultipartUpload>
              <Part><PartNumber>2</PartNumber><ETag>"b"</ETag></Part>
              <Part><PartNumber>1</PartNumber><ETag>"a"</ETag></Part>
            </CompleteMultipartUpload>
            """.trimIndent().toByteArray()

        assertEquals(listOf(2, 1), S3Requests.parseCompleteMultipartUpload(body).map { it.partNumber })
    }

    @Test
    fun `a doctype is refused`() {
        // The one thing a hand-rolled reader gets for free: there is no entity resolution to abuse.
        // It is still refused rather than skipped, so that a body carrying one never looks accepted.
        val body =
            """
            <?xml version="1.0"?>
            <!DOCTYPE Delete [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <Delete><Object><Key>&xxe;</Key></Object></Delete>
            """.trimIndent().toByteArray()

        assertFailsWith<XmlReader.MalformedXmlException> { S3Requests.parseDelete(body) }
    }

    @Test
    fun `an unknown entity is refused rather than passed through`() {
        val body = "<Delete><Object><Key>&xxe;</Key></Object></Delete>".toByteArray()
        assertFailsWith<XmlReader.MalformedXmlException> { S3Requests.parseDelete(body) }
    }

    @Test
    fun `the wrong root is refused`() {
        val body = "<Deleted><Object><Key>a</Key></Object></Deleted>".toByteArray()
        assertFailsWith<XmlReader.MalformedXmlException> { S3Requests.parseDelete(body) }
    }

    @Test
    fun `a truncated document is refused`() {
        assertFailsWith<XmlReader.MalformedXmlException> {
            S3Requests.parseDelete("<Delete><Object><Key>a.txt".toByteArray())
        }
        assertFailsWith<XmlReader.MalformedXmlException> {
            S3Requests.parseDelete("<Delete><Object><Key>a.txt</Key></Object>".toByteArray())
        }
    }

    @Test
    fun `a part without a number or an etag is refused`() {
        assertFailsWith<XmlReader.MalformedXmlException> {
            S3Requests.parseCompleteMultipartUpload(
                "<CompleteMultipartUpload><Part><ETag>\"a\"</ETag></Part></CompleteMultipartUpload>".toByteArray(),
            )
        }
        val notANumber =
            (
                "<CompleteMultipartUpload><Part><PartNumber>x</PartNumber><ETag>\"a\"</ETag></Part>" +
                    "</CompleteMultipartUpload>"
            ).toByteArray()

        assertFailsWith<XmlReader.MalformedXmlException> {
            S3Requests.parseCompleteMultipartUpload(notANumber)
        }
    }

    @Test
    fun `a body longer than the protocol allows is refused`() {
        // 1001 objects: the protocol bound is 1000, and "the client would not send more" is not a
        // property of the client (Риск 3).
        val body =
            buildString {
                append("<Delete>")
                repeat(1001) { append("<Object><Key>k$it</Key></Object>") }
                append("</Delete>")
            }.toByteArray()

        assertFailsWith<XmlReader.MalformedXmlException> { S3Requests.parseDelete(body) }
    }

    @Test
    fun `exactly the bound is accepted`() {
        val body =
            buildString {
                append("<Delete>")
                repeat(S3Requests.MAX_DELETE_KEYS) { append("<Object><Key>k$it</Key></Object>") }
                append("</Delete>")
            }.toByteArray()

        assertEquals(S3Requests.MAX_DELETE_KEYS, S3Requests.parseDelete(body).keys.size)
    }
}
