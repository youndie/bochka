package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Tags belong to a version, and `?versionId=` is how a client says which one (M-305).
 *
 * Found by mint's versioning suite: it writes several versions of one key, each with its own
 * `x-amz-tagging`, and then asks for the tags of each **by version id**. Every answer was the
 * newest version's tags, because the tagging route did not carry a version at all — the query
 * parameter was parsed for `?acl`, for `?attributes` and for a plain read, and dropped here.
 *
 * The failure is quiet in the worst way: the answer is a valid tag set belonging to a real object,
 * so nothing looks wrong until somebody compares it with what they wrote.
 */
class TaggingByVersionTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private fun versioned(): Pair<String, String> {
        s3.createBucket("photos")
        assertEquals(
            200,
            s3
                .send(
                    "PUT",
                    "/photos",
                    query = "versioning",
                    body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
                ).status,
        )
        val first = s3.put("photos", "k", "one", headers = listOf("x-amz-tagging" to "colour=red"))
        val second = s3.put("photos", "k", "two", headers = listOf("x-amz-tagging" to "colour=blue"))
        return first.header("x-amz-version-id")!! to second.header("x-amz-version-id")!!
    }

    @Test
    fun `the tags of an old version are its own`() {
        val (older, newer) = versioned()

        val old = s3.send("GET", "/photos/k", query = "tagging&versionId=$older")
        assertContains(
            old.text,
            "<Value>red</Value>",
            message = "the older version answered with somebody else's tags",
        )

        val new = s3.send("GET", "/photos/k", query = "tagging&versionId=$newer")
        assertContains(new.text, "<Value>blue</Value>")

        // Without a version the newest wins, which is what every other read here does.
        assertContains(s3.send("GET", "/photos/k", query = "tagging").text, "<Value>blue</Value>")
    }

    @Test
    fun `writing tags to a named version leaves the others alone`() {
        val (older, newer) = versioned()

        val written =
            s3.send(
                "PUT",
                "/photos/k",
                query = "tagging&versionId=$older",
                body =
                    "<Tagging><TagSet><Tag><Key>colour</Key><Value>green</Value></Tag></TagSet></Tagging>"
                        .toByteArray(),
            )
        assertEquals(200, written.status, written.text)

        assertContains(s3.send("GET", "/photos/k", query = "tagging&versionId=$older").text, "<Value>green</Value>")
        assertContains(
            s3.send("GET", "/photos/k", query = "tagging&versionId=$newer").text,
            "<Value>blue</Value>",
            message = "writing tags to one version changed another",
        )
    }

    @Test
    fun `deleting the tags of a named version leaves the others alone`() {
        val (older, newer) = versioned()

        assertEquals(204, s3.send("DELETE", "/photos/k", query = "tagging&versionId=$older").status)

        val old = s3.send("GET", "/photos/k", query = "tagging&versionId=$older")
        assertEquals(200, old.status, "an object with no tags still has a tag set, an empty one")
        assertEquals(false, old.text.contains("<Value>red</Value>"), "the tags were not removed")
        assertContains(s3.send("GET", "/photos/k", query = "tagging&versionId=$newer").text, "<Value>blue</Value>")
    }

    @Test
    fun `a version that does not exist is not the newest one`() {
        // The failure this whole case is about, asked directly: a wrong id must not quietly answer
        // with the current version's tags.
        versioned()

        val answer = s3.send("GET", "/photos/k", query = "tagging&versionId=nosuchversion")

        assertEquals(404, answer.status, "an unknown version answered with something: ${answer.text}")
    }
}
