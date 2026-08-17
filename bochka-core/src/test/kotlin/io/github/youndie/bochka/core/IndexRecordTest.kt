package io.github.youndie.bochka.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `a record from a newer version is refused rather than misread`() {
        assertFailsWith<IllegalArgumentException> { IndexRecord.decode(byteArrayOf(99, 0, 0, 0, 0)) }
    }
}
