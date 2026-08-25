package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /<bucket>?versions` (M-107).
 *
 * Until this milestone the operation answered with an ordinary listing in a different wrapper —
 * that is, it said the bucket held one version of everything and that nothing had ever been
 * deleted. The document was of the right shape, and from outside it was indistinguishable from the
 * truth; which is why the tests here count **rows** rather than check a status.
 */
class ListVersionsTest {
    private fun S3Fixture.enable(bucket: String) {
        createBucket(bucket)
        send(
            "PUT",
            "/$bucket",
            query = "versioning",
            body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
        )
    }

    @Test
    fun `every version is a row, newest first and only one of them latest`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            s3.put("photos", "a.txt", "второй")

            val body = s3.send("GET", "/photos", query = "versions").text

            assertEquals(2, Regex("<Version>").findAll(body).count(), body)
            assertEquals(1, Regex("<IsLatest>true</IsLatest>").findAll(body).count(), body)
            // The newest first: that order is the operation's definition, and also the definition
            // of which version is the current one.
            assertTrue(
                body.indexOf("<IsLatest>true</IsLatest>") < body.indexOf("<IsLatest>false</IsLatest>"),
                body,
            )
        }
    }

    @Test
    fun `a tombstone is a DeleteMarker row, not a Version with zero bytes`() {
        // A tombstone carries no ETag and no Size — otherwise a client compares it with an empty
        // object and finds them equal.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            s3.send("DELETE", "/photos/a.txt")

            val body = s3.send("GET", "/photos", query = "versions").text

            assertEquals(1, Regex("<DeleteMarker>").findAll(body).count(), body)
            assertEquals(1, Regex("<Version>").findAll(body).count(), body)
            val marker = body.substring(body.indexOf("<DeleteMarker>"), body.indexOf("</DeleteMarker>"))
            assertTrue("<ETag>" !in marker && "<Size>" !in marker, marker)
        }
    }

    @Test
    fun `a bucket without versioning lists its objects at version null`() {
        // This already worked (M3) and has to keep working: the foreign cleanup calls the operation
        // before every test, and a refusal here cost 837 cases.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "a.txt", "первый")

            val body = s3.send("GET", "/photos", query = "versions").text

            assertTrue("<VersionId>null</VersionId>" in body, body)
            assertTrue("<Key>a.txt</Key>" in body, body)
        }
    }

    @Test
    fun `a page can end inside a key, and the two markers resume it`() {
        // This is what the two markers are for: a page breaks off in the middle of one key's
        // versions, and `key-marker` alone could only resume from a key boundary — that is, either
        // repeating versions or losing them.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            repeat(3) { s3.put("photos", "a.txt", "версия $it") }

            val first = s3.send("GET", "/photos", query = "versions&max-keys=2").text
            assertEquals(2, Regex("<Version>").findAll(first).count(), first)
            assertTrue("<IsTruncated>true</IsTruncated>" in first, first)

            val keyMarker = first.substringAfter("<NextKeyMarker>").substringBefore("</NextKeyMarker>")
            val versionMarker =
                first.substringAfter("<NextVersionIdMarker>").substringBefore("</NextVersionIdMarker>")
            val second =
                s3
                    .send(
                        "GET",
                        "/photos",
                        query = "versions&key-marker=$keyMarker&version-id-marker=$versionMarker",
                    ).text

            assertEquals(1, Regex("<Version>").findAll(second).count(), second)
            assertTrue("версия 0" !in second, "the continuation must not repeat what was already handed out")
        }
    }
}
