package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multipart upload over a socket (M-54…M-57).
 *
 * Operations and statuses from `docs/api/protocol-s3.md` §4; the five-mebibyte floor and the
 * 1..10 000 part numbers from §8, which takes them from `s3-service-2.json:1604` and the AWS
 * limits table.
 */
class MultipartTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private val fiveMiB = 5 * 1024 * 1024

    private fun begin(key: String = "big.bin"): String {
        s3.createBucket("photos")
        val started = s3.send("POST", "/photos/$key", query = "uploads")
        assertEquals(200, started.status, started.text)
        return Regex("<UploadId>(.*?)</UploadId>").find(started.text)!!.groupValues[1]
    }

    private fun uploadPart(
        uploadId: String,
        number: Int,
        content: ByteArray,
        key: String = "big.bin",
    ): S3Fixture.Answer =
        s3.send("PUT", "/photos/$key", query = "partNumber=$number&uploadId=$uploadId", body = content)

    private fun complete(
        uploadId: String,
        parts: List<Pair<Int, String>>,
        key: String = "big.bin",
    ): S3Fixture.Answer {
        val body =
            buildString {
                append("<CompleteMultipartUpload>")
                for ((number, eTag) in parts) append("<Part><PartNumber>$number</PartNumber><ETag>$eTag</ETag></Part>")
                append("</CompleteMultipartUpload>")
            }.toByteArray()
        return s3.send("POST", "/photos/$key", query = "uploadId=$uploadId", body = body)
    }

    @Test
    fun `an object arrives in parts and comes back whole`() {
        val uploadId = begin()
        val first = ByteArray(fiveMiB) { (it % 251).toByte() }
        val second = "the tail, which may be short".toByteArray()

        val a = uploadPart(uploadId, 1, first)
        val b = uploadPart(uploadId, 2, second)
        assertEquals(200, a.status)
        assertEquals(200, b.status)

        val done = complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))
        assertEquals(200, done.status, done.text)
        assertContains(done.text, "<CompleteMultipartUploadResult")

        val got = s3.get("photos", "big.bin")
        assertEquals(200, got.status)
        assertContentEquals(first + second, got.body)
        assertEquals((first.size + second.size).toString(), got.header("Content-Length"))
    }

    @Test
    fun `the ETag of a multipart object says how many parts it had`() {
        // M-56, and the suffix is the point: a client that computed an MD5 of the whole file and
        // compared would find they disagree. The `-N` says "this is not that kind of ETag".
        val uploadId = begin()
        val a = uploadPart(uploadId, 1, ByteArray(fiveMiB))
        val b = uploadPart(uploadId, 2, ByteArray(16))

        val done = complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))
        // The quotes of an ETag arrive as `&quot;` in a document — that is what S3 sends too, and
        // a client's XML parser gives them back.
        val eTag = Regex("<ETag>(.*?)</ETag>").find(done.text)!!.groupValues[1].replace("&quot;", "\"")
        assertTrue(eTag.endsWith("-2\""), "a two-part object should carry -2, got $eTag")
        assertEquals(eTag, s3.get("photos", "big.bin").header("ETag"))
    }

    @Test
    fun `a part below the minimum that is not the last is refused`() {
        // ceph/s3-tests, test_multipart_upload_size_too_small: 400 EntityTooSmall at completion,
        // not at upload — the server cannot know a part is not the last until the list arrives.
        val uploadId = begin()
        val a = uploadPart(uploadId, 1, ByteArray(1024))
        val b = uploadPart(uploadId, 2, ByteArray(1024))
        assertEquals(200, a.status, "a small part is accepted; it might be the last one")

        val refused = complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))
        assertEquals(400, refused.status)
        assertContains(refused.text, "EntityTooSmall")
        assertEquals(404, s3.get("photos", "big.bin").status, "a refused completion creates nothing")
    }

    @Test
    fun `a single part of any size is a complete object`() {
        // The floor applies to every part but the last, so an upload of one part has no floor.
        val uploadId = begin()
        val only = uploadPart(uploadId, 1, "small".toByteArray())
        assertEquals(200, complete(uploadId, listOf(1 to only.header("ETag")!!)).status)
        assertEquals("small", s3.get("photos", "big.bin").text)
    }

    @Test
    fun `parts out of order are refused by name`() {
        val uploadId = begin()
        val a = uploadPart(uploadId, 1, ByteArray(fiveMiB))
        val b = uploadPart(uploadId, 2, ByteArray(16))

        val refused = complete(uploadId, listOf(2 to b.header("ETag")!!, 1 to a.header("ETag")!!))
        assertEquals(400, refused.status)
        assertContains(refused.text, "InvalidPartOrder")
    }

    @Test
    fun `a part the client names wrongly is refused`() {
        val uploadId = begin()
        val a = uploadPart(uploadId, 1, "content".toByteArray())

        assertContains(complete(uploadId, listOf(1 to "\"deadbeef\"")).text, "InvalidPart")
        assertContains(complete(uploadId, listOf(7 to a.header("ETag")!!)).text, "InvalidPart")
    }

    @Test
    fun `a part sent twice replaces the first one`() {
        val uploadId = begin()
        uploadPart(uploadId, 1, "first try".toByteArray())
        val second = uploadPart(uploadId, 1, "second try".toByteArray())

        assertEquals(200, complete(uploadId, listOf(1 to second.header("ETag")!!)).status)
        assertEquals("second try", s3.get("photos", "big.bin").text)
    }

    @Test
    fun `the parts of an upload can be listed`() {
        val uploadId = begin()
        uploadPart(uploadId, 2, ByteArray(32))
        uploadPart(uploadId, 1, ByteArray(16))

        val listed = s3.send("GET", "/photos/big.bin", query = "uploadId=$uploadId")
        assertEquals(200, listed.status)
        val numbers = Regex("<PartNumber>(\\d+)</PartNumber>").findAll(listed.text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("1", "2"), numbers, "parts are listed in order whatever order they arrived in")
        val sizes = Regex("<Size>(\\d+)</Size>").findAll(listed.text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("16", "32"), sizes)
    }

    @Test
    fun `an aborted upload is gone, and so are its parts`() {
        val uploadId = begin()
        uploadPart(uploadId, 1, ByteArray(1024))

        assertEquals(204, s3.send("DELETE", "/photos/big.bin", query = "uploadId=$uploadId").status)
        assertEquals(404, s3.send("GET", "/photos/big.bin", query = "uploadId=$uploadId").status)
        assertEquals(404, s3.send("DELETE", "/photos/big.bin", query = "uploadId=$uploadId").status)

        // And nothing of it is left on the disk. Aborting is the only moment anybody says so.
        assertEquals(0, s3.store.sweepOrphans(olderThanMillis = -1000))
    }

    @Test
    fun `an upload nobody started takes nothing`() {
        s3.createBucket("photos")
        val refused = s3.send("PUT", "/photos/big.bin", query = "partNumber=1&uploadId=none", body = "x".toByteArray())
        assertEquals(404, refused.status)
        assertContains(refused.text, "NoSuchUpload")
    }

    @Test
    fun `uploads in flight can be listed and are gone once completed`() {
        val uploadId = begin("one.bin")
        s3.send("POST", "/photos/two.bin", query = "uploads")

        val listed = s3.send("GET", "/photos", query = "uploads")
        assertEquals(200, listed.status)
        val keys = Regex("<Key>(.*?)</Key>").findAll(listed.text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("one.bin", "two.bin"), keys)

        val only = uploadPart(uploadId, 1, "x".toByteArray(), key = "one.bin")
        complete(uploadId, listOf(1 to only.header("ETag")!!), key = "one.bin")

        val after = s3.send("GET", "/photos", query = "uploads").text
        assertEquals(listOf("two.bin"), Regex("<Key>(.*?)</Key>").findAll(after).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `an upload in flight survives a restart`() {
        // The reason the upload is in the index log and not only in memory: a client has been told
        // its part was accepted, and a restart that forgot it would make that a lie.
        val uploadId = begin()
        val part = uploadPart(uploadId, 1, "the part".toByteArray())

        s3.store.close()
        val reopened =
            io.github.youndie.bochka.core
                .ObjectStore(s3.root)
        assertEquals(uploadId, reopened.upload(uploadId)?.id)
        assertEquals(listOf(1), reopened.parts(uploadId).map { it.number })
        assertEquals(part.header("ETag"), reopened.parts(uploadId).single().eTag)
        reopened.close()
    }

    @Test
    fun `an abandoned upload does not live for ever`() {
        // M-57. Nothing else will ever mention it: a client that stops calling says nothing.
        val uploadId = begin()
        uploadPart(uploadId, 1, ByteArray(1024))

        assertEquals(0, s3.store.sweepUploads(olderThanMillis = 60_000), "a fresh upload is not abandoned")
        assertEquals(1, s3.store.sweepUploads(olderThanMillis = -1000))
        assertEquals(null, s3.store.upload(uploadId))
        assertEquals(0, s3.store.sweepOrphans(olderThanMillis = -1000), "its parts went with it")
    }

    @Test
    fun `a part number outside the allowed range is refused from the head`() {
        val uploadId = begin()
        assertEquals(400, uploadPart(uploadId, 0, "x".toByteArray()).status)
        assertEquals(400, uploadPart(uploadId, 10_001, "x".toByteArray()).status)
        assertEquals(200, uploadPart(uploadId, 10_000, "x".toByteArray()).status)
    }
}
