package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Versions on the wire (M-104, M-106, M-108).
 *
 * The answer shapes come from `s3-service-2.json`: `PutObjectOutput.members.VersionId` with
 * `location: "header", locationName: "x-amz-version-id"`, `DeleteObjectOutput.members.DeleteMarker`
 * with `x-amz-delete-marker`, and `GetObjectRequest.members.VersionId` as the query parameter
 * `versionId`.
 *
 * What is checked here is the thing no single-operation test can see: **what is left afterwards**.
 * Versioning consists entirely of relations between writes, and an assertion of the form "the PUT
 * answered 200" passes the same way whether there are versions or not.
 */
class VersioningTest {
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
    fun `a bucket without versioning names no version at all`() {
        // The header on every answer would tell the client that the bucket versions when it does
        // not. The header's absence here is an assertion rather than an omission.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val put = s3.put("photos", "a.txt", "первый")

            assertEquals(200, put.status)
            assertNull(put.header("x-amz-version-id"))
            assertNull(s3.get("photos", "a.txt").header("x-amz-version-id"))
        }
    }

    @Test
    fun `two writes to a versioning bucket leave two versions, and the newer one is current`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")

            val first = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")
            val second = s3.put("photos", "a.txt", "второй").header("x-amz-version-id")

            assertNotNull(first)
            assertNotNull(second)
            assertNotEquals(first, second, "two writes are two versions rather than one overwritten")
            assertEquals("второй", s3.get("photos", "a.txt").text)
            assertEquals("первый", s3.send("GET", "/photos/a.txt", query = "versionId=$first").text)
            assertEquals("второй", s3.send("GET", "/photos/a.txt", query = "versionId=$second").text)
        }
    }

    @Test
    fun `deleting lays a tombstone rather than removing anything`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val version = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")

            val deleted = s3.send("DELETE", "/photos/a.txt")

            assertEquals(204, deleted.status)
            assertEquals("true", deleted.header("x-amz-delete-marker"))
            assertNotNull(deleted.header("x-amz-version-id"))
            // The key is gone and the bytes are not: that is the difference between a deletion and
            // a tombstone.
            val read = s3.get("photos", "a.txt")
            assertEquals(404, read.status)
            assertEquals(
                "true",
                read.header("x-amz-delete-marker"),
                "otherwise the client cannot know the object is recoverable",
            )
            assertEquals("первый", s3.send("GET", "/photos/a.txt", query = "versionId=$version").text)
        }
    }

    @Test
    fun `removing the tombstone brings the key back`() {
        // This is what a tombstone hands its versionId out in the DELETE answer for: the client has
        // no other way to learn it.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            val marker = s3.send("DELETE", "/photos/a.txt").header("x-amz-version-id")

            val removed = s3.send("DELETE", "/photos/a.txt", query = "versionId=$marker")

            assertEquals(204, removed.status)
            assertEquals("true", removed.header("x-amz-delete-marker"))
            assertEquals("первый", s3.get("photos", "a.txt").text)
        }
    }

    @Test
    fun `reading a tombstone by its id is 405, not 404`() {
        // Different questions, different answers. A `404` would say the version does not exist, and
        // the client would stop trying to delete it — that is, stop trying to bring the object
        // back.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            val marker = s3.send("DELETE", "/photos/a.txt").header("x-amz-version-id")

            val read = s3.send("GET", "/photos/a.txt", query = "versionId=$marker")

            assertEquals(405, read.status, read.text)
            assertEquals("true", read.header("x-amz-delete-marker"))
        }
    }

    @Test
    fun `a listing shows the current version and skips a tombstoned key`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            s3.put("photos", "a.txt", "второй")
            s3.put("photos", "b.txt", "бэ")

            s3.send("DELETE", "/photos/b.txt")
            val listing = s3.send("GET", "/photos", query = "list-type=2")

            assertTrue("<Key>a.txt</Key>" in listing.text, listing.text)
            assertTrue("<Key>b.txt</Key>" !in listing.text, "a tombstone is not listed: ${listing.text}")
            // Two PUTs into one key are one line of the listing rather than two: a listing is about
            // objects rather than about versions.
            assertEquals(1, Regex("<Key>").findAll(listing.text).count(), listing.text)
        }
    }

    @Test
    fun `deleting one version by id leaves the others, and the newest of them is current`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val first = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")
            val second = s3.put("photos", "a.txt", "второй").header("x-amz-version-id")

            assertEquals(204, s3.send("DELETE", "/photos/a.txt", query = "versionId=$second").status)

            assertEquals("первый", s3.get("photos", "a.txt").text, "the one that remained became the current one")
            assertEquals(404, s3.send("GET", "/photos/a.txt", query = "versionId=$second").status)
            assertEquals("первый", s3.send("GET", "/photos/a.txt", query = "versionId=$first").text)
        }
    }

    @Test
    fun `suspending keeps the old versions and stops making new ones`() {
        // A suspended bucket is not "one that was never enabled": the versions made while it was
        // enabled stay reachable by name.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val versioned = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")

            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = "<VersioningConfiguration><Status>Suspended</Status></VersioningConfiguration>".toByteArray(),
            )
            s3.put("photos", "a.txt", "второй")
            s3.put("photos", "a.txt", "третий")

            assertEquals("третий", s3.get("photos", "a.txt").text)
            assertEquals("первый", s3.send("GET", "/photos/a.txt", query = "versionId=$versioned").text)
            // Writes into a suspended bucket collapse into one `null` version rather than piling
            // up.
            assertEquals("третий", s3.send("GET", "/photos/a.txt", query = "versionId=null").text)
        }
    }

    @Test
    fun `a copy names the version it copies from`() {
        // M-163. Before this the version in `x-amz-copy-source` was stripped and thrown away: the
        // client asked for the old one, got the current one, and had **no sign at all** that its
        // request had been reinterpreted.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val first = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")
            s3.put("photos", "a.txt", "второй")

            s3.send(
                "PUT",
                "/photos/copy.txt",
                headers = listOf("x-amz-copy-source" to "/photos/a.txt?versionId=$first"),
            )

            assertEquals("первый", s3.get("photos", "copy.txt").text)
        }
    }

    @Test
    fun `a batch delete says which tombstones it made`() {
        // M-161. The only place the batch form hands out a tombstone's name: without it a client
        // that deleted a thousand keys cannot undo a single one.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "тело")
            val body = "<Delete><Object><Key>a.txt</Key></Object></Delete>".toByteArray()
            val md5 =
                java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest
                        .getInstance("MD5")
                        .digest(body),
                )

            val answer =
                s3.send("POST", "/photos", query = "delete", headers = listOf("Content-MD5" to md5), body = body)

            assertTrue("<DeleteMarker>true</DeleteMarker>" in answer.text, answer.text)
            assertTrue("<DeleteMarkerVersionId>" in answer.text, answer.text)
        }
    }

    @Test
    fun `GetObjectAttributes reads the version it was given`() {
        // M-164.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val first = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")
            s3.put("photos", "a.txt", "подлиннее второй")

            val attributes =
                s3.send(
                    "GET",
                    "/photos/a.txt",
                    query = "attributes&versionId=$first",
                    headers =
                        listOf("x-amz-object-attributes" to "ObjectSize"),
                )

            assertTrue("<ObjectSize>12</ObjectSize>" in attributes.text, attributes.text)
        }
    }
}
