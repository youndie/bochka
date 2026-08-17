package io.github.youndie.bochka.s3.xml

import io.github.youndie.bochka.core.ObjectKey
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Roots and namespaces: `minio/minio`, `cmd/api-response.go:102,178,226,403,412,436`, and
 * `cmd/api-errors.go:64` for `<Error>`. Members and flattening: `docs/spec/s3-service-2.json`,
 * `shapes.*.members`.
 */
class S3DocumentsTest {
    private fun ByteArray.asText(): String = toString(StandardCharsets.UTF_8)

    @Test
    fun `a result document carries the s3 namespace and the error document does not`() {
        val listing = S3Documents.listAllMyBucketsResult(emptyList(), "bochka", "bochka").asText()
        assertTrue(
            listing.startsWith(
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<ListAllMyBucketsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""",
            ),
            listing,
        )

        val error = S3Documents.error("NoSuchKey", "The specified key does not exist.", "/b/k", "req-1").asText()
        assertTrue(error.contains("<Error><Code>NoSuchKey</Code>"), error)
        assertFalse(error.contains("xmlns"), "a namespaced <Error> is one a client will not recognise")
    }

    @Test
    fun `an error always carries the request id pair`() {
        val error = S3Documents.error("AccessDenied", "Access Denied.", "/b", "req-7", hostId = "host-9").asText()
        assertTrue(error.contains("<RequestId>req-7</RequestId>"), error)
        assertTrue(error.contains("<HostId>host-9</HostId>"), error)
    }

    @Test
    fun `buckets are wrapped and listing entries are not`() {
        // `shapes.ListBucketsOutput` wraps; `shapes.ListObjectsV2Output.Contents` is flattened.
        // Getting this backwards produces a document that parses and lists nothing.
        val buckets =
            S3Documents
                .listAllMyBucketsResult(
                    listOf(
                        S3Documents.BucketEntry("photos", "2026-08-17T10:00:00.000Z"),
                        S3Documents.BucketEntry("logs", "2026-08-17T10:00:01.000Z"),
                    ),
                    "bochka",
                    "bochka",
                ).asText()
        assertTrue(buckets.contains("<Buckets><Bucket><Name>photos</Name>"), buckets)

        val listing =
            S3Documents
                .listBucketResult(
                    bucket = "photos",
                    prefix = null,
                    delimiter = null,
                    maxKeys = 1000,
                    keyCount = 2,
                    isTruncated = false,
                    contents =
                        listOf(
                            entry("a.txt"),
                            entry("b.txt"),
                        ),
                    commonPrefixes = emptyList(),
                    encoding = S3Documents.KeyEncoding.NONE,
                ).asText()
        assertTrue(listing.contains("<Contents><Key>a.txt</Key>"), listing)
        assertTrue(listing.contains("</Contents><Contents><Key>b.txt</Key>"), listing)
        assertFalse(listing.contains("<Contents><Contents>"), "Contents must be flattened")
    }

    @Test
    fun `with encoding-type=url a space becomes a plus`() {
        val listing =
            S3Documents
                .listBucketResult(
                    bucket = "photos",
                    prefix = "my dir/",
                    delimiter = "/",
                    maxKeys = 1000,
                    keyCount = 1,
                    isTruncated = false,
                    contents = listOf(entry("my dir/file.txt")),
                    commonPrefixes = listOf("my dir/sub/".toByteArray()),
                    encoding = S3Documents.KeyEncoding.URL,
                ).asText()

        assertTrue(listing.contains("<Key>my+dir/file.txt</Key>"), listing)
        assertTrue(listing.contains("<Prefix>my+dir/</Prefix>"), listing)
        assertTrue(listing.contains("<CommonPrefixes><Prefix>my+dir/sub/</Prefix></CommonPrefixes>"), listing)
        assertTrue(listing.contains("<EncodingType>url</EncodingType>"), listing)
    }

    @Test
    fun `without encoding the key goes out byte for byte`() {
        // The reason the writer takes bytes: 0xC3 0x28 is not valid UTF-8, and a writer that went
        // through String would emit a replacement character — a different key, quietly.
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        val listing =
            S3Documents.listBucketResult(
                bucket = "b",
                prefix = null,
                delimiter = null,
                maxKeys = 1000,
                keyCount = 1,
                isTruncated = false,
                contents = listOf(S3Documents.ObjectEntry(ObjectKey(invalid), NOW, "\"e\"", 1)),
                commonPrefixes = emptyList(),
                encoding = S3Documents.KeyEncoding.NONE,
            )

        val marker = "<Key>".toByteArray()
        val at = listing.indexOfSlice(marker) + marker.size
        assertContentEquals(invalid, listing.copyOfRange(at, at + invalid.size))
    }

    @Test
    fun `the five entities are escaped and control bytes are not`() {
        val key = ObjectKey("a&b<c>d\"e'f".toByteArray() + byteArrayOf(1))
        val listing =
            S3Documents.listBucketResult(
                bucket = "b",
                prefix = null,
                delimiter = null,
                maxKeys = 1000,
                keyCount = 1,
                isTruncated = false,
                contents = listOf(S3Documents.ObjectEntry(key, NOW, "\"e\"", 1)),
                commonPrefixes = emptyList(),
                encoding = S3Documents.KeyEncoding.NONE,
            )

        val text = listing.asText()
        assertTrue(text.contains("<Key>a&amp;b&lt;c&gt;d&quot;e&apos;f"), text)
        // 0x01 stays. The model's answer to keys like this is encoding-type=url, not a server that
        // rewrites the key (`shapes.EncodingType`), so the document is left broken on purpose.
        assertTrue(listing.contains(1.toByte()), "the control byte must survive")
    }

    @Test
    fun `an absent member is left out rather than written empty`() {
        val listing =
            S3Documents
                .listBucketResult(
                    bucket = "b",
                    prefix = null,
                    delimiter = null,
                    maxKeys = 1000,
                    keyCount = 0,
                    isTruncated = false,
                    contents = emptyList(),
                    commonPrefixes = emptyList(),
                    encoding = S3Documents.KeyEncoding.NONE,
                ).asText()

        assertFalse(listing.contains("<Delimiter>"), listing)
        assertFalse(listing.contains("<NextContinuationToken>"), listing)
        // Prefix is the exception: always present, empty when not asked for.
        assertTrue(listing.contains("<Prefix></Prefix>"), listing)
    }

    @Test
    fun `multipart documents name the elements the client looks for`() {
        val key = ObjectKey.of("big.bin")

        val created = S3Documents.initiateMultipartUploadResult("photos", key, "upload-1").asText()
        assertTrue(created.contains("<Bucket>photos</Bucket><Key>big.bin</Key><UploadId>upload-1</UploadId>"), created)

        val completed =
            S3Documents
                .completeMultipartUploadResult(
                    "http://localhost:9000/photos/big.bin",
                    "photos",
                    key,
                    "\"abc-2\"",
                ).asText()
        assertTrue(completed.contains("<ETag>&quot;abc-2&quot;</ETag>"), completed)

        val parts =
            S3Documents
                .listPartsResult(
                    bucket = "photos",
                    key = key,
                    uploadId = "upload-1",
                    partNumberMarker = 0,
                    nextPartNumberMarker = 2,
                    maxParts = 1000,
                    isTruncated = false,
                    parts = listOf(S3Documents.PartEntry(1, NOW, "\"p1\"", 5 * 1024 * 1024)),
                ).asText()
        assertTrue(parts.contains("<Part><PartNumber>1</PartNumber>"), parts)
    }

    @Test
    fun `a delete result reports successes and failures side by side`() {
        val result =
            S3Documents
                .deleteResult(
                    deleted = listOf(S3Documents.DeletedEntry(ObjectKey.of("gone.txt"))),
                    errors =
                        listOf(
                            S3Documents.DeleteError(ObjectKey.of("kept.txt"), "AccessDenied", "Access Denied."),
                        ),
                ).asText()

        assertTrue(result.contains("<Deleted><Key>gone.txt</Key></Deleted>"), result)
        assertTrue(result.contains("<Error><Key>kept.txt</Key><Code>AccessDenied</Code>"), result)
    }

    private fun entry(key: String) = S3Documents.ObjectEntry(ObjectKey.of(key), NOW, "\"d41d8c\"", 11)

    private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
        outer@ for (i in 0..size - slice.size) {
            for (j in slice.indices) if (this[i + j] != slice[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val NOW = "2026-08-17T10:00:00.000Z"
    }
}
