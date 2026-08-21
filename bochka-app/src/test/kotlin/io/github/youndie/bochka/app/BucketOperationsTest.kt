package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bucket operations (M-41), over a socket.
 *
 * Statuses from `docs/api/protocol-s3.md` §4; names from the AWS bucket-naming rules, checked apart
 * in `BucketNameRulesTest` and here in the form a client sees them.
 */
class BucketOperationsTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    @Test
    fun `a bucket is created, found, listed and deleted`() {
        assertEquals(200, s3.createBucket("photos").status)
        assertEquals("/photos", s3.createBucket("photos").header("Location"))
        assertEquals(200, s3.send("HEAD", "/photos").status)

        val listed = s3.send("GET", "/")
        assertEquals(200, listed.status)
        assertContains(listed.text, "<Name>photos</Name>")
        assertContains(listed.text, "ListAllMyBucketsResult")

        assertEquals(204, s3.send("DELETE", "/photos").status)
        assertEquals(404, s3.send("HEAD", "/photos").status)
    }

    @Test
    fun `creating a bucket twice is a success`() {
        // Every client treats `mb` as idempotent, and in us-east-1 so does S3.
        assertEquals(200, s3.createBucket("photos").status)
        assertEquals(200, s3.createBucket("photos").status)
    }

    @Test
    fun `a bucket with something in it is not deleted`() {
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "hello")

        val refused = s3.send("DELETE", "/photos")
        assertEquals(409, refused.status)
        assertContains(refused.text, "BucketNotEmpty")

        s3.send("DELETE", "/photos/a.txt")
        assertEquals(204, s3.send("DELETE", "/photos").status)
    }

    @Test
    fun `a name that cannot be a bucket is not a missing bucket`() {
        // InvalidBucketName rather than NoSuchBucket, and the difference is what the client does
        // next: NoSuchBucket invites it to create the thing, which will never work.
        val refused = s3.send("PUT", "/MyBucket")
        assertEquals(400, refused.status)
        assertContains(refused.text, "InvalidBucketName")

        assertEquals(400, s3.send("GET", "/ab").status)
        assertEquals(400, s3.send("HEAD", "/192.168.5.4").status)
    }

    @Test
    fun `a missing bucket is a missing bucket`() {
        val missing = s3.send("GET", "/absent-bucket")
        assertEquals(404, missing.status)
        assertContains(missing.text, "NoSuchBucket")
    }

    @Test
    fun `the location of a bucket is the configured region`() {
        s3.createBucket("photos")
        val location = s3.send("GET", "/photos", query = "location")
        assertEquals(200, location.status)
        assertContains(location.text, "<LocationConstraint")
        assertContains(location.text, "us-east-1")
    }

    @Test
    fun `a batch delete without a checksum of its own body does not happen`() {
        // M-45: the body is a list of things to destroy, so a corrupted one destroys the wrong
        // objects and leaves nothing to notice it by. Every SDK sends a checksum; a request
        // without one is refused rather than executed.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "hello")

        val body = "<Delete><Object><Key>a.txt</Key></Object></Delete>".toByteArray()
        val refused = s3.send("POST", "/photos", query = "delete", body = body)
        assertEquals(400, refused.status)
        assertContains(refused.text, "MissingContentMD5")
        assertEquals(200, s3.get("photos", "a.txt").status)
    }

    @Test
    fun `a batch delete with a checksum empties the bucket`() {
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "hello")
        s3.put("photos", "b.txt", "hello")

        val body = "<Delete><Object><Key>a.txt</Key></Object><Object><Key>b.txt</Key></Object></Delete>".toByteArray()
        val md5 =
            java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest
                    .getInstance("MD5")
                    .digest(body),
            )
        val done = s3.send("POST", "/photos", query = "delete", headers = listOf("Content-MD5" to md5), body = body)

        assertEquals(200, done.status)
        assertContains(done.text, "DeleteResult")
        assertEquals(404, s3.get("photos", "a.txt").status)
        assertEquals(404, s3.get("photos", "b.txt").status)
        assertEquals(204, s3.send("DELETE", "/photos").status)
    }

    @Test
    fun `a bucket a client asks for by hostname is the same bucket`() {
        // Virtual-hosted addressing is configuration, never guessed (S3Router): the suffix has to
        // be listed, and then a leading label is a bucket name.
        S3Fixture(virtualHostSuffixes = listOf("example.test")).use { hosted ->
            hosted.host = "photos.example.test:${hosted.port}"
            // The path carries no bucket at all now: the whole of it is the key.
            assertEquals(200, hosted.send("PUT", "/").status)
            assertEquals(200, hosted.send("PUT", "/a.txt", body = "hello".toByteArray()).status)
            assertEquals("hello", hosted.send("GET", "/a.txt").text)

            // And the same object, addressed path-style against the same server.
            hosted.host = "127.0.0.1:${hosted.port}"
            assertEquals("hello", hosted.get("photos", "a.txt").text)
        }
    }

    @Test
    fun `a bucket deleted while a body is arriving makes the write NoSuchBucket`() {
        // M-220. The bucket is checked before the body is read, and the body then takes as long as
        // the client takes; found on a deployment, where deleting the bucket eight seconds into an
        // eighty-second upload left the client with `200` for an object no listing showed.
        //
        // `DeleteBucket` in the middle succeeds and is right to: at that instant the bucket is
        // empty, because the bytes are staged and belong to nobody yet.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            var deleted = -1

            val answer =
                s3.send(
                    "PUT",
                    "/photos/a.txt",
                    body = ByteArray(64) { 'x'.code.toByte() },
                    payloadHash = "UNSIGNED-PAYLOAD",
                    duringBody = { deleted = s3.send("DELETE", "/photos").status },
                )

            assertEquals(204, deleted, "the bucket was empty when it was deleted")
            assertEquals(404, answer.status, answer.text)
            assertTrue("NoSuchBucket" in answer.text, answer.text)

            // And nothing is left under the name: a version in a deleted bucket makes it
            // un-deletable for whoever takes the name next, and invisible to everyone.
            assertEquals(200, s3.createBucket("photos").status)
            assertEquals(204, s3.send("DELETE", "/photos").status, "nothing was left behind")
        }
    }
}
