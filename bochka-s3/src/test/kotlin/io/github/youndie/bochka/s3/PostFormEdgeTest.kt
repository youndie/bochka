package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The form parser at its own boundaries (M-250).
 *
 * Everything next door sends a form a browser would send. This file sends the ones a browser would
 * not: a delimiter cut off mid-way, a part whose content is empty, content whose own last bytes are
 * a line break, a boundary that is the empty string. Twenty mutations of the index arithmetic in
 * `PostForm` survived the whole suite, and they survived for one reason — every input sat comfortably
 * inside the bounds, so `<` and `<=` decided the same thing every time.
 *
 * This parser reads a body that has not been authenticated: the policy and the signature are inside
 * it. An off-by-one here either stores a byte the uploader did not send or drops one they did, and
 * the object is wrong in a way nothing downstream can notice.
 */
class PostFormEdgeTest {
    @Test
    fun `a boundary has to be a boundary, and an empty one is not`() {
        // `boundary=` with nothing after it is a header a client can produce and a value the parser
        // cannot use: every byte of the body would be a delimiter.
        assertNull(PostForm.boundaryOf("multipart/form-data; boundary="))
        assertNull(PostForm.boundaryOf("multipart/form-data; boundary=\"\""))
        assertNull(PostForm.boundaryOf("multipart/form-data; boundary=   "))

        // The header's own spelling is not the client's to get right: `Content-Type` parameters are
        // case-insensitive, and so is the media type.
        assertEquals("XYZ", PostForm.boundaryOf("MULTIPART/FORM-DATA; BOUNDARY=XYZ"))
        assertEquals("XYZ", PostForm.boundaryOf("multipart/form-data;boundary=XYZ"))
    }

    @Test
    fun `a truncated final delimiter is a refusal rather than a read past the end`() {
        // One byte after the last `--B` instead of two. The check that decides "is this the closing
        // delimiter" reads two bytes, so the bound on it is the difference between a `400` and an
        // index out of the array — and the second one is not an answer, it is a stack trace where a
        // status should be.
        //
        // No `file` part on purpose: `file` ends the parse where it is found, so a form that has
        // one never reaches the truncated delimiter at all. That is the shape the first version of
        // this test had, and it proved nothing.
        val truncated = "--B\r\nContent-Disposition: form-data; name=\"acl\"\r\n\r\nprivate\r\n--B-".toByteArray()

        assertFailsWith<PostForm.Malformed> { PostForm.parse(truncated, "B") }
    }

    @Test
    fun `a body that is only a delimiter has no file in it`() {
        assertFailsWith<PostForm.Malformed> { PostForm.parse("--B--\r\n".toByteArray(), "B") }
        assertFailsWith<PostForm.Malformed> { PostForm.parse("--B".toByteArray(), "B") }
        assertFailsWith<PostForm.Malformed> { PostForm.parse(ByteArray(0), "B") }
    }

    @Test
    fun `an empty file is zero bytes at a real offset, not a missing one`() {
        // The content between the headers and the next delimiter is nothing at all, so the only
        // thing separating them is the delimiter's own line break. Trimming one byte too many here
        // gives a negative length; one too few gives an object with a stray `\n` in it.
        val body = "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n\r\n--B--\r\n".toByteArray()

        val parsed = PostForm.parse(body, "B")

        assertEquals(0, parsed.fileLength)
        assertEquals(String(body).indexOf("\r\n--B--"), parsed.fileOffset)
    }

    @Test
    fun `content that ends in a line break keeps it, and loses only the delimiter's`() {
        // The two are indistinguishable by eye and not by count: the bytes before the delimiter are
        // `a`, CR, LF, CR, LF, and exactly one of those pairs belongs to the form.
        val body = "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\na\r\n\r\n--B--\r\n".toByteArray()

        val parsed = PostForm.parse(body, "B")

        assertEquals(3, parsed.fileLength, "the object is `a`, CR, LF — the other pair is the form's")
        assertEquals("a\r\n", String(body, parsed.fileOffset, parsed.fileLength))
    }

    @Test
    fun `a bare newline after the delimiter is accepted, and a missing one ends the form`() {
        // The carriage return is optional on the way in — a client that writes `\n` alone is
        // producing a form no browser writes and one this parser can still read. What is not
        // optional is that *something* separates the delimiter from the headers.
        val bare = "--B\nContent-Disposition: form-data; name=\"file\"\r\n\r\nxy\r\n--B--\r\n".toByteArray()
        assertEquals("xy", String(bare, PostForm.parse(bare, "B").fileOffset, 2))

        // No line break at all after the opening delimiter: there is nothing to read as a part, and
        // the form ends without one.
        val glued = "--BContent-Disposition: form-data; name=\"file\"\r\n\r\nxy\r\n--B--\r\n".toByteArray()
        assertFailsWith<PostForm.Malformed> { PostForm.parse(glued, "B") }
    }

