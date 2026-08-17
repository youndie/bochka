package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ListObjectsV2` and `ListObjects` on the wire (M-48…M-53).
 *
 * The walk itself is checked in `ObjectStoreListingTest`; what is here is the document — which
 * fields each version carries, and what a client can round-trip back as the next page.
 */
class ListingTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private fun bucket(vararg keys: String): String {
        s3.createBucket("photos")
        for (key in keys) s3.put("photos", key, key)
        return "photos"
    }

    private fun listV2(query: String = ""): S3Fixture.Answer =
        s3.send("GET", "/photos", query = "list-type=2" + if (query.isEmpty()) "" else "&$query")

    private fun keysOf(body: String): List<String> =
        Regex("<Key>(.*?)</Key>")
            .findAll(body)
            .map {
                it.groupValues[1]
            }.toList()

    private fun prefixesOf(body: String): List<String> =
        Regex("<CommonPrefixes><Prefix>(.*?)</Prefix></CommonPrefixes>")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()

    private fun field(
        body: String,
        name: String,
    ): String? = Regex("<$name>(.*?)</$name>").find(body)?.groupValues?.get(1)

    @Test
    fun `a listing names what is in the bucket, in order`() {
        bucket("b.txt", "a.txt", "c.txt")
        val body = listV2().text

        assertEquals(listOf("a.txt", "b.txt", "c.txt"), keysOf(body))
        assertEquals("3", field(body, "KeyCount"))
        assertEquals("1000", field(body, "MaxKeys"))
        assertEquals("false", field(body, "IsTruncated"))
        // Prefix is present and empty when it was not asked for — clients read it back.
        assertContains(body, "<Prefix></Prefix>")
    }

    @Test
    fun `a delimiter rolls a group into one entry`() {
        bucket("asdf", "boo/bar", "boo/baz/xyzzy", "cquux/thud")
        val body = listV2("delimiter=%2F").text

        assertEquals(listOf("asdf"), keysOf(body))
        assertEquals(listOf("boo/", "cquux/"), prefixesOf(body))
        // Three entries on the page: one key and two rolled-up prefixes.
        assertEquals("3", field(body, "KeyCount"))
        assertEquals("/", field(body, "Delimiter"))
    }

    @Test
    fun `a client can walk the whole bucket through the tokens it is given`() {
        val keys = (0 until 25).map { "k%02d".format(it) }
        bucket(*keys.toTypedArray())

        val seen = mutableListOf<String>()
        var token: String? = null
        var pages = 0
        while (true) {
            val body = listV2("max-keys=7" + (token?.let { "&continuation-token=$it" } ?: "")).text
            seen += keysOf(body)
            pages++
            if (field(body, "IsTruncated") != "true") break
            token =
                java.net.URLEncoder.encode(
                    field(body, "NextContinuationToken") ?: error("truncated with no token: $body"),
                    "UTF-8",
                )
        }

        assertEquals(4, pages)
        assertEquals(keys, seen, "the pages have to join up with no gap and no repeat")
    }

    @Test
    fun `the token this server issues is opaque and comes back verbatim`() {
        bucket("a", "b", "c")
        val first = listV2("max-keys=1").text
        val token = field(first, "NextContinuationToken")!!
        assertFalse(token.contains("a"), "a token that is the key in plain sight invites clients to build one")

        val second = listV2("max-keys=1&continuation-token=${java.net.URLEncoder.encode(token, "UTF-8")}").text
        assertEquals(listOf("b"), keysOf(second))
        // Echoed back, so a client can tell which page it is holding.
        assertEquals(token, field(second, "ContinuationToken"))
    }

    @Test
    fun `start-after skips what is at or before it`() {
        bucket("a", "b", "c")
        val body = listV2("start-after=b").text
        assertEquals(listOf("c"), keysOf(body))
        assertEquals("b", field(body, "StartAfter"))
    }

    @Test
    fun `the first version carries a marker and the second does not`() {
        bucket("a", "b", "c")

        val v1 = s3.send("GET", "/photos", query = "max-keys=2").text
        assertEquals(listOf("a", "b"), keysOf(v1))
        assertEquals("true", field(v1, "IsTruncated"))
        assertContains(v1, "<Marker></Marker>")
        assertEquals(null, field(v1, "KeyCount"), "KeyCount is not a field of ListObjects")

        val next = s3.send("GET", "/photos", query = "max-keys=2&marker=b").text
        assertEquals(listOf("c"), keysOf(next))
        assertEquals("b", field(next, "Marker"))
    }

    @Test
    fun `the first version issues a NextMarker when a group ends the page`() {
        // The model's own note: NextMarker is returned only with a delimiter, because that is the
        // case where the last thing on the page is not a key the client could resume from.
        bucket("a/1", "b/1", "c/1")

        val plain = s3.send("GET", "/photos", query = "max-keys=1").text
        assertEquals(null, field(plain, "NextMarker"))

        val grouped = s3.send("GET", "/photos", query = "max-keys=1&delimiter=%2F").text
        assertEquals(listOf("a/"), prefixesOf(grouped))
        assertEquals("a/", field(grouped, "NextMarker"))

        val next = s3.send("GET", "/photos", query = "max-keys=1&delimiter=%2F&marker=a%2F").text
        assertEquals(listOf("b/"), prefixesOf(next), "resuming at a group must not roll the same group up again")
    }

    @Test
    fun `a key that XML cannot carry is why encoding-type exists`() {
        // The model's answer to a key holding bytes XML 1.0 has no representation for is not to
        // rewrite the key — it is `encoding-type=url` (shapes.EncodingType). Without it the key
        // goes out as it is and the client's parser rejects the document, exactly as from S3.
        s3.createBucket("photos")
        s3.put("photos", "a%01b.txt", "x")

        val raw = listV2().body
        // The byte is written as an escape rather than typed: an invisible control character
        // in a source file reads as an empty string, and this assertion was one when it was.
        assertTrue(raw.contains(0x01), "the unrepresentable byte goes out as it is")

        val encoded = listV2("encoding-type=url").text
        assertEquals(listOf("a%01b.txt"), keysOf(encoded))
        assertEquals("url", field(encoded, "EncodingType"))
    }

    @Test
    fun `a listing is bounded by its prefix`() {
        bucket("a/1", "a/2", "b/1")
        assertEquals(listOf("a/1", "a/2"), keysOf(listV2("prefix=a%2F").text))
        assertEquals("a/", field(listV2("prefix=a%2F").text, "Prefix"))
    }

    @Test
    fun `max-keys that is not a number is refused`() {
        bucket("a")
        val refused = listV2("max-keys=blah")
        assertEquals(400, refused.status)
        assertContains(refused.text, "InvalidArgument")
    }

    @Test
    fun `max-keys above the cap gives a capped page and says what was asked for`() {
        bucket(*(0 until 5).map { "k$it" }.toTypedArray())
        val body = listV2("max-keys=5000").text
        // MaxKeys echoes the request, because that is what the client sent and what S3 returns;
        // the page is capped regardless.
        assertEquals("5000", field(body, "MaxKeys"))
        assertEquals(5, keysOf(body).size)
    }

    @Test
    fun `a listing of an empty bucket is a listing`() {
        s3.createBucket("photos")
        val body = listV2().text
        assertEquals(emptyList(), keysOf(body))
        assertEquals("0", field(body, "KeyCount"))
        assertTrue(field(body, "IsTruncated") == "false")
    }
}
