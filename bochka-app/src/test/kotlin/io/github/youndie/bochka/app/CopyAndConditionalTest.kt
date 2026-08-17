package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `CopyObject` and the conditional headers of a read.
 *
 * Both came out of running the compatibility suite rather than out of the backlog, which is what
 * an acceptance milestone is for: copying is eighty-four of its cases and the operation was not
 * written down anywhere, and the conditional headers are twenty more.
 */
class CopyAndConditionalTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private fun bucket(): String {
        s3.createBucket("photos")
        return "photos"
    }

    private fun copy(
        from: String,
        to: String,
        headers: List<Pair<String, String>> = emptyList(),
    ): S3Fixture.Answer = s3.send("PUT", "/photos/$to", headers = headers + ("x-amz-copy-source" to "/photos/$from"))

    @Test
    fun `an object is copied inside the server`() {
        bucket()
        s3.put("photos", "source.txt", "the content", headers = listOf("Content-Type" to "text/plain"))

        val copied = copy("source.txt", "target.txt")
        assertEquals(200, copied.status, copied.text)
        assertContains(copied.text, "<CopyObjectResult")
        assertContains(copied.text, "<ETag>")

        val got = s3.get("photos", "target.txt")
        assertEquals("the content", got.text)
        assertEquals(s3.get("photos", "source.txt").header("ETag"), got.header("ETag"))
        assertEquals("text/plain", got.header("Content-Type"), "COPY is the default directive")
    }

    @Test
    fun `the source keeps its own bytes`() {
        bucket()
        s3.put("photos", "source.txt", "original")
        copy("source.txt", "target.txt")
        s3.put("photos", "target.txt", "replaced")

        assertEquals("original", s3.get("photos", "source.txt").text, "the two are separate objects")
    }

    @Test
    fun `REPLACE takes the metadata off the request`() {
        bucket()
        s3.put("photos", "source.txt", "x", headers = listOf("x-amz-meta-who" to "source"))

        val copied =
            copy(
                "source.txt",
                "target.txt",
                headers = listOf("x-amz-metadata-directive" to "REPLACE", "x-amz-meta-who" to "target"),
            )
        assertEquals(200, copied.status)
        assertEquals("target", s3.get("photos", "target.txt").header("x-amz-meta-who"))
    }

    @Test
    fun `copying an object onto itself without REPLACE is refused`() {
        // Reads as pedantry and is not: the request asks for nothing, and a client that meant to
        // rewrite the metadata and forgot the directive would otherwise be told it worked.
        bucket()
        s3.put("photos", "a.txt", "x", headers = listOf("x-amz-meta-who" to "before"))

        val refused = copy("a.txt", "a.txt")
        assertEquals(400, refused.status)
        assertContains(refused.text, "InvalidRequest")

        val rewritten =
            copy(
                "a.txt",
                "a.txt",
                headers = listOf("x-amz-metadata-directive" to "REPLACE", "x-amz-meta-who" to "after"),
            )
        assertEquals(200, rewritten.status)
        assertEquals("after", s3.get("photos", "a.txt").header("x-amz-meta-who"))
    }

    @Test
    fun `copying from what is not there says which half is missing`() {
        bucket()
        assertContains(copy("absent.txt", "target.txt").text, "NoSuchKey")

        val fromNowhere =
            s3.send("PUT", "/photos/target.txt", headers = listOf("x-amz-copy-source" to "/absent-bucket/a.txt"))
        assertEquals(404, fromNowhere.status)
        assertContains(fromNowhere.text, "NoSuchBucket")
    }

    @Test
    fun `a copy of a key with a slash in it lands under the whole key`() {
        // The source header is `/<bucket>/<key>`, and everything after the first slash is the key.
        // Splitting on every slash would put `a/b/c.txt` in a bucket called `a`.
        bucket()
        s3.put("photos", "a/b/c.txt", "nested")
        val copied = s3.send("PUT", "/photos/copy.txt", headers = listOf("x-amz-copy-source" to "/photos/a/b/c.txt"))
        assertEquals(200, copied.status, copied.text)
        assertEquals("nested", s3.get("photos", "copy.txt").text)
    }

    @Test
    fun `If-None-Match on a tag the client already has is 304`() {
        bucket()
        s3.put("photos", "a.txt", "hello")
        val eTag = s3.get("photos", "a.txt").header("ETag")!!

        val cached = s3.get("photos", "a.txt", headers = listOf("If-None-Match" to eTag))
        assertEquals(304, cached.status)
        assertEquals(0, cached.body.size)

        assertEquals(200, s3.get("photos", "a.txt", headers = listOf("If-None-Match" to "\"other\"")).status)
    }

    @Test
    fun `If-Match on a tag that is gone is 412`() {
        bucket()
        s3.put("photos", "a.txt", "hello")
        val eTag = s3.get("photos", "a.txt").header("ETag")!!

        assertEquals(200, s3.get("photos", "a.txt", headers = listOf("If-Match" to eTag)).status)
        assertEquals(412, s3.get("photos", "a.txt", headers = listOf("If-Match" to "\"stale\"")).status)
        assertEquals(200, s3.get("photos", "a.txt", headers = listOf("If-Match" to "*")).status)
    }

    @Test
    fun `the date conditions compare in whole seconds`() {
        // `Last-Modified` has no sub-second precision, so an object written in the same second as
        // the client's copy must not read as newer than it.
        bucket()
        s3.put("photos", "a.txt", "hello")
        val lastModified = s3.get("photos", "a.txt").header("Last-Modified")!!

        assertEquals(304, s3.get("photos", "a.txt", headers = listOf("If-Modified-Since" to lastModified)).status)
        assertEquals(200, s3.get("photos", "a.txt", headers = listOf("If-Unmodified-Since" to lastModified)).status)

        val longAgo = "Thu, 01 Jan 2015 00:00:00 GMT"
        assertEquals(200, s3.get("photos", "a.txt", headers = listOf("If-Modified-Since" to longAgo)).status)
        assertEquals(412, s3.get("photos", "a.txt", headers = listOf("If-Unmodified-Since" to longAgo)).status)
    }

    @Test
    fun `a request with no credentials is refused as nobody's, not as broken`() {
        // 403 and not 400: the request is well-formed, it just carries no identity. Telling a
        // client its request was malformed says that credentials would not have helped.
        bucket()
        val anonymous =
            java.net.http.HttpClient
                .newHttpClient()
                .send(
                    java.net.http.HttpRequest
                        .newBuilder(java.net.URI.create("http://127.0.0.1:${s3.port}/photos/a.txt"))
                        .GET()
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers
                        .ofString(),
                )
        assertEquals(403, anonymous.statusCode())
        assertContains(anonymous.body(), "AccessDenied")
        assertNotEquals(400, anonymous.statusCode())
    }
}
