package io.github.youndie.bochka.app

import java.util.Base64
import java.util.zip.CRC32
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The checksum of a multipart object, end to end (M-86, M-87).
 *
 * The value existed before this milestone; what did not was any way for a client to **use** it.
 * `s3-service-2.json` puts the algorithm and the type on four different responses —
 * `CreateMultipartUploadOutput.ChecksumAlgorithm` and `.ChecksumType` as headers,
 * `UploadPartOutput.ChecksumSHA256` as a header, `CompleteMultipartUploadOutput.ChecksumSHA256`
 * and `.ChecksumType` as elements, `GetObjectOutput.ChecksumType` as a header — and an SDK reads
 * every one of them. Seven cases of the suite fail on the first of those alone.
 *
 * The second half is `ChecksumType`, which is two computations rather than two labels: `COMPOSITE`
 * hashes the parts' checksums, `FULL_OBJECT` describes the object's own bytes. A client that
 * downloads the object and checksums it can only ever reproduce the second.
 */
class MultipartChecksumTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private val fiveMiB = 5 * 1024 * 1024

    private fun begin(
        headers: List<Pair<String, String>>,
        key: String = "big.bin",
    ): S3Fixture.Answer {
        s3.createBucket("photos")
        return s3.send("POST", "/photos/$key", query = "uploads", headers = headers)
    }

    private fun idOf(answer: S3Fixture.Answer) = Regex("<UploadId>(.*?)</UploadId>").find(answer.text)!!.groupValues[1]

    private fun crc32(bytes: ByteArray): String {
        val running = CRC32()
        running.update(bytes, 0, bytes.size)
        val value = running.value
        return Base64.getEncoder().encodeToString(ByteArray(4) { i -> (value ushr ((3 - i) * 8)).toByte() })
    }

    private fun field(
        body: String,
        name: String,
    ): String? = Regex("<$name>(.*?)</$name>").find(body)?.groupValues?.get(1)

    @Test
    fun `the algorithm and the type come back on the request that starts the upload`() {
        // `CreateMultipartUploadOutput` carries both as **headers**, not as elements of the
        // document. An SDK reads `response['ChecksumAlgorithm']` straight after this call and
        // fails with `KeyError` if it is not there — which is the first line of seven suite cases.
        val started = begin(listOf("x-amz-checksum-algorithm" to "CRC32", "x-amz-checksum-type" to "FULL_OBJECT"))
        assertEquals(200, started.status, started.text)
        assertEquals("CRC32", started.header("x-amz-checksum-algorithm"))
        assertEquals("FULL_OBJECT", started.header("x-amz-checksum-type"))
    }

    @Test
    fun `a part answers with the checksum it was given`() {
        val uploadId = idOf(begin(listOf("x-amz-checksum-algorithm" to "CRC32")))
        val body = ByteArray(fiveMiB) { 'A'.code.toByte() }

        val part =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                headers = listOf("x-amz-checksum-crc32" to crc32(body)),
                body = body,
            )

        assertEquals(200, part.status, part.text)
        assertEquals(crc32(body), part.header("x-amz-checksum-crc32"))
    }

    @Test
    fun `a FULL_OBJECT upload answers the checksum of the object's own bytes`() {
        // The reason `CrcCombine` exists. `COMPOSITE` would answer a hash of the parts' hashes
        // with `-2` after it, and a client that downloaded these ten mebibytes and ran CRC32 over
        // them would get something else — correctly, and with no way to tell which of the two of
        // us was wrong.
        val uploadId =
            idOf(begin(listOf("x-amz-checksum-algorithm" to "CRC32", "x-amz-checksum-type" to "FULL_OBJECT")))
        val first = ByteArray(fiveMiB) { 'A'.code.toByte() }
        val second = ByteArray(fiveMiB) { 'B'.code.toByte() }

        val a = uploadPart(uploadId, 1, first)
        val b = uploadPart(uploadId, 2, second)
        val done = complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        assertEquals(200, done.status, done.text)
        assertEquals("FULL_OBJECT", field(done.text, "ChecksumType"))
        assertEquals(crc32(first + second), field(done.text, "ChecksumCRC32"))
    }

    @Test
    fun `a COMPOSITE upload answers a checksum of checksums with the part count after it`() {
        val uploadId = idOf(begin(listOf("x-amz-checksum-algorithm" to "CRC32")))
        val first = ByteArray(fiveMiB) { 'A'.code.toByte() }
        val second = ByteArray(fiveMiB) { 'B'.code.toByte() }

        val a = uploadPart(uploadId, 1, first)
        val b = uploadPart(uploadId, 2, second)
        val done = complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        assertEquals("COMPOSITE", field(done.text, "ChecksumType"))
        assertContains(field(done.text, "ChecksumCRC32")!!, "-2")
    }

    @Test
    fun `the object answers with its checksum and its type when asked`() {
        val uploadId =
            idOf(begin(listOf("x-amz-checksum-algorithm" to "CRC32", "x-amz-checksum-type" to "FULL_OBJECT")))
        val first = ByteArray(fiveMiB) { 'A'.code.toByte() }
        val second = ByteArray(fiveMiB) { 'B'.code.toByte() }
        val a = uploadPart(uploadId, 1, first)
        val b = uploadPart(uploadId, 2, second)
        complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        val silent = s3.send("HEAD", "/photos/big.bin")
        assertNull(silent.header("x-amz-checksum-crc32"), "the checksum is only sent when it is asked for")

        val asked = s3.send("HEAD", "/photos/big.bin", headers = listOf("x-amz-checksum-mode" to "ENABLED"))
        assertEquals(crc32(first + second), asked.header("x-amz-checksum-crc32"))
        assertEquals("FULL_OBJECT", asked.header("x-amz-checksum-type"))
    }

    @Test
    fun `a part read back answers with that part's checksum, not the object's`() {
        // `partNumber=N` returns the bytes of one part, so the checksum beside it has to describe
        // those bytes. Sending the object's would be the same mistake as sending it beside a
        // `206`: a true statement about something the client did not receive.
        val uploadId = idOf(begin(listOf("x-amz-checksum-algorithm" to "CRC32")))
        val first = ByteArray(fiveMiB) { 'A'.code.toByte() }
        val second = ByteArray(fiveMiB) { 'B'.code.toByte() }
        val a = uploadPart(uploadId, 1, first)
        val b = uploadPart(uploadId, 2, second)
        complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        val part =
            s3.send(
                "GET",
                "/photos/big.bin",
                query = "partNumber=2",
                headers = listOf("x-amz-checksum-mode" to "ENABLED"),
            )
        assertEquals(206, part.status, part.text)
        assertEquals(crc32(second), part.header("x-amz-checksum-crc32"))
    }

    @Test
    fun `a HEAD takes partNumber too`() {
        // M-86: the router knew `partNumber` on a `GET` and not on a `HEAD`, so a client asking
        // how many parts an object has — which is what an SDK does before it starts a parallel
        // download — got the whole object's headers and no `x-amz-mp-parts-count` at all.
        val uploadId = idOf(begin(emptyList()))
        val first = ByteArray(fiveMiB) { 'A'.code.toByte() }
        val second = "the tail".toByteArray()
        val a = uploadPart(uploadId, 1, first)
        val b = uploadPart(uploadId, 2, second)
        complete(uploadId, listOf(1 to a.header("ETag")!!, 2 to b.header("ETag")!!))

        val head = s3.send("HEAD", "/photos/big.bin", query = "partNumber=1")
        assertEquals(206, head.status)
        assertEquals("2", head.header("x-amz-mp-parts-count"))
        assertEquals(fiveMiB.toString(), head.header("Content-Length"))
    }

    @Test
    fun `the parts of GetObjectAttributes carry their checksums and honour MaxParts`() {
        val uploadId = idOf(begin(listOf("x-amz-checksum-algorithm" to "CRC32")))
        val bodies = List(3) { index -> ByteArray(fiveMiB) { ('A' + index).code.toByte() } }
        val eTags =
            bodies.mapIndexed { index, body ->
                index + 1 to
                    uploadPart(uploadId, index + 1, body).header("ETag")!!
            }
        complete(uploadId, eTags)

        val all =
            s3.send(
                "GET",
                "/photos/big.bin",
                query = "attributes",
                headers = listOf("x-amz-object-attributes" to "ObjectParts,Checksum"),
            )
        assertEquals(200, all.status, all.text)
        assertContains(all.text, "<ChecksumCRC32>${crc32(bodies[0])}</ChecksumCRC32>")
        assertContains(all.text, "<PartsCount>3</PartsCount>")

        // `x-amz-max-parts` and `x-amz-part-number-marker` page this list, and `PartsCount` stays
        // the count of the whole object rather than of the page — the suite reads both off the
        // same document (`test_get_paginated_multipart_object_attributes`).
        val page =
            s3.send(
                "GET",
                "/photos/big.bin",
                query = "attributes",
                headers =
                    listOf(
                        "x-amz-object-attributes" to "ObjectParts",
                        "x-amz-max-parts" to "1",
                        "x-amz-part-number-marker" to "1",
                    ),
            )
        assertContains(page.text, "<PartsCount>3</PartsCount>")
        assertContains(page.text, "<MaxParts>1</MaxParts>")
        assertContains(page.text, "<PartNumberMarker>1</PartNumberMarker>")
        assertContains(page.text, "<NextPartNumberMarker>2</NextPartNumberMarker>")
        assertContains(page.text, "<IsTruncated>true</IsTruncated>")
        assertEquals(1, Regex("<Part>").findAll(page.text).count())
        assertContains(page.text, "<PartNumber>2</PartNumber>")
    }

    @Test
    fun `an upload keeps its algorithm and its parts' checksums across a restart`() {
        // The same rule the parts themselves are stored under: a client was told this upload is
        // CRC32 and `FULL_OBJECT`, and a restart that forgot it would complete into an object
        // whose checksum answers a question nobody asked — silently, and with the right shape.
        val uploadId =
            idOf(begin(listOf("x-amz-checksum-algorithm" to "CRC32", "x-amz-checksum-type" to "FULL_OBJECT")))
        val body = ByteArray(fiveMiB) { 'A'.code.toByte() }
        uploadPart(uploadId, 1, body)

        s3.store.close()
        val reopened =
            io.github.youndie.bochka.core
                .ObjectStore(s3.root)
        assertEquals("crc32", reopened.upload(uploadId)?.checksumAlgorithm)
        assertEquals("FULL_OBJECT", reopened.upload(uploadId)?.checksumType)
        assertEquals(
            crc32(body),
            reopened
                .parts(uploadId)
                .single()
                .checksum
                ?.value,
        )
        reopened.close()
    }

    @Test
    fun `a completion whose checksum does not match the parts is BadDigest`() {
        // The code, not just the refusal. `CompletionRefused.CHECKSUM_MISMATCH` maps to
        // `BadDigest`, and until this test nothing said so: changing that line to
        // `InvalidRequest` left the whole gate green. The two codes send a client to different
        // places - `BadDigest` says the bytes and the checksum disagree, which is a thing to
        // retry with a corrected value; `InvalidRequest` says the request was malformed, which
        // is a thing to fix in the client.
        //
        // The undecodable case, which the milestone names, is pinned twice over in
        // `PayloadChecksumsTest`; this is the other one - a value that decodes cleanly and
        // describes different bytes.
        val uploadId =
            idOf(begin(listOf("x-amz-checksum-algorithm" to "CRC32", "x-amz-checksum-type" to "FULL_OBJECT")))
        val first = ByteArray(fiveMiB) { 'A'.code.toByte() }
        val second = "the tail".toByteArray()
        val a = uploadPart(uploadId, 1, first)
        val b = uploadPart(uploadId, 2, second)

        val body =
            buildString {
                append("<CompleteMultipartUpload>")
                append("<Part><PartNumber>1</PartNumber><ETag>${a.header("ETag")}</ETag></Part>")
                append("<Part><PartNumber>2</PartNumber><ETag>${b.header("ETag")}</ETag></Part>")
                append("</CompleteMultipartUpload>")
            }.toByteArray()
        // A checksum of something else entirely, in the right shape and the right length.
        val refused =
            s3.send(
                "POST",
                "/photos/big.bin",
                query = "uploadId=$uploadId",
                headers = listOf("x-amz-checksum-crc32" to crc32("not these bytes".toByteArray())),
                body = body,
            )

        assertEquals(400, refused.status, refused.text)
        assertContains(refused.text, "BadDigest")
    }

    private fun uploadPart(
        uploadId: String,
        number: Int,
        content: ByteArray,
    ): S3Fixture.Answer =
        s3.send(
            "PUT",
            "/photos/big.bin",
            query = "partNumber=$number&uploadId=$uploadId",
            headers = listOf("x-amz-checksum-crc32" to crc32(content)),
            body = content,
        )

    private fun complete(
        uploadId: String,
        parts: List<Pair<Int, String>>,
    ): S3Fixture.Answer {
        val body =
            buildString {
                append("<CompleteMultipartUpload>")
                for ((number, eTag) in parts) append("<Part><PartNumber>$number</PartNumber><ETag>$eTag</ETag></Part>")
                append("</CompleteMultipartUpload>")
            }.toByteArray()
        return s3.send("POST", "/photos/big.bin", query = "uploadId=$uploadId", body = body)
    }
}
