package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The preconditions of a **write**: `If-Match`, `If-None-Match`, `x-amz-if-match-size` and
 * `x-amz-if-match-last-modified-time` (M-80, M-84, M-85).
 *
 * There was no test file here before, and that is the finding this milestone opened with: M-80 was
 * closed on the suite's total moving rather than on the cases it was written for, and every one of
 * those cases was still red with a different message. The conditions themselves are read out of
 * `s3-service-2.json` — `DeleteObjectRequest.members.IfMatch`, `.IfMatchSize` (`x-amz-if-match-size`,
 * a `long`) and `.IfMatchLastModifiedTime` (`x-amz-if-match-last-modified-time`, `rfc822`) — which
 * is also where the rule that decides half of this file comes from: *"If the Size matches **or if
 * the object doesn't exist**, the operation returns 204."*
 *
 * That last clause is the whole difference between the two operations. On a `DELETE` a precondition
 * against a key that is not there is satisfied by there being nothing to protect; on a `PUT` it is
 * `404 NoSuchKey`, because `If-Match` on a write means "replace the thing I looked at" and there is
 * no thing.
 */
class ConditionalWriteTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private fun bucket(): String {
        s3.createBucket("photos")
        return "photos"
    }

    private fun eTagOf(
        bucket: String,
        key: String,
    ) = s3.send("HEAD", "/$bucket/$key").header("ETag")!!

    @Test
    fun `If-None-Match star creates a key only if nobody got there first`() {
        val bucket = bucket()
        assertEquals(200, s3.put(bucket, "obj", "first", listOf("If-None-Match" to "*")).status)

        val second = s3.put(bucket, "obj", "second", listOf("If-None-Match" to "*"))
        assertEquals(412, second.status, second.text)
        assertContains(second.text, "PreconditionFailed")
        assertEquals("first", s3.get(bucket, "obj").text, "the refused write must not have landed")
    }

    @Test
    fun `If-None-Match with a tag refuses only that tag`() {
        val bucket = bucket()
        s3.put(bucket, "obj", "first")
        val eTag = eTagOf(bucket, "obj")

        assertEquals(412, s3.put(bucket, "obj", "second", listOf("If-None-Match" to eTag)).status)
        assertEquals(200, s3.put(bucket, "obj", "second", listOf("If-None-Match" to "\"badetag\"")).status)
    }

    @Test
    fun `If-Match against a key that is not there is NoSuchKey, not PreconditionFailed`() {
        // M-85, and the direction of it was written down backwards. `If-Match` on a write says
        // "replace what I read"; if there is nothing there, the client is not wrong about the tag,
        // it is wrong about the object — and a `412` would send it off to re-read an ETag that
        // does not exist. `s3-service-2.json` keeps the two apart, and so does the suite
        // (`test_put_object_if_match`, `test_put_object_ifmatch_nonexisted_failed`).
        val bucket = bucket()

        val star = s3.put(bucket, "obj", "x", listOf("If-Match" to "*"))
        assertEquals(404, star.status, star.text)
        assertContains(star.text, "NoSuchKey")

        val tag = s3.put(bucket, "obj", "x", listOf("If-Match" to "\"badetag\""))
        assertEquals(404, tag.status, tag.text)
        assertContains(tag.text, "NoSuchKey")
    }

    @Test
    fun `If-Match against a key that is there compares the tag`() {
        val bucket = bucket()
        s3.put(bucket, "obj", "first")
        val eTag = eTagOf(bucket, "obj")

        assertEquals(412, s3.put(bucket, "obj", "second", listOf("If-Match" to "\"badetag\"")).status)
        assertEquals(200, s3.put(bucket, "obj", "second", listOf("If-Match" to eTag)).status)
        assertEquals(200, s3.put(bucket, "obj", "third", listOf("If-Match" to "*")).status)
    }

    @Test
    fun `a delete of what is not there ignores its precondition`() {
        // The clause quoted in the class comment, and it is not symmetry with `PUT` — it follows
        // from deleting a missing key already being a success. A precondition exists to stop a
        // delete from destroying something unexpected, and there is nothing to destroy.
        val bucket = bucket()

        assertEquals(204, s3.send("DELETE", "/$bucket/obj", headers = listOf("If-Match" to "*")).status)
        assertEquals(204, s3.send("DELETE", "/$bucket/obj", headers = listOf("If-Match" to "\"badetag\"")).status)
        assertEquals(204, s3.send("DELETE", "/$bucket/obj", headers = listOf("x-amz-if-match-size" to "9999")).status)
    }

    @Test
    fun `a delete of what is there compares the tag`() {
        val bucket = bucket()
        s3.put(bucket, "obj", "first")
        val eTag = eTagOf(bucket, "obj")

        val refused = s3.send("DELETE", "/$bucket/obj", headers = listOf("If-Match" to "\"badetag\""))
        assertEquals(412, refused.status, refused.text)
        assertContains(refused.text, "PreconditionFailed")
        assertEquals(200, s3.get(bucket, "obj").status, "the refused delete must not have happened")

        assertEquals(204, s3.send("DELETE", "/$bucket/obj", headers = listOf("If-Match" to eTag)).status)
    }

    @Test
    fun `x-amz-if-match-size is a precondition on the length`() {
        // M-84. A separate header and a separate feature from `If-Match`: a client that knows how
        // long the object should be does not have to have read its ETag, and the two can be sent
        // together — `s3-service-2.json` says so in as many words.
        val bucket = bucket()
        s3.put(bucket, "obj", "twelve bytes")

        val wrong = s3.send("DELETE", "/$bucket/obj", headers = listOf("x-amz-if-match-size" to "9999"))
        assertEquals(412, wrong.status, wrong.text)
        assertContains(wrong.text, "PreconditionFailed")

        assertEquals(204, s3.send("DELETE", "/$bucket/obj", headers = listOf("x-amz-if-match-size" to "12")).status)
    }

    @Test
    fun `x-amz-if-match-last-modified-time is a precondition on the timestamp`() {
        val bucket = bucket()
        s3.put(bucket, "obj", "x")
        val modified = s3.send("HEAD", "/$bucket/obj").header("Last-Modified")!!

        val wrong =
            s3.send(
                "DELETE",
                "/$bucket/obj",
                headers = listOf("x-amz-if-match-last-modified-time" to "Thu, 01 Jan 2015 00:00:00 GMT"),
            )
        assertEquals(412, wrong.status, wrong.text)

        val right = s3.send("DELETE", "/$bucket/obj", headers = listOf("x-amz-if-match-last-modified-time" to modified))
        assertEquals(204, right.status, right.text)
    }

    @Test
    fun `a size that is not a number is a bad request rather than a condition that never holds`() {
        // A malformed condition is not a failed one. Treating it as failed would answer `412`,
        // which tells the client its object changed — sending it to look at an object that is
        // exactly as it left it.
        val bucket = bucket()
        s3.put(bucket, "obj", "x")

        val answer = s3.send("DELETE", "/$bucket/obj", headers = listOf("x-amz-if-match-size" to "twelve"))
        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "InvalidArgument")
    }

    // --- DeleteObjects, where a refusal is per key ------------------------------------------------

    private fun deleteObjects(vararg entries: String): S3Fixture.Answer {
        val body =
            entries
                .joinToString("", prefix = "<Delete>", postfix = "</Delete>") { "<Object>$it</Object>" }
                .toByteArray()
        return s3.send(
            "POST",
            "/photos",
            query = "delete",
            headers = listOf("Content-MD5" to md5(body)),
            body = body,
        )
    }

    private fun md5(body: ByteArray) =
        java.util.Base64
            .getEncoder()
            .encodeToString(
                java.security.MessageDigest
                    .getInstance("MD5")
                    .digest(body),
            )

    @Test
    fun `a batch delete reports the key whose precondition did not hold`() {
        // M-85. Without this the suite fails with `KeyError: 'Errors'` — the batch answers that
        // everything was deleted, and the client believes an object it asked to be protected is
        // gone. A per-key refusal is the only shape this operation has for saying otherwise.
        bucket()
        s3.put("photos", "obj", "first")

        val refused = deleteObjects("<Key>obj</Key><ETag>\"badetag\"</ETag>")
        assertEquals(200, refused.status, refused.text)
        assertContains(refused.text, "<Error>")
        assertContains(refused.text, "<Code>PreconditionFailed</Code>")
        assertEquals(200, s3.get("photos", "obj").status, "the refused key must still be there")

        val eTag = eTagOf("photos", "obj")
        val done = deleteObjects("<Key>obj</Key><ETag>$eTag</ETag>")
        assertContains(done.text, "<Deleted>")
        assertEquals(404, s3.get("photos", "obj").status)
    }

    @Test
    fun `a batch delete of a key that is not there ignores its precondition`() {
        bucket()
        val answer = deleteObjects("<Key>gone</Key><ETag>\"badetag\"</ETag>")
        assertEquals(200, answer.status, answer.text)
        assertContains(answer.text, "<Deleted>")
    }

    @Test
    fun `a batch delete takes the size and the timestamp as conditions too`() {
        bucket()
        s3.put("photos", "obj", "twelve bytes")

        assertContains(deleteObjects("<Key>obj</Key><Size>9999</Size>").text, "<Code>PreconditionFailed</Code>")

        val modified = s3.send("HEAD", "/photos/obj").header("Last-Modified")!!
        assertContains(
            deleteObjects("<Key>obj</Key><LastModifiedTime>Thu, 01 Jan 2015 00:00:00 GMT</LastModifiedTime>").text,
            "<Code>PreconditionFailed</Code>",
        )

        val done = deleteObjects("<Key>obj</Key><Size>12</Size><LastModifiedTime>$modified</LastModifiedTime>")
        assertContains(done.text, "<Deleted>")
    }

    @Test
    fun `one refused key does not stop the rest of the batch`() {
        bucket()
        s3.put("photos", "keep", "x")
        s3.put("photos", "drop", "x")

        val answer = deleteObjects("<Key>keep</Key><ETag>\"badetag\"</ETag>", "<Key>drop</Key>")
        assertContains(answer.text, "<Code>PreconditionFailed</Code>")
        assertContains(answer.text, "<Deleted>")
        assertEquals(200, s3.get("photos", "keep").status)
        assertEquals(404, s3.get("photos", "drop").status)
    }

    // --- and the same conditions on a completion, which is a write like any other -----------------

    private fun multipart(
        key: String,
        conditions: List<Pair<String, String>>,
    ): S3Fixture.Answer {
        val started = s3.send("POST", "/photos/$key", query = "uploads")
        val uploadId = Regex("<UploadId>(.*?)</UploadId>").find(started.text)!!.groupValues[1]
        val part = s3.send("PUT", "/photos/$key", query = "partNumber=1&uploadId=$uploadId", body = "abc".toByteArray())
        val body =
            "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>${part.header("ETag")}</ETag>" +
                "</Part></CompleteMultipartUpload>"
        return s3.send(
            "POST",
            "/photos/$key",
            query = "uploadId=$uploadId",
            headers = conditions,
            body = body.toByteArray(),
        )
    }

    @Test
    fun `a completion carries the same preconditions a PUT does`() {
        // `test_multipart_put_object_if_match`. The condition has to be applied where the key
        // changes hands rather than where the completion starts: the parts take minutes to
        // arrive, and a check made at the beginning of that is a check about a different moment.
        bucket()
        assertEquals(200, multipart("obj", listOf("If-None-Match" to "*")).status)

        val second = multipart("obj", listOf("If-None-Match" to "*"))
        assertEquals(412, second.status, second.text)
        assertContains(second.text, "PreconditionFailed")

        assertEquals(204, s3.send("DELETE", "/photos/obj").status)
        val absent = multipart("obj", listOf("If-Match" to "*"))
        assertEquals(404, absent.status, absent.text)
        assertContains(absent.text, "NoSuchKey")
    }
}
