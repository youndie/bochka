package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `PUT`, `GET`, `HEAD`, `DELETE` and `Range`, over a socket (M-42, M-43, M-46, M-47).
 *
 * Operations and statuses are `docs/api/protocol-s3.md` §4, which takes them from
 * `s3-service-2.json`; the range arithmetic is checked apart in `ByteRangesTest` and here only in
 * the form it reaches the wire in.
 */
class ObjectOperationsTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private fun bucket(name: String = "photos"): String {
        s3.createBucket(name)
        return name
    }

    @Test
    fun `an object with no content type still answers with one`() {
        // `s3-service-2.json`, `GetObjectOutput.members.ContentType`. A client that reads the
        // header unconditionally gets an error rather than a default of its own.
        val b = bucket()
        s3.put(b, "a.bin", "x")
        assertEquals("binary/octet-stream", s3.get(b, "a.bin").header("Content-Type"))
    }

    @Test
    fun `a body framed by chunked transfer encoding states its own length`() {
        // The rule is that a body whose length is stated **nowhere** cannot be stored. A chunked
        // body states it, chunk by chunk — refusing it was over-reading the rule, and
        // `test_object_write_with_chunked_transfer_encoding` is what said so.
        val b = bucket()
        val chunked =
            s3.send(
                "PUT",
                "/$b/chunked.txt",
                body = "framed by the transfer encoding".toByteArray(),
                chunked = true,
            )
        assertEquals(200, chunked.status, chunked.text)
        assertEquals("framed by the transfer encoding", s3.get(b, "chunked.txt").text)
    }

    @Test
    fun `an object goes in and comes back`() {
        val b = bucket()
        val put = s3.put(b, "a.txt", "hello")
        assertEquals(200, put.status)
        assertEquals("\"5d41402abc4b2a76b9719d911017c592\"", put.header("ETag"))

        val get = s3.get(b, "a.txt")
        assertEquals(200, get.status)
        assertEquals("hello", get.text)
        assertEquals("5", get.header("Content-Length"))
        assertEquals("bytes", get.header("Accept-Ranges"))
    }

    @Test
    fun `HEAD answers the length of the body it is not sending`() {
        // The regression this whole fixture exists for: the object was fine, three clients were
        // fine, and rclone deleted every upload because HEAD said the object was empty.
        val b = bucket()
        s3.put(b, "a.txt", "hello")

        val head = s3.send("HEAD", "/$b/a.txt")
        assertEquals(200, head.status)
        assertEquals("5", head.header("Content-Length"))
        assertEquals(0, head.body.size)
        assertEquals("\"5d41402abc4b2a76b9719d911017c592\"", head.header("ETag"))
    }

    @Test
    fun `HEAD of a missing object is a status and nothing else`() {
        // protocol-s3.md §4: HEAD has no body even on an error, so a client cannot tell a missing
        // object from a missing bucket by reading one.
        val b = bucket()
        val head = s3.send("HEAD", "/$b/nothing")
        assertEquals(404, head.status)
        assertEquals(0, head.body.size)
    }

    @Test
    fun `deleting what is not there is a success`() {
        // protocol-s3.md §4, and the first of the three places intuition says otherwise.
        val b = bucket()
        assertEquals(204, s3.send("DELETE", "/$b/never-existed").status)

        s3.put(b, "a.txt", "hello")
        assertEquals(204, s3.send("DELETE", "/$b/a.txt").status)
        assertEquals(404, s3.get(b, "a.txt").status)
    }

    @Test
    fun `a range comes back as 206 with the bytes it named`() {
        val b = bucket()
        s3.put(b, "a.txt", "testcontent")

        val ranged = s3.get(b, "a.txt", headers = listOf("Range" to "bytes=4-7"))
        assertEquals(206, ranged.status)
        assertEquals("cont", ranged.text)
        assertEquals("4", ranged.header("Content-Length"))
        assertEquals("bytes 4-7/11", ranged.header("Content-Range"))
    }

    @Test
    fun `a suffix range counts from the end`() {
        val b = bucket()
        s3.put(b, "a.txt", "testcontent")

        val ranged = s3.get(b, "a.txt", headers = listOf("Range" to "bytes=-7"))
        assertEquals(206, ranged.status)
        assertEquals("content", ranged.text)
        assertEquals("bytes 4-10/11", ranged.header("Content-Range"))
    }

    @Test
    fun `a range past the end is 416 and says how big the object is`() {
        val b = bucket()
        s3.put(b, "a.txt", "testcontent")

        val ranged = s3.get(b, "a.txt", headers = listOf("Range" to "bytes=40-50"))
        assertEquals(416, ranged.status)
        assertEquals("bytes */11", ranged.header("Content-Range"))
        assertContains(ranged.text, "InvalidRange")
    }

    @Test
    fun `a range of an empty object is 416`() {
        val b = bucket()
        s3.put(b, "empty.txt", "")

        val ranged = s3.get(b, "empty.txt", headers = listOf("Range" to "bytes=0-0"))
        assertEquals(416, ranged.status)
        assertEquals("bytes */0", ranged.header("Content-Range"))
    }

    @Test
    fun `a range this server cannot honour is served whole`() {
        // RFC 9110 §14.2, and the answer that reads as wrong: several ranges is not an error.
        val b = bucket()
        s3.put(b, "a.txt", "testcontent")

        val ranged = s3.get(b, "a.txt", headers = listOf("Range" to "bytes=0-1,4-5"))
        assertEquals(200, ranged.status)
        assertEquals("testcontent", ranged.text)
        assertNull(ranged.header("Content-Range"))
    }

    @Test
    fun `an object larger than one socket buffer arrives intact`() {
        // The response body is a file handed to `transferTo`, which is allowed to move less than
        // asked and does on anything that fills the send buffer. A four-megabyte object is the
        // smallest thing that notices a missing loop.
        val b = bucket()
        val content = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        assertEquals(200, s3.put(b, "big.bin", content).status)

        val got = s3.get(b, "big.bin")
        assertEquals(200, got.status)
        assertContentEquals(content, got.body)

        val tail = s3.get(b, "big.bin", headers = listOf("Range" to "bytes=4194300-"))
        assertEquals(206, tail.status)
        assertContentEquals(content.copyOfRange(4194300, content.size), tail.body)
    }

    @Test
    fun `the metadata an object arrived with comes back with it`() {
        // s3-service-2.json, PutObjectRequest.members: the named ones carry `"location": "header"`,
        // and `Metadata` carries `"location": "headers"` with the prefix `x-amz-meta-`.
        val b = bucket()
        s3.put(
            b,
            "a.txt",
            "hello",
            headers =
                listOf(
                    "Content-Type" to "text/plain; charset=utf-8",
                    "Cache-Control" to "max-age=60",
                    "Content-Disposition" to "attachment; filename=\"a.txt\"",
                    "Content-Language" to "ru",
                    "Expires" to "Thu, 01 Jan 2026 00:00:00 GMT",
                    "x-amz-meta-owner" to "youndie",
                    "x-amz-meta-Mixed-Case" to "kept",
                ),
        )

        val got = s3.get(b, "a.txt")
        assertEquals("text/plain; charset=utf-8", got.header("Content-Type"))
        assertEquals("max-age=60", got.header("Cache-Control"))
        assertEquals("attachment; filename=\"a.txt\"", got.header("Content-Disposition"))
        assertEquals("ru", got.header("Content-Language"))
        assertEquals("Thu, 01 Jan 2026 00:00:00 GMT", got.header("Expires"))
        assertEquals("youndie", got.header("x-amz-meta-owner"))
        // Header names are case-insensitive, so this is the same entry however it was spelled.
        assertEquals("kept", got.header("x-amz-meta-mixed-case"))

        val head = s3.send("HEAD", "/$b/a.txt")
        assertEquals("youndie", head.header("x-amz-meta-owner"))
        assertEquals("text/plain; charset=utf-8", head.header("Content-Type"))
    }

    @Test
    fun `metadata survives a restart`() {
        val b = bucket()
        s3.put(b, "a.txt", "hello", headers = listOf("x-amz-meta-owner" to "youndie", "Cache-Control" to "no-store"))

        // Through the index log rather than the in-memory map: the record format has to carry it.
        val key =
            io.github.youndie.bochka.core.ObjectKey
                .of("a.txt")
        val before = s3.store.get(b, key)!!.metadata
        s3.store.close()
        val reopened =
            io.github.youndie.bochka.core
                .ObjectStore(s3.root)
        assertEquals(before, reopened.get(b, key)!!.metadata)
        reopened.close()
    }

    @Test
    fun `a body that does not match its stated checksum is refused and changes nothing`() {
        val b = bucket()
        s3.put(b, "a.txt", "original")

        // The CRC32C of the empty string, against a body that is not empty.
        val refused = s3.put(b, "a.txt", "replacement", headers = listOf("x-amz-checksum-crc32c" to "AAAAAA=="))
        assertEquals(400, refused.status)
        assertContains(refused.text, "BadDigest")

        // And the object that was there is still there. A refusal that destroyed it would be worse
        // than accepting the upload.
        assertEquals("original", s3.get(b, "a.txt").text)
    }

    @Test
    fun `a checksum comes back when it is asked for`() {
        val b = bucket()
        s3.put(b, "a.txt", "testcontent", headers = listOf("x-amz-checksum-crc32c" to "nekaYA=="))

        assertNull(s3.get(b, "a.txt").header("x-amz-checksum-crc32c"))
        val asked = s3.get(b, "a.txt", headers = listOf("x-amz-checksum-mode" to "ENABLED"))
        assertEquals("nekaYA==", asked.header("x-amz-checksum-crc32c"))

        // Not with a range. The stored value describes the whole object, and sending it beside a
        // slice of one says something false about the bytes that arrived — botocore checks it,
        // finds a different value, and reports the server as corrupt.
        val ranged =
            s3.get(b, "a.txt", headers = listOf("x-amz-checksum-mode" to "ENABLED", "Range" to "bytes=0-3"))
        assertEquals(206, ranged.status)
        assertNull(ranged.header("x-amz-checksum-crc32c"))
    }

    @Test
    fun `Content-MD5 is checked`() {
        val b = bucket()
        assertEquals(
            200,
            s3.put(b, "a.txt", "testcontent", headers = listOf("Content-MD5" to "KWq0kwKkNVPjI/uMtD/Neg==")).status,
        )
        val wrong = s3.put(b, "b.txt", "testcontent", headers = listOf("Content-MD5" to "1B2M2Y8AsgTpgAmY7PhCfg=="))
        assertEquals(400, wrong.status)
        assertContains(wrong.text, "BadDigest")
        assertEquals(404, s3.get(b, "b.txt").status)
    }

    @Test
    fun `an overwrite is atomic for a reader that started before it`() {
        // Р2 and M-44: the file on disk is never rewritten in place, so a reader holding the old
        // one reads the old one to the end. What proves it is that the bytes of the previous
        // version are still readable through a handle opened before the overwrite.
        val b = bucket()
        s3.put(b, "a.txt", "first version")
        val key =
            io.github.youndie.bochka.core.ObjectKey
                .of("a.txt")
        val old = s3.store.get(b, key)!!
        val handle =
            java.nio.channels.FileChannel
                .open(s3.store.pathOf(old))

        s3.put(b, "a.txt", "second version")
        assertEquals("second version", s3.get(b, "a.txt").text)

        val buffer = java.nio.ByteBuffer.allocate(old.size.toInt())
        handle.read(buffer, 0)
        handle.close()
        assertEquals("first version", String(buffer.array()))
    }

    @Test
    fun `the response headers can be replaced for one answer without touching the object`() {
        // M-79, and the task was written from a misread failure. `assert 'binary/octet-stream' ==
        // 'foo/bar'` looks like "the type set at upload came back as the default"; the type was
        // never set at upload, it was asked for in the query
        // (`shapes.GetObjectRequest.members.ResponseContentType` and its five siblings).
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "x", listOf("Content-Type" to "text/plain"))

        val overridden =
            s3.send(
                "GET",
                "/photos/a.txt",
                query =
                    "response-content-type=foo%2Fbar&response-content-disposition=bla" +
                        "&response-content-encoding=aaa&response-content-language=esperanto" +
                        "&response-cache-control=no-cache&response-expires=123",
            )
        assertEquals(200, overridden.status, overridden.text)
        assertEquals("foo/bar", overridden.header("Content-Type"))
        assertEquals("bla", overridden.header("Content-Disposition"))
        assertEquals("aaa", overridden.header("Content-Encoding"))
        assertEquals("esperanto", overridden.header("Content-Language"))
        assertEquals("no-cache", overridden.header("Cache-Control"))
        assertEquals("123", overridden.header("Expires"))

        // And the object keeps what it was stored with: the override is this answer only.
        assertEquals("text/plain", s3.get("photos", "a.txt").header("Content-Type"))
    }

    @Test
    fun `a replaced header appears once, not twice`() {
        // The stored value has to come **out** of the list rather than be appended after: two
        // `Content-Type` headers is a response whose meaning depends on which one the client reads
        // first, and they disagree.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "x", listOf("Content-Type" to "text/plain"))

        val answer = s3.send("GET", "/photos/a.txt", query = "response-content-type=foo%2Fbar")

        assertEquals(listOf("foo/bar"), answer.headers("Content-Type"))
    }
}
