package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Parsing `multipart/form-data` on recorded bytes, with no socket (Р8).
 *
 * The form is assembled by hand here rather than by a client: the point of the check is that the
 * **bytes are exactly** what a browser sends, `\r\n` in the delimiters included. A library that
 * assembled the form for us would be checking that two of our own representations agree.
 */
class PostFormTest {
    private fun form(
        boundary: String,
        vararg parts: Pair<String, String>,
    ): ByteArray =
        buildString {
            for ((name, value) in parts) {
                append("--$boundary\r\n")
                if (name == "file") {
                    append("Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\n")
                } else {
                    append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                }
                append(value)
                append("\r\n")
            }
            append("--$boundary--\r\n")
        }.toByteArray()

    @Test
    fun `the fields are read and the file's contents are not copied`() {
        val body = form("XYZ", "key" to "foo.txt", "acl" to "private", "file" to "bar")

        val parsed = PostForm.parse(body, "XYZ")

        assertEquals("foo.txt", parsed["key"])
        assertEquals("private", parsed["acl"])
        assertEquals("a.txt", parsed.fileName)
        assertContentEquals(
            "bar".toByteArray(),
            body.copyOfRange(parsed.fileOffset, parsed.fileOffset + parsed.fileLength),
        )
    }

    @Test
    fun `everything after file is ignored, the way S3 does it`() {
        // Clients rely on this: fields after the file are not covered by the signature and carry no
        // meaning.
        val body = form("XYZ", "key" to "foo.txt", "file" to "bar", "ignored" to "мусор")

        val parsed = PostForm.parse(body, "XYZ")

        assertNull(parsed["ignored"])
        assertEquals(3, parsed.fileLength)
    }

    @Test
    fun `field names are case-insensitive`() {
        val body = form("XYZ", "Content-Type" to "text/plain", "file" to "bar")

        assertEquals("text/plain", PostForm.parse(body, "XYZ")["content-type"])
    }

    @Test
    fun `the line break before a boundary belongs to the delimiter rather than to the file`() {
        // The classic off-by-one: the `\r\n` before `--boundary` is part of the delimiter. Handing
        // it over as content means storing an object two bytes longer than the one sent.
        val body = form("XYZ", "file" to "bar")

        assertEquals(3, PostForm.parse(body, "XYZ").fileLength)
    }

    @Test
    fun `an empty file is zero bytes rather than an error`() {
        val body = form("XYZ", "key" to "empty", "file" to "")

        assertEquals(0, PostForm.parse(body, "XYZ").fileLength)
    }

    @Test
    fun `a form without a file is refused`() {
        val body = form("XYZ", "key" to "foo.txt")

        assertFailsWith<PostForm.Malformed> { PostForm.parse(body, "XYZ") }
    }

    @Test
    fun `the boundary comes from the header, and only for multipart`() {
        assertEquals("XYZ", PostForm.boundaryOf("multipart/form-data; boundary=XYZ"))
        assertEquals("XYZ", PostForm.boundaryOf("multipart/form-data; boundary=\"XYZ\""))
        assertNull(PostForm.boundaryOf("application/octet-stream"))
        assertNull(PostForm.boundaryOf("multipart/form-data"))
        assertNull(PostForm.boundaryOf(null))
    }

    @Test
    fun `a filename outside ASCII survives the part headers`() {
        // The part's headers are read byte-for-byte, and `filename` is the one place inside them
        // that carries user text. Read as Latin-1 it becomes mojibake, and since the key of a form
        // upload can be built from it, the object lands under a name nobody asked for.
        val body =
            (
                "--B\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"снимок.txt\"\r\n" +
                    "\r\n" +
                    "тело\r\n" +
                    "--B--\r\n"
            ).toByteArray(Charsets.UTF_8)

        assertEquals("снимок.txt", PostForm.parse(body, "B").fileName)
    }
}
