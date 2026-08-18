package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a completed multipart object remembers about how it was made (M-81, M-82, M-83).
 *
 * The bytes are joined at completion — that was decided by measurement (open question 3) — so the
 * only thing that knows where the seams were is the index. Three operations need them, and none of
 * them can be answered by looking at the assembled file.
 */
class MultipartAttributesTest {
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

    private fun field(
        body: String,
        name: String,
    ): String? = Regex("<$name>(.*?)</$name>").find(body)?.groupValues?.get(1)

    @Test
    fun `partNumber on a GET reads the part that arrived under that number`() {
        val uploadId = begin()
        val first = ByteArray(fiveMiB) { 'a'.code.toByte() }
        val second = "the tail".toByteArray()
        val a = s3.send("PUT", "/photos/big.bin", query = "partNumber=1&uploadId=$uploadId", body = first)
        val b = s3.send("PUT", "/photos/big.bin", query = "partNumber=2&uploadId=$uploadId", body = second)
        complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        val tail = s3.send("GET", "/photos/big.bin", query = "partNumber=2")
        assertEquals(206, tail.status, tail.text)
        assertContentEquals(second, tail.body)
        assertEquals("2", tail.header("x-amz-mp-parts-count"))
        assertEquals(
            "bytes $fiveMiB-${fiveMiB + second.size - 1}/${fiveMiB + second.size}",
            tail.header("Content-Range"),
        )

        // A part number the object does not have is not an empty answer.
        assertEquals(400, s3.send("GET", "/photos/big.bin", query = "partNumber=3").status)
    }

    @Test
    fun `the parts survive a restart, because only the index remembers them`() {
        val uploadId = begin()
        val a = s3.send("PUT", "/photos/big.bin", query = "partNumber=1&uploadId=$uploadId", body = ByteArray(fiveMiB))
        val b =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=2&uploadId=$uploadId",
                body = "tail".toByteArray(),
            )
        complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        val key =
            io.github.youndie.bochka.core.ObjectKey
                .of("big.bin")
        val before = s3.store.get("photos", key)!!.parts
        assertEquals(listOf(1, 2), before.map { it.number })
        s3.store.close()