    @Test
    fun `a disposition without a closing quote names nothing`() {
        // The attribute is found and the value never ends. Reading to the end of the line instead
        // would make the field's name whatever the rest of the header happened to be.
        val body = "--B\r\nContent-Disposition: form-data; name=\"file\r\n\r\nxy\r\n--B--\r\n".toByteArray()

        assertFailsWith<PostForm.Malformed> { PostForm.parse(body, "B") }
    }

    @Test
    fun `an attribute at the very start of the header line is still found`() {
        // `name="file"` with nothing before it: the search for the attribute starts at zero, and a
        // bound that refuses a match there loses the only field that matters.
        val body = "--B\r\nname=\"file\"\r\n\r\nxy\r\n--B--\r\n".toByteArray()

        assertFailsWith<PostForm.Malformed>("a disposition header is what names a part") {
            PostForm.parse(body, "B")
        }
    }

    @Test
    fun `a field with no content is a field with an empty value`() {
        val body =
            (
                "--B\r\nContent-Disposition: form-data; name=\"acl\"\r\n\r\n\r\n" +
                    "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nxy\r\n--B--\r\n"
            ).toByteArray()

        val parsed = PostForm.parse(body, "B")

        assertEquals("", parsed["acl"], "present and empty is not the same as absent")
        assertEquals(2, parsed.fileLength)
    }

    @Test
    fun `the delimiter is found where it starts, including at the first byte`() {
        // The body opens with the delimiter and nothing before it, which is what every real form
        // does — and the search that finds it starts at zero, so the bound on the scan decides
        // whether the first byte can ever match.
        val body = "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nxy\r\n--B--\r\n".toByteArray()

        assertEquals(2, PostForm.parse(body, "B").fileLength)

        // And a delimiter that only *nearly* fits at the end is not one: the last bytes are
        // `--B-`, one short of the closing form and one past the last part.
        val short = "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nxy\r\n--".toByteArray()
        assertFailsWith<PostForm.Malformed> { PostForm.parse(short, "B") }
    }

    @Test
    fun `content that is not even a line break long is zero bytes, not minus one`() {
        // The delimiter follows the headers with nothing at all between them — no content and not
        // even the line break a form normally puts there. The trim that removes that line break has
        // to notice there is nothing to remove: taking two bytes off an empty stretch gives a
        // length of minus one, and the object is not short, it is an exception.
        val body = "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n--B--\r\n".toByteArray()

        val parsed = PostForm.parse(body, "B")

        assertEquals(0, parsed.fileLength)
    }

    @Test
    fun `a part with no disposition header is skipped rather than stored under an empty name`() {
        // A part the parser cannot name is a part it has nothing to do with. Naming it the empty
        // string instead would put it in the field map, where a policy condition would then be
        // checked against a field nobody sent.
        val body =
            (
                "--B\r\nX-Something: else\r\n\r\nignored\r\n" +
                    "--B\r\nContent-Disposition: form-data; name=\"acl\"\r\n\r\nprivate\r\n" +
                    "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nxy\r\n--B--\r\n"
            ).toByteArray()

        val parsed = PostForm.parse(body, "B")

        assertEquals(setOf("acl"), parsed.fields.keys, "only the parts that named themselves are fields")
        assertEquals(2, parsed.fileLength)
    }

    @Test
    fun `a delimiter that ends the body is still found`() {
        // The last bytes of the body are the delimiter itself, with nothing after it — no `--`, no
        // line break. The scan that looks for it has to reach the last position at which it can
        // possibly fit, and one short of that loses the end of the last part.
        val body = "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nxy\r\n--B".toByteArray()

        val parsed = PostForm.parse(body, "B")

        assertEquals(2, parsed.fileLength, "the part ends where the delimiter starts")
        assertEquals("xy", String(body, parsed.fileOffset, parsed.fileLength))
    }

    @Test
    fun `content of a single newline is one byte long, and trimming it stops there`() {
        // The trim takes the line feed and then looks for a carriage return in front of it — and in
        // front of it is the end of the part's headers, not the part. Reading one byte further back
        // takes a byte off the object that was never in it, and the length goes negative.
        val body = "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n\n--B--\r\n".toByteArray()

        val parsed = PostForm.parse(body, "B")

        assertEquals(0, parsed.fileLength, "the lone newline belongs to the delimiter")
    }

    @Test
    fun `a name whose quote never closes is no name at all`() {
        // Not the empty string: an unterminated value is a part the parser could not name, and a
        // part named `""` goes into the field map, where a policy condition would then be evaluated
        // against a field the uploader never sent.
        val body =
            (
                "--B\r\nContent-Disposition: form-data; name=\"acl\r\n\r\nprivate\r\n" +
                    "--B\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nxy\r\n--B--\r\n"
            ).toByteArray()

        val parsed = PostForm.parse(body, "B")

        assertEquals(emptySet(), parsed.fields.keys, "an unnamed part is not a field")
        assertEquals(2, parsed.fileLength)
    }
}
