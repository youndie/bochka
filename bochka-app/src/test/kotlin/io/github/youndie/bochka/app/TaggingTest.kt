package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bucket and object tags (M-91, M-92).
 *
 * The shapes are in `docs/spec/s3-service-2.json`: `Tagging` (`:13301`) holds `TagSet` (`:13294`),
 * whose element is a `Tag` with required `Key` and `Value` (`:13272`). The `x-amz-tagging` header
 * on an upload is `:3158`.
 *
 * The one place here that holds a decision rather than storage: **a missing set is a `404` with the
 * code `NoSuchTagSet`, not an empty document.** A client reads those two answers differently, and
 * `test_set_bucket_tagging:7148` checks the code specifically.
 */
class TaggingTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private fun tagging(vararg pairs: Pair<String, String>) = tagging(pairs.toList())

    private fun tagging(pairs: List<Pair<String, String>>) =
        pairs
            .joinToString(
                "",
                prefix = "<Tagging><TagSet>",
                postfix = "</TagSet></Tagging>",
            ) { "<Tag><Key>${it.first}</Key><Value>${it.second}</Value></Tag>" }
            .toByteArray()

    @Test
    fun `a bucket with no tags answers NoSuchTagSet rather than an empty set`() {
        s3.createBucket("photos")

        val answer = s3.send("GET", "/photos", query = "tagging")

        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchTagSet")
    }

    @Test
    fun `bucket tags are put, read and removed`() {
        s3.createBucket("photos")

        assertEquals(200, s3.send("PUT", "/photos", query = "tagging", body = tagging("Hello" to "World")).status)

        val read = s3.send("GET", "/photos", query = "tagging")
        assertEquals(200, read.status, read.text)
        assertContains(read.text, "<Key>Hello</Key>")
        assertContains(read.text, "<Value>World</Value>")

        assertEquals(204, s3.send("DELETE", "/photos", query = "tagging").status)
        assertEquals(404, s3.send("GET", "/photos", query = "tagging").status, "a removed set is missing again")
    }

    @Test
    fun `a tag set is replaced whole rather than added to`() {
        // `PutBucketTagging` is not "add a tag": it puts the whole set. Adding would leave a client
        // a way to accumulate tags and no way at all to take them off.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "tagging", body = tagging("a" to "1", "b" to "2"))
        s3.send("PUT", "/photos", query = "tagging", body = tagging("c" to "3"))

        val read = s3.send("GET", "/photos", query = "tagging").text

        assertContains(read, "<Key>c</Key>")
        assertEquals(1, Regex("<Tag>").findAll(read).count(), "exactly one tag should be left: $read")
    }

    @Test
    fun `tags outlive a restart, because they are state of the bucket`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "tagging", body = tagging("Hello" to "World"))

        s3.store.close()
        val reopened =
            io.github.youndie.bochka.core
                .ObjectStore(s3.root)
        val document = reopened.bucketSubresource("photos", "tagging")
        reopened.close()

        assertContains(String(document!!), "<Key>Hello</Key>")
    }

    @Test
    fun `an object with no tags answers with an empty set rather than a 404`() {
        // And this is **not** what a bucket does, though the operation carries the same name. An
        // object may have no tags, but the object itself exists, and S3 answers with an empty
        // `TagSet`; a `404` here would mean the object is missing. Same operation name, different
        // answers — which is why it is checked separately.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "x")

        val answer = s3.send("GET", "/photos/a.txt", query = "tagging")

        assertEquals(200, answer.status, answer.text)
        assertContains(answer.text, "<TagSet")
    }

    @Test
    fun `object tags are put, read and removed`() {
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "x")

        assertEquals(200, s3.send("PUT", "/photos/a.txt", query = "tagging", body = tagging("k" to "v")).status)
        assertContains(s3.send("GET", "/photos/a.txt", query = "tagging").text, "<Key>k</Key>")

        assertEquals(204, s3.send("DELETE", "/photos/a.txt", query = "tagging").status)
        val empty = s3.send("GET", "/photos/a.txt", query = "tagging")
        assertEquals(200, empty.status)
        assertEquals(0, Regex("<Tag>").findAll(empty.text).count())
    }

    @Test
    fun `tags arrive in a header on upload and are counted on a read`() {
        // `x-amz-tagging: a=1&b=2` is the request's form rather than a document's (`:3158`). A
        // client that put the object in one request would otherwise have to make a second.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "x", listOf("x-amz-tagging" to "a=1&b=2"))

        assertContains(s3.send("GET", "/photos/a.txt", query = "tagging").text, "<Key>a</Key>")
        assertEquals("2", s3.get("photos", "a.txt").header("x-amz-tagging-count"))
    }

    @Test
    fun `there are no object tags without an object`() {
        s3.createBucket("photos")

        val answer = s3.send("GET", "/photos/missing.txt", query = "tagging")

        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchKey")
    }

    @Test
    fun `x-amz-tagging with no value is a tag with an empty value rather than a breakage`() {
        // `test_put_obj_with_tags:12281` sends `foo=bar&bar` and expects two tags, the second with
        // an empty value. The form `key` without `=` is legal: a tag's value is optional.
        s3.createBucket("photos")

        val put = s3.put("photos", "a.txt", "body", headers = listOf("x-amz-tagging" to "foo=bar&bar"))

        assertEquals(200, put.status, put.text)
        val read = s3.send("GET", "/photos/a.txt", query = "tagging")
        assertContains(read.text, "<Key>bar</Key><Value></Value>")
        assertContains(read.text, "<Key>foo</Key><Value>bar</Value>")
    }

    @Test
    fun `a malformed x-amz-tagging is an answer rather than a dropped connection`() {
        // A refusal has to be a refusal. `screen` reads the headers before the body, and an
        // exception thrown from there escaped the request loop: the client got a closed socket and
        // went off to diagnose the network. `handle` had that protection from the start; `screen`
        // did not.
        s3.createBucket("photos")

        val put = s3.put("photos", "a.txt", "body", headers = listOf("x-amz-tagging" to "a=%ZZ"))

        assertTrue(put.status in 400..599, "an answer was expected, ${put.status} arrived")
        assertContains(put.text, "<Error>")
    }

    @Test
    fun `eleven tags are InvalidTag, and the object is left with none`() {
        // `test_put_excess_tags:12072`. The code matters more than the status here: `MalformedXML`
        // would send the client looking for a bug in its own serialiser, while the document was
        // faultless — what is wrong is the set it describes.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "body")

        val answer = s3.send("PUT", "/photos/a.txt", query = "tagging", body = tagging((1..11).map { "$it" to "$it" }))

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "InvalidTag")
        // The second half of the case: a refusal that leaves a set behind is not a refusal.
        assertContains(s3.send("GET", "/photos/a.txt", query = "tagging").text, "<TagSet></TagSet>")
    }

    @Test
    fun `key and value lengths are checked on both sides of the bound`() {
        // `test_put_max_kvsize_tags:12087` demands success at 128 and 256;
        // `test_put_excess_key_tags:12108` and `test_put_excess_val_tags:12130` demand a refusal at
        // 129 and 257.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "body")

        fun put(
            key: String,
            value: String,
        ) = s3.send("PUT", "/photos/a.txt", query = "tagging", body = tagging(listOf(key to value)))

        assertEquals(200, put("k".repeat(128), "v".repeat(256)).status)

        val longKey = put("k".repeat(129), "v")
        assertEquals(400, longKey.status, longKey.text)
        assertContains(longKey.text, "InvalidTag")

        val longValue = put("k", "v".repeat(257))
        assertEquals(400, longValue.status, longValue.text)
        assertContains(longValue.text, "InvalidTag")
    }

    @Test
    fun `a set arriving in the header is checked too, and before the body`() {
        // The same bound from the other side: until M-176, eleven tags in `x-amz-tagging` simply
        // became eleven tags on the object, because only the document parser counted them. The
        // refusal is visible from the headers, so it should also cost nothing (§1.2).
        s3.createBucket("photos")

        val answer =
            s3.put(
                "photos",
                "a.txt",
                "body",
                headers = listOf("x-amz-tagging" to (1..11).joinToString("&") { "k$it=v" }),
            )

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "InvalidTag")
        assertEquals(404, s3.get("photos", "a.txt").status, "the object should never have appeared")
    }

    @Test
    fun `tags answer in key order, whichever way they were put`() {
        // A set is unordered and a document is not. The same set, put once as a header and once as
        // a document, has to read back the same.
        s3.createBucket("photos")
        s3.put("photos", "header.txt", "body", headers = listOf("x-amz-tagging" to "foo=1&bar=2"))
        s3.put("photos", "document.txt", "body")
        s3.send("PUT", "/photos/document.txt", query = "tagging", body = tagging(listOf("foo" to "1", "bar" to "2")))

        val fromHeader = s3.send("GET", "/photos/header.txt", query = "tagging").text
        val fromDocument = s3.send("GET", "/photos/document.txt", query = "tagging").text

        assertEquals(fromHeader, fromDocument)
        assertTrue(fromHeader.indexOf("<Key>bar</Key>") < fromHeader.indexOf("<Key>foo</Key>"), fromHeader)
    }
}