        io.github.youndie.bochka.core.ObjectStore(s3.root).use { reopened ->
            assertEquals(before, reopened.get("photos", key)!!.parts, "the seams are in the log or they are nowhere")
        }
    }

    @Test
    fun `GetObjectAttributes answers only what was asked for`() {
        val uploadId = begin()
        val a = s3.send("PUT", "/photos/big.bin", query = "partNumber=1&uploadId=$uploadId", body = ByteArray(fiveMiB))
        val b =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=2&uploadId=$uploadId",
                body = "tail".toByteArray(),
            )
        complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        val everything =
            s3.send(
                "GET",
                "/photos/big.bin",
                query = "attributes",
                headers = listOf("x-amz-object-attributes" to "ETag,ObjectSize,StorageClass,ObjectParts"),
            )
        assertEquals(200, everything.status, everything.text)
        assertContains(everything.text, "<GetObjectAttributesOutput")
        assertEquals((fiveMiB + 4).toString(), field(everything.text, "ObjectSize"))
        assertEquals("2", field(everything.text, "PartsCount"))
        assertEquals("STANDARD", field(everything.text, "StorageClass"))
        // The ETag is the one member S3 sends without its quotes.
        assertTrue(field(everything.text, "ETag")!!.endsWith("-2"), everything.text)

        // Only what was named: a ten-thousand-part object would otherwise answer a megabyte to a
        // client that asked for its size.
        val justSize =
            s3.send(
                "GET",
                "/photos/big.bin",
                query = "attributes",
                headers = listOf("x-amz-object-attributes" to "ObjectSize"),
            )
        assertEquals(null, field(justSize.text, "PartsCount"))
        assertEquals(null, field(justSize.text, "ETag"))
        assertEquals((fiveMiB + 4).toString(), field(justSize.text, "ObjectSize"))
    }

    @Test
    fun `an ordinary object has no parts and says so`() {
        s3.createBucket("photos")
        s3.put("photos", "plain.txt", "hello")

        val attributes =
            s3.send(
                "GET",
                "/photos/plain.txt",
                query = "attributes",
                headers = listOf("x-amz-object-attributes" to "ObjectParts,ObjectSize"),
            )
        // Absent, not empty: a client reads the absence of `ObjectParts` as "this was not a
        // multipart upload", and `<PartsCount>0</PartsCount>` says something different and wrong.
        assertEquals(null, field(attributes.text, "PartsCount"))
        assertEquals("5", field(attributes.text, "ObjectSize"))

        // And `partNumber=1` on an object that was never assembled is the object: one part, the
        // whole thing. It is what lets a client use one download loop for both kinds.
        val onlyPart = s3.send("GET", "/photos/plain.txt", query = "partNumber=1")
        assertEquals(206, onlyPart.status, onlyPart.text)
        assertEquals("hello", onlyPart.text)
        assertEquals("1", onlyPart.header("x-amz-mp-parts-count"))
        assertEquals(400, s3.send("GET", "/photos/plain.txt", query = "partNumber=2").status)
    }

    @Test
    fun `the checksum of an assembled object is a checksum of its parts' checksums`() {
        // M-83. The object's bytes never went through one hash, so a value shaped like an ordinary
        // checksum would be one no client could reproduce; the `-N` says which kind it is.
        val uploadId = begin()
        val first = ByteArray(fiveMiB)
        val a =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                headers = listOf("x-amz-checksum-crc32c" to crc32cOf(first)),
                body = first,
            )
        val second = "tail".toByteArray()
        val b =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=2&uploadId=$uploadId",
                headers = listOf("x-amz-checksum-crc32c" to crc32cOf(second)),
                body = second,
            )
        assertEquals(200, a.status, a.text)
        assertEquals(200, b.status, b.text)
        complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        val asked = s3.send("GET", "/photos/big.bin", headers = listOf("x-amz-checksum-mode" to "ENABLED"))
        val composite = asked.header("x-amz-checksum-crc32c")
        assertTrue(composite != null && composite.endsWith("-2"), "expected a composite checksum, got $composite")

        // And it is the checksum of the parts' **raw** checksums, not of the object's bytes.
        // Computed here rather than by calling the server's own function with the same arguments:
        // an expectation built out of the code under test agrees with it however wrong both are.
        val raw =
            java.util.Base64
                .getDecoder()
                .decode(crc32cOf(first)) +
                java.util.Base64
                    .getDecoder()
                    .decode(crc32cOf(second))
        val running = java.util.zip.CRC32C()
        running.update(raw, 0, raw.size)
        val expected =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(4) { i -> (running.value ushr ((3 - i) * 8)).toByte() }) + "-2"
        assertEquals(expected, composite)

        val attributes =
            s3.send(
                "GET",
                "/photos/big.bin",
                query = "attributes",
                headers = listOf("x-amz-object-attributes" to "Checksum"),
            )
        assertEquals("COMPOSITE", field(attributes.text, "ChecksumType"))
        assertEquals(composite, field(attributes.text, "ChecksumCRC32C"))
    }

    @Test
    fun `a part can be copied out of another object`() {
        // M-81, and the reason it exists: a client rewriting a large object copies the parts it
        // keeps instead of downloading and re-uploading them.
        s3.createBucket("photos")
        val source = ByteArray(fiveMiB + 16) { (it % 251).toByte() }
        s3.put("photos", "source.bin", source)

        val uploadId = begin("assembled.bin")
        val copied =
            s3.send(
                "PUT",
                "/photos/assembled.bin",
                query = "partNumber=1&uploadId=$uploadId",
                headers =
                    listOf(
                        "x-amz-copy-source" to "/photos/source.bin",
                        "x-amz-copy-source-range" to "bytes=0-${fiveMiB - 1}",
                    ),
            )
        assertEquals(200, copied.status, copied.text)
        assertContains(copied.text, "<CopyPartResult")
        val partETag = field(copied.text, "ETag")!!.replace("&quot;", "\"")

        val tail =
            s3.send(
                "PUT",
                "/photos/assembled.bin",
                query = "partNumber=2&uploadId=$uploadId",
                body = "end".toByteArray(),
            )
        assertEquals(
            200,
            complete(uploadId, listOf(1 to partETag, 2 to tail.header("ETag")!!), key = "assembled.bin").status,
        )

        val got = s3.get("photos", "assembled.bin")
        assertContentEquals(source.copyOfRange(0, fiveMiB) + "end".toByteArray(), got.body)
    }

    @Test
    fun `a copy-source range that does not resolve is refused rather than guessed`() {
        // The one place a malformed range is an error: on a GET it means "send everything"
        // (RFC 9110 §14.2), but here it decides what the part *is*.
        s3.createBucket("photos")
        s3.put("photos", "source.bin", "short")
        val uploadId = begin("assembled.bin")

        // Two refusals, not one, and the split is the point (M-86). A header that does not parse
        // is a bad **argument**; one that parses and names bytes this object does not have is a
        // bad **range**, and the two send the client to look in different places. The suite pins
        // the second (`test_multipart_copy_invalid_range`, source of five bytes, `bytes=0-21`).
        for (range in listOf("bytes=abc-def", "bytes=0-1,3-4")) {
            val refused = copyRange(uploadId, range)
            assertEquals(400, refused.status, "range '$range' should be refused, got ${refused.text}")
            assertContains(refused.text, "InvalidArgument")
        }
        for (range in listOf("bytes=100-200", "bytes=0-21")) {
            val refused = copyRange(uploadId, range)
            assertEquals(416, refused.status, "range '$range' should be refused, got ${refused.text}")
            assertContains(refused.text, "InvalidRange")
        }

        // With no range at all the whole source is the part.
        val whole =
            s3.send(
                "PUT",
                "/photos/assembled.bin",
                query = "partNumber=1&uploadId=$uploadId",
                headers = listOf("x-amz-copy-source" to "/photos/source.bin"),
            )
        assertEquals(200, whole.status, whole.text)
    }

    /** The vectors are generated elsewhere; this is the same arithmetic the fixture needs inline. */
    private fun crc32cOf(bytes: ByteArray): String {
        val crc = java.util.zip.CRC32C()
        crc.update(bytes, 0, bytes.size)
        val value = crc.value
        return java.util.Base64
            .getEncoder()
            .encodeToString(
                byteArrayOf(
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte(),
                ),
            )
    }

    private fun copyRange(
        uploadId: String,
        range: String,
    ): S3Fixture.Answer =
        s3.send(
            "PUT",
            "/photos/assembled.bin",
            query = "partNumber=1&uploadId=$uploadId",
            headers = listOf("x-amz-copy-source" to "/photos/source.bin", "x-amz-copy-source-range" to range),
        )
}
