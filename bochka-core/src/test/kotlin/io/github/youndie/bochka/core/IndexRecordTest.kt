package io.github.youndie.bochka.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IndexRecordTest {
    private fun roundTrip(record: IndexRecord) = assertEquals(record, IndexRecord.decode(IndexRecord.encode(record)))

    @Test
    fun `every record survives a round trip`() {
        roundTrip(IndexRecord.BucketCreated("photos"))
        roundTrip(IndexRecord.BucketDeleted("photos"))
        roundTrip(IndexRecord.Deleted("photos", ObjectKey.of("a/b.txt")))
        roundTrip(
            IndexRecord.Put(
                bucket = "photos",
                key = ObjectKey.of("a/b.txt"),
                fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e33",
                size = 1_234_567_890L,
                eTag = "\"d41d8cd98f00b204e9800998ecf8427e\"",
                lastModifiedMillis = 1_755_400_000_000L,
                metadata =
                    Metadata(
                        contentType = "text/plain; charset=utf-8",
                        cacheControl = "max-age=60",
                        contentDisposition = "attachment; filename=\"b.txt\"",
                        contentEncoding = "gzip",
                        contentLanguage = "ru",
                        expires = "Thu, 01 Jan 2026 00:00:00 GMT",
                        user = mapOf("x-amz-meta-owner" to "youndie", "x-amz-meta-empty" to ""),
                        checksum = Metadata.Checksum("crc32c", "AAAAAA=="),
                    ),
            ),
        )
        // An upload carrying a lock (M-175). The fields are new and have defaults, which is exactly
        // why the round trip has to be checked with non-empty ones: a record where everything is a
        // default passes even when it is never written at all.
        roundTrip(
            IndexRecord.UploadStarted(
                bucket = "photos",
                key = ObjectKey.of("big.bin"),
                uploadId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e33",
                startedAtMillis = 1_755_400_000_000L,
                metadata = Metadata(contentType = "application/octet-stream", tags = mapOf("k" to "v")),
                checksumAlgorithm = "crc32c",
                checksumType = "FULL_OBJECT",
                retentionMode = "COMPLIANCE",
                retentionUntilMillis = 1_900_000_000_000L,
                legalHold = true,
            ),
        )
        roundTrip(
            IndexRecord.UploadStarted(
                bucket = "photos",
                key = ObjectKey.of("plain.bin"),
                uploadId = "1f3c0d45-7b2e-4c8a-8d9f-3a6b2e1c8f44",
                startedAtMillis = 1_755_400_000_001L,
                metadata = Metadata(),
            ),
        )
    }

    @Test
    fun `an empty metadata value is a value, not an absence`() {
        // `x-amz-meta-x:` with nothing after the colon is a header S3 keeps and returns. Encoding
        // absence as an empty field would fold the two together, and the object would come back
        // without a header it was uploaded with.
        val record =
            IndexRecord.Put(
                bucket = "b",
                key = ObjectKey.of("k"),
                fileId = "id",
                size = 0,
                eTag = "\"e\"",
                lastModifiedMillis = 0,
                metadata = Metadata(contentType = "", user = mapOf("x-amz-meta-x" to "")),
            )
        val decoded = IndexRecord.decode(IndexRecord.encode(record)) as IndexRecord.Put
        assertEquals("", decoded.metadata.contentType)
        assertEquals(mapOf("x-amz-meta-x" to ""), decoded.metadata.user)
    }

    @Test
    fun `a key that is not text survives`() {
        // The reason this encoding is length-prefixed rather than delimited: a key can hold any
        // byte, including the ones a delimiter would be made of.
        val key = ObjectKey(byteArrayOf(0, 1, '\n'.code.toByte(), 0xC3.toByte(), 0x28, 0xFF.toByte()))
        roundTrip(IndexRecord.Deleted("b", key))
    }

    @Test
    fun `an absent content type stays absent`() {
        val record =
            IndexRecord.Put(
                bucket = "b",
                key = ObjectKey.of("k"),
                fileId = "id",
                size = 0,
                eTag = "\"e\"",
                lastModifiedMillis = 0,
                metadata = Metadata.EMPTY,
            )
        val decoded = IndexRecord.decode(IndexRecord.encode(record)) as IndexRecord.Put
        assertEquals(Metadata.EMPTY, decoded.metadata)
    }

    @Test
    fun `fields come back in the order they went in`() {
        // Named arguments in a constructor call are a bad place to decode a stream from: what fixes
        // the read order is the order the calls are written, and that is easy to change by
        // accident when the parameter list is reordered. Distinct values catch a swap that equal
        // ones would hide.
        val decoded =
            IndexRecord.decode(
                IndexRecord.encode(
                    IndexRecord.Put(
                        bucket = "bucket-name",
                        key = ObjectKey.of("key-name"),
                        fileId = "file-id",
                        size = 111,
                        eTag = "etag-value",
                        lastModifiedMillis = 222,
                        metadata = Metadata(contentType = "content-type"),
                    ),
                ),
            ) as IndexRecord.Put

        assertEquals("bucket-name", decoded.bucket)
        assertEquals("key-name", decoded.key.toString())
        assertEquals("file-id", decoded.fileId)
        assertEquals(111, decoded.size)
        assertEquals("etag-value", decoded.eTag)
        assertEquals(222, decoded.lastModifiedMillis)
        assertEquals("content-type", decoded.metadata.contentType)
    }

    @Test
    fun `an encrypted version keeps its algorithm, its key md5 and its iv`() {
        // M-186. The key is not among them and cannot be: a server that keeps it erases the whole
        // difference between encrypting under the client's key and under its own.
        val record =
            IndexRecord.Put(
                bucket = "photos",
                key = ObjectKey.of("secret.txt"),
                fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e33",
                size = 1000,
                eTag = "\"d41d8cd98f00b204e9800998ecf8427e\"",
                lastModifiedMillis = 1_755_400_000_000L,
                metadata = Metadata(contentType = "text/plain"),
                encryptionAlgorithm = "AES256",
                encryptionKeyMd5 = "DWygnHRtgiJ77HCm+1rvHw==",
                encryptionIv = ByteArray(16) { it.toByte() },
            )

        val back = IndexRecord.decode(IndexRecord.encode(record)) as IndexRecord.Put

        assertEquals("AES256", back.encryptionAlgorithm)
        assertEquals("DWygnHRtgiJ77HCm+1rvHw==", back.encryptionKeyMd5)
        assertContentEquals(ByteArray(16) { it.toByte() }, back.encryptionIv)
    }

    @Test
    fun `a version written before encryption existed decodes as unencrypted`() {
        // The rule of this file: a record already written has to decode into exactly what it meant.
        // "Not encrypted" is a truth about it rather than a default somebody supplied.
        val record =
            IndexRecord.Put(
                bucket = "photos",
                key = ObjectKey.of("plain.txt"),
                fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e34",
                size = 5,
                eTag = "\"x\"",
                lastModifiedMillis = 1_755_400_000_000L,
                metadata = Metadata(contentType = "text/plain"),
            )

        val back = IndexRecord.decode(IndexRecord.encode(record)) as IndexRecord.Put

        assertNull(back.encryptionAlgorithm)
        assertNull(back.encryptionIv)
    }

    @Test
    fun `an owned version keeps who wrote it and how it is shared`() {
        // M-192. The owner is the access key that wrote the version, and the ACL is a canned name
        // rather than a list of grants: a grant names a user, and this server has keys.
        val record =
            IndexRecord.Put(
                bucket = "photos",
                key = ObjectKey.of("holiday.jpg"),
                fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e35",
                size = 10,
                eTag = "\"x\"",
                lastModifiedMillis = 1_755_400_000_000L,
                metadata = Metadata(contentType = "image/jpeg"),
                owner = "s3main",
                acl = "public-read",
            )

        val back = IndexRecord.decode(IndexRecord.encode(record)) as IndexRecord.Put

        assertEquals("s3main", back.owner)
        assertEquals("public-read", back.acl)
    }

    @Test
    fun `an owned version can also be encrypted`() {
        // The two newest fields of a version arrived in different milestones, and a record has to
        // carry both at once or the second one silently costs the first.
        val record =
            IndexRecord.Put(
                bucket = "photos",
                key = ObjectKey.of("secret.jpg"),
                fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e36",
                size = 10,
                eTag = "\"x\"",
                lastModifiedMillis = 1_755_400_000_000L,
                metadata = Metadata(contentType = "image/jpeg"),
                encryptionAlgorithm = "AES256",
                encryptionKeyMd5 = "DWygnHRtgiJ77HCm+1rvHw==",
                encryptionIv = ByteArray(16) { it.toByte() },
                owner = "s3alt",
                acl = "private",
            )

        val back = IndexRecord.decode(IndexRecord.encode(record)) as IndexRecord.Put

        assertEquals("s3alt", back.owner)
        assertEquals("private", back.acl)
        assertEquals("AES256", back.encryptionAlgorithm)
        assertContentEquals(ByteArray(16) { it.toByte() }, back.encryptionIv)
    }

    @Test
    fun `a version written before owners existed has no owner`() {
        // The other half of the rule: an old record means exactly what it meant. A version with
        // no owner is not owned by the process that happens to read it.
        val record =
            IndexRecord.Put(
                bucket = "photos",
                key = ObjectKey.of("old.txt"),
                fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e37",
                size = 5,
                eTag = "\"x\"",
                lastModifiedMillis = 1_755_400_000_000L,
                metadata = Metadata(contentType = "text/plain"),
            )

        val back = IndexRecord.decode(IndexRecord.encode(record)) as IndexRecord.Put

        assertNull(back.owner)
        assertNull(back.acl)
    }

    @Test
    fun `a bucket remembers who created it, and its acl is a record of its own`() {
        roundTrip(IndexRecord.BucketCreated("photos", 1_755_400_000_000L, owner = "s3main", acl = "private"))
        // Separate from creation because it changes after creation, like versioning: `PutBucketAcl`
        // rewriting the creation record would have to carry a creation time it did not witness.
        roundTrip(IndexRecord.BucketAcl("photos", "public-read"))
    }

    @Test
    fun `a bucket created before owners existed has none`() {
        val back =
            IndexRecord.decode(IndexRecord.encode(IndexRecord.BucketCreated("photos", 1L))) as
                IndexRecord.BucketCreated

        assertNull(back.owner)
        assertNull(back.acl)
    }

    @Test
    fun `a record from a newer version is refused rather than misread`() {
        assertFailsWith<IllegalArgumentException> { IndexRecord.decode(byteArrayOf(99, 0, 0, 0, 0)) }
    }
}
