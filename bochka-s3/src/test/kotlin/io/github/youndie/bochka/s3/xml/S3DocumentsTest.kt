package io.github.youndie.bochka.s3.xml

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.s3.CorsRules
import io.github.youndie.bochka.s3.Lifecycle
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Roots and namespaces: `minio/minio`, `cmd/api-response.go:102,178,226,403,412,436`, and
 * `cmd/api-errors.go:64` for `<Error>`. Members and flattening: `docs/spec/s3-service-2.json`,
 * `shapes.*.members`.
 *
 * **Every document is compared whole, and compared as a parse** ([XmlTree]). What stood here
 * before read the documents as characters — twenty-two `assertTrue(xml.contains(…))` against a
 * single `assertEquals` — and a substring cannot see what the root element declares, cannot see an
 * element written twice, and cannot see anything the builder emits beside the fragment being
 * matched. The cost of that is on the record: `<PostResponse>` went out carrying a namespace, the
 * test here stayed green, and the foreign case failed, because a client reads the answer as XML
 * and the test read it as text.
 *
 * The expected forms below are written from the model and the reference server rather than from
 * this code's output. A document copied out of a run and pasted back in agrees with whatever the
 * run did, including the parts of it that are wrong.
 */
class S3DocumentsTest {
    private fun canonical(document: ByteArray) = XmlTree.canonical(document)

    @Test
    fun `an error carries no namespace, and a result document carries the s3 one`() {
        // `<Error>` unnamespaced is not a detail: a client matching on a namespaced root would
        // never recognise it (`cmd/api-errors.go:64-76`).
        assertEquals(
            """
            Error @ no namespace
            Error/Code = NoSuchKey
            Error/Message = The specified key does not exist.
            Error/Resource = /b/k
            Error/RequestId = req-1
            Error/HostId =
            """.trimIndent(),
            canonical(S3Documents.error("NoSuchKey", "The specified key does not exist.", "/b/k", "req-1")),
        )

        assertEquals(
            """
            ListAllMyBucketsResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListAllMyBucketsResult/Owner/ID = bochka
            ListAllMyBucketsResult/Owner/DisplayName = bochka
            ListAllMyBucketsResult/Buckets =
            """.trimIndent(),
            canonical(S3Documents.listAllMyBucketsResult(emptyList(), "bochka", "bochka")),
        )
    }

    @Test
    fun `an error names the object and the bucket when it has them`() {
        // The `RequestId`/`HostId` pair goes out unconditionally: without it AWS support will not
        // look at a report, so client libraries carry the pair into their exceptions regardless.
        assertEquals(
            """
            Error @ no namespace
            Error/Code = AccessDenied
            Error/Message = Access Denied.
            Error/Key = k.txt
            Error/BucketName = photos
            Error/Resource = /photos/k.txt
            Error/RequestId = req-7
            Error/HostId = host-9
            """.trimIndent(),
            canonical(
                S3Documents.error(
                    code = "AccessDenied",
                    message = "Access Denied.",
                    resource = "/photos/k.txt",
                    requestId = "req-7",
                    hostId = "host-9",
                    key = ObjectKey.of("k.txt"),
                    bucketName = "photos",
                ),
            ),
        )
    }

    @Test
    fun `buckets are wrapped in Buckets and listing entries are not wrapped at all`() {
        // `shapes.ListBucketsOutput` wraps; `shapes.ListObjectsV2Output.Contents` is flattened.
        // Getting either backwards produces a document that parses and lists nothing.
        assertEquals(
            """
            ListAllMyBucketsResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListAllMyBucketsResult/Owner/ID = bochka
            ListAllMyBucketsResult/Owner/DisplayName = bochka display
            ListAllMyBucketsResult/Buckets/Bucket[1]/Name = photos
            ListAllMyBucketsResult/Buckets/Bucket[1]/CreationDate = 2026-08-17T10:00:00.000Z
            ListAllMyBucketsResult/Buckets/Bucket[2]/Name = logs
            ListAllMyBucketsResult/Buckets/Bucket[2]/CreationDate = 2026-08-17T10:00:01.000Z
            ListAllMyBucketsResult/ContinuationToken = next-page
            ListAllMyBucketsResult/Prefix = pho
            """.trimIndent(),
            canonical(
                S3Documents.listAllMyBucketsResult(
                    buckets =
                        listOf(
                            S3Documents.BucketEntry("photos", "2026-08-17T10:00:00.000Z"),
                            S3Documents.BucketEntry("logs", "2026-08-17T10:00:01.000Z"),
                        ),
                    ownerId = "bochka",
                    ownerDisplayName = "bochka display",
                    // The member is called `ContinuationToken` on the way out too, and it is the
                    // **next** one rather than an echo (`shapes.ListBucketsOutput.members`).
                    nextContinuationToken = "next-page",
                    prefix = "pho",
                ),
            ),
        )

        assertEquals(
            """
            ListBucketResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListBucketResult/Name = photos
            ListBucketResult/Prefix =
            ListBucketResult/MaxKeys = 1000
            ListBucketResult/KeyCount = 2
            ListBucketResult/IsTruncated = false
            ListBucketResult/Contents[1]/Key = a.txt
            ListBucketResult/Contents[1]/LastModified = $NOW
            ListBucketResult/Contents[1]/ETag = "d41d8c"
            ListBucketResult/Contents[1]/Size = 11
            ListBucketResult/Contents[1]/StorageClass = STANDARD
            ListBucketResult/Contents[2]/Key = b.txt
            ListBucketResult/Contents[2]/LastModified = $NOW
            ListBucketResult/Contents[2]/ETag = "d41d8c"
            ListBucketResult/Contents[2]/Size = 11
            ListBucketResult/Contents[2]/StorageClass = STANDARD
            """.trimIndent(),
            canonical(listing(contents = listOf(entry("a.txt"), entry("b.txt")), keyCount = 2)),
        )
    }

    @Test
    fun `the owner of a version rides on the entry, and only when it was asked for`() {
        // `shapes.ListObjectsV2Request` has `fetch-owner` precisely because the owner is not sent
        // by default; per entry rather than per page, because a bucket open for writing holds
        // objects belonging to more than one key (M27).
        assertEquals(
            """
            ListBucketResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListBucketResult/Name = photos
            ListBucketResult/Prefix =
            ListBucketResult/MaxKeys = 1000
            ListBucketResult/KeyCount = 2
            ListBucketResult/IsTruncated = false
            ListBucketResult/Contents[1]/Key = mine.txt
            ListBucketResult/Contents[1]/LastModified = $NOW
            ListBucketResult/Contents[1]/ETag = "d41d8c"
            ListBucketResult/Contents[1]/Size = 11
            ListBucketResult/Contents[1]/StorageClass = STANDARD
            ListBucketResult/Contents[1]/Owner/ID = asker
            ListBucketResult/Contents[1]/Owner/DisplayName = asker
            ListBucketResult/Contents[2]/Key = theirs.txt
            ListBucketResult/Contents[2]/LastModified = $NOW
            ListBucketResult/Contents[2]/ETag = "d41d8c"
            ListBucketResult/Contents[2]/Size = 11
            ListBucketResult/Contents[2]/StorageClass = STANDARD
            ListBucketResult/Contents[2]/Owner/ID = somebody-else
            ListBucketResult/Contents[2]/Owner/DisplayName = somebody-else
            """.trimIndent(),
            canonical(
                listing(
                    contents =
                        listOf(
                            entry("mine.txt"),
                            entry("theirs.txt").copy(owner = "somebody-else"),
                        ),
                    keyCount = 2,
                    owner = "asker",
                ),
            ),
        )
    }

    @Test
    fun `with encoding-type=url the encoding is stated and every key member is encoded`() {
        assertEquals(
            """
            ListBucketResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListBucketResult/Name = photos
            ListBucketResult/Prefix = my%20dir/
            ListBucketResult/Delimiter = /
            ListBucketResult/MaxKeys = 1000
            ListBucketResult/KeyCount = 1
            ListBucketResult/IsTruncated = false
            ListBucketResult/EncodingType = url
            ListBucketResult/StartAfter = my%20dir/a.txt
            ListBucketResult/Contents/Key = my%20dir/file.txt
            ListBucketResult/Contents/LastModified = $NOW
            ListBucketResult/Contents/ETag = "d41d8c"
            ListBucketResult/Contents/Size = 11
            ListBucketResult/Contents/StorageClass = STANDARD
            ListBucketResult/CommonPrefixes/Prefix = my%20dir/sub/
            """.trimIndent(),
            canonical(
                listing(
                    prefix = "my dir/".toByteArray(),
                    delimiter = "/".toByteArray(),
                    contents = listOf(entry("my dir/file.txt")),
                    commonPrefixes = listOf("my dir/sub/".toByteArray()),
                    keyCount = 1,
                    encoding = S3Documents.KeyEncoding.URL,
                    startAfter = "my dir/a.txt".toByteArray(),
                ),
            ),
        )
    }

    @Test
    fun `an absent member is left out, and Prefix is the exception that is left empty`() {
        // Both halves matter to a client: `Delimiter` absent means nothing was grouped, while
        // `Prefix` empty means nothing was asked for — and clients read the second one back.
        assertEquals(
            """
            ListBucketResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListBucketResult/Name = b
            ListBucketResult/Prefix =
            ListBucketResult/MaxKeys = 1000
            ListBucketResult/KeyCount = 0
            ListBucketResult/IsTruncated = false
            """.trimIndent(),
            canonical(listing(bucket = "b")),
        )
    }

    @Test
    fun `a truncated page states where the next one starts`() {
        assertEquals(
            """
            ListBucketResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListBucketResult/Name = b
            ListBucketResult/Prefix =
            ListBucketResult/MaxKeys = 1
            ListBucketResult/KeyCount = 1
            ListBucketResult/IsTruncated = true
            ListBucketResult/ContinuationToken = from-here
            ListBucketResult/NextContinuationToken = to-there
            ListBucketResult/Contents/Key = a.txt
            ListBucketResult/Contents/LastModified = $NOW
            ListBucketResult/Contents/ETag = "d41d8c"
            ListBucketResult/Contents/Size = 11
            ListBucketResult/Contents/StorageClass = STANDARD
            """.trimIndent(),
            canonical(
                listing(
                    bucket = "b",
                    maxKeys = 1,
                    keyCount = 1,
                    isTruncated = true,
                    contents = listOf(entry("a.txt")),
                    continuationToken = "from-here",
                    nextContinuationToken = "to-there",
                ),
            ),
        )
    }

    @Test
    fun `the multipart documents name every member the client reads`() {
        val key = ObjectKey.of("big.bin")

        assertEquals(
            """
            InitiateMultipartUploadResult @ http://s3.amazonaws.com/doc/2006-03-01/
            InitiateMultipartUploadResult/Bucket = photos
            InitiateMultipartUploadResult/Key = big.bin
            InitiateMultipartUploadResult/UploadId = upload-1
            """.trimIndent(),
            canonical(S3Documents.initiateMultipartUploadResult("photos", key, "upload-1")),
        )

        // The checksum belongs in the body: `CompleteMultipartUploadOutput.ChecksumCRC32` carries
        // no `location` in `s3-service-2.json`, which makes it an element. The same value in a
        // header is invisible to an SDK.
        assertEquals(
            """
            CompleteMultipartUploadResult @ http://s3.amazonaws.com/doc/2006-03-01/
            CompleteMultipartUploadResult/Location = http://localhost:9000/photos/big.bin
            CompleteMultipartUploadResult/Bucket = photos
            CompleteMultipartUploadResult/Key = big.bin
            CompleteMultipartUploadResult/ETag = "abc-2"
            CompleteMultipartUploadResult/ChecksumCRC32 = q1D2Aw==
            CompleteMultipartUploadResult/ChecksumType = COMPOSITE
            """.trimIndent(),
            canonical(
                S3Documents.completeMultipartUploadResult(
                    location = "http://localhost:9000/photos/big.bin",
                    bucket = "photos",
                    key = key,
                    eTag = "\"abc-2\"",
                    checksum = "crc32" to "q1D2Aw==",
                    checksumType = "COMPOSITE",
                ),
            ),
        )

        assertEquals(
            """
            ListPartsResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListPartsResult/Bucket = photos
            ListPartsResult/Key = big.bin
            ListPartsResult/UploadId = upload-1
            ListPartsResult/PartNumberMarker = 0
            ListPartsResult/NextPartNumberMarker = 2
            ListPartsResult/MaxParts = 1000
            ListPartsResult/IsTruncated = false
            ListPartsResult/StorageClass = STANDARD
            ListPartsResult/ChecksumAlgorithm = CRC32
            ListPartsResult/Part[1]/PartNumber = 1
            ListPartsResult/Part[1]/LastModified = $NOW
            ListPartsResult/Part[1]/ETag = "p1"
            ListPartsResult/Part[1]/Size = 5242880
            ListPartsResult/Part[1]/ChecksumCRC32 = q1D2Aw==
            ListPartsResult/Part[2]/PartNumber = 2
            ListPartsResult/Part[2]/LastModified = $NOW
            ListPartsResult/Part[2]/ETag = "p2"
            ListPartsResult/Part[2]/Size = 17
            """.trimIndent(),
            canonical(
                S3Documents.listPartsResult(
                    bucket = "photos",
                    key = key,
                    uploadId = "upload-1",
                    partNumberMarker = 0,
                    nextPartNumberMarker = 2,
                    maxParts = 1000,
                    isTruncated = false,
                    parts =
                        listOf(
                            S3Documents.PartEntry(1, NOW, "\"p1\"", 5 * 1024 * 1024, "crc32" to "q1D2Aw=="),
                            S3Documents.PartEntry(2, NOW, "\"p2\"", 17),
                        ),
                    checksumAlgorithm = "crc32",
                ),
            ),
        )
    }

    @Test
    fun `a delete result reports successes and failures side by side`() {
        assertEquals(
            """
            DeleteResult @ http://s3.amazonaws.com/doc/2006-03-01/
            DeleteResult/Deleted/Key = gone.txt
            DeleteResult/Deleted/VersionId = v-1
            DeleteResult/Deleted/DeleteMarker = true
            DeleteResult/Deleted/DeleteMarkerVersionId = v-2
            DeleteResult/Error/Key = kept.txt
            DeleteResult/Error/Code = AccessDenied
            DeleteResult/Error/Message = Access Denied.
            """.trimIndent(),
            canonical(
                S3Documents.deleteResult(
                    deleted =
                        listOf(
                            S3Documents.DeletedEntry(
                                key = ObjectKey.of("gone.txt"),
                                versionId = "v-1",
                                deleteMarker = true,
                                deleteMarkerVersionId = "v-2",
                            ),
                        ),
                    errors =
                        listOf(
                            S3Documents.DeleteError(ObjectKey.of("kept.txt"), "AccessDenied", "Access Denied."),
                        ),
                ),
            ),
        )
    }

    @Test
    fun `the five entities are escaped, and the parser gets them back unchanged`() {
        // Escaping is the writer's half; that the escaping round-trips is the client's half, and
        // only a parse can say the second one.
        val awkward = """a&b<c>d"e'f"""
        assertEquals(
            """
            ListBucketResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListBucketResult/Name = b
            ListBucketResult/Prefix =
            ListBucketResult/MaxKeys = 1000
            ListBucketResult/KeyCount = 1
            ListBucketResult/IsTruncated = false
            ListBucketResult/Contents/Key = $awkward
            ListBucketResult/Contents/LastModified = $NOW
            ListBucketResult/Contents/ETag = "d41d8c"
            ListBucketResult/Contents/Size = 11
            ListBucketResult/Contents/StorageClass = STANDARD
            """.trimIndent(),
            canonical(listing(bucket = "b", keyCount = 1, contents = listOf(entry(awkward)))),
        )
    }

    /*
     * The two below stay at the byte level on purpose, and are the reason `XmlTree` is not the only
     * instrument here: both produce documents a parser is **supposed** to refuse or to alter, so
     * parsing them would destroy the property being checked.
     */

    @Test
    fun `without encoding the key goes out byte for byte`() {
        // The reason the writer takes bytes: 0xC3 0x28 is not valid UTF-8, and a writer that went
        // through String would emit a replacement character — a different key, quietly.
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        val document =
            listing(
                bucket = "b",
                keyCount = 1,
                contents = listOf(S3Documents.ObjectEntry(ObjectKey(invalid), NOW, "\"e\"", 1)),
            )

        val marker = "<Key>".toByteArray()
        val at = document.indexOfSlice(marker) + marker.size
        assertContentEquals(invalid, document.copyOfRange(at, at + invalid.size))
    }

    @Test
    fun `a control byte in a key survives into a document the parser will refuse`() {
        val key = ObjectKey("a".toByteArray() + byteArrayOf(1))
        val document =
            listing(bucket = "b", keyCount = 1, contents = listOf(S3Documents.ObjectEntry(key, NOW, "\"e\"", 1)))

        // 0x01 stays. The model's answer to keys like this is `encoding-type=url`, not a server
        // that rewrites the key (`shapes.EncodingType`), so the document is left broken on
        // purpose: a wrong key is quieter than a broken document and much worse.
        assertTrue(document.contains(1.toByte()), "the control byte must survive")
        assertTrue(
            runCatching { XmlTree.canonical(document) }.isFailure,
            "a parser is expected to refuse this document; if it now accepts it, the byte was rewritten",
        )
    }

    @Test
    fun `every document opens with the xml declaration`() {
        // A byte-level property a parse cannot report: a document without the prolog still parses,
        // and this is the one assertion here that has to look at the characters.
        for (
        document in
        listOf(
            S3Documents.error("NoSuchKey", "m", "/b/k", "req-1"),
            S3Documents.postResponse("http://h/b/k", "b", "k", "\"e\""),
            listing(),
        )
        ) {
            val text = document.toString(StandardCharsets.UTF_8)
            assertTrue(text.startsWith(XmlWriter.DECLARATION), text.take(80))
        }
    }

    @Test
    fun `the browser upload answer carries no namespace either`() {
        // `test_post_object_set_success_code:2072` reads it with `ET.fromstring(…).find('Key')`,
        // and an unqualified `find` matches nothing in a document whose root declares a namespace.
        // This is the one that shipped wrong once, and a substring test could not see it.
        assertEquals(
            """
            PostResponse @ no namespace
            PostResponse/Location = http://localhost:9000/photos/report.txt
            PostResponse/Bucket = photos
            PostResponse/Key = report.txt
            PostResponse/ETag = "abc"
            """.trimIndent(),
            canonical(
                S3Documents.postResponse(
                    "http://localhost:9000/photos/report.txt",
                    "photos",
                    "report.txt",
                    "\"abc\"",
                ),
            ),
        )
    }

    @Test
    fun `the first version of a listing answers with its own members`() {
        // `ListObjects` v1 shares the root with v2 and not the members: `Marker` instead of a
        // continuation token, and no `KeyCount`. A v1 client reading a v2 document finds no marker
        // and stops after one page.
        //
        // `Prefix` goes out **unencoded** here alone, and the client decides that rather than the
        // model: botocore's `decode_list_object` percent-decodes `Delimiter`, `Marker` and
        // `NextMarker` for v1 and not `Prefix`, so encoding it would hand a v1 client `%0A` where
        // it asked for a newline.
        assertEquals(
            """
            ListBucketResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListBucketResult/Name = photos
            ListBucketResult/Prefix = my dir/
            ListBucketResult/Marker = my%20dir/a.txt
            ListBucketResult/NextMarker = my%20dir/z.txt
            ListBucketResult/MaxKeys = 2
            ListBucketResult/Delimiter = /
            ListBucketResult/IsTruncated = true
            ListBucketResult/EncodingType = url
            ListBucketResult/Contents/Key = my%20dir/file.txt
            ListBucketResult/Contents/LastModified = $NOW
            ListBucketResult/Contents/ETag = "d41d8c"
            ListBucketResult/Contents/Size = 11
            ListBucketResult/Contents/StorageClass = STANDARD
            ListBucketResult/CommonPrefixes/Prefix = my%20dir/sub/
            """.trimIndent(),
            canonical(
                S3Documents.listObjectsResult(
                    bucket = "photos",
                    prefix = "my dir/".toByteArray(),
                    delimiter = "/".toByteArray(),
                    marker = "my dir/a.txt".toByteArray(),
                    nextMarker = "my dir/z.txt".toByteArray(),
                    maxKeys = 2,
                    isTruncated = true,
                    contents = listOf(entry("my dir/file.txt")),
                    commonPrefixes = listOf("my dir/sub/".toByteArray()),
                    encoding = S3Documents.KeyEncoding.URL,
                ),
            ),
        )
    }

    @Test
    fun `a versions listing writes the markers even when they are empty`() {
        // The cheapest field in the protocol to get wrong: botocore's paginator puts `NextKeyMarker`
        // straight into the next request, and absent it sends `None`, which its own validation then
        // rejects — inside the fixture that runs around **every** case of the foreign suite.
        //
        // A delete marker is a different element from a version and carries fewer members: no
        // `ETag`, no `Size`, no `StorageClass`.
        assertEquals(
            """
            ListVersionsResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListVersionsResult/Name = photos
            ListVersionsResult/Prefix =
            ListVersionsResult/KeyMarker =
            ListVersionsResult/NextKeyMarker =
            ListVersionsResult/VersionIdMarker =
            ListVersionsResult/NextVersionIdMarker =
            ListVersionsResult/MaxKeys = 1000
            ListVersionsResult/IsTruncated = false
            ListVersionsResult/Version/Key = a.txt
            ListVersionsResult/Version/VersionId = v-1
            ListVersionsResult/Version/IsLatest = false
            ListVersionsResult/Version/LastModified = $NOW
            ListVersionsResult/Version/ETag = "d41d8c"
            ListVersionsResult/Version/Size = 11
            ListVersionsResult/Version/StorageClass = STANDARD
            ListVersionsResult/DeleteMarker/Key = a.txt
            ListVersionsResult/DeleteMarker/VersionId = v-2
            ListVersionsResult/DeleteMarker/IsLatest = true
            ListVersionsResult/DeleteMarker/LastModified = $NOW
            """.trimIndent(),
            canonical(
                S3Documents.listVersionsResult(
                    bucket = "photos",
                    prefix = null,
                    delimiter = null,
                    keyMarker = null,
                    nextKeyMarker = null,
                    versionIdMarker = null,
                    nextVersionIdMarker = null,
                    maxKeys = 1000,
                    isTruncated = false,
                    versions =
                        listOf(
                            S3Documents.VersionEntry(
                                key = ObjectKey.of("a.txt"),
                                versionId = "v-1",
                                isLatest = false,
                                lastModified = NOW,
                                eTag = "\"d41d8c\"",
                                size = 11,
                                deleteMarker = false,
                            ),
                            S3Documents.VersionEntry(
                                key = ObjectKey.of("a.txt"),
                                versionId = "v-2",
                                isLatest = true,
                                lastModified = NOW,
                                eTag = "",
                                size = 0,
                                deleteMarker = true,
                            ),
                        ),
                    commonPrefixes = emptyList(),
                    encoding = S3Documents.KeyEncoding.NONE,
                ),
            ),
        )
    }

    @Test
    fun `an uploads listing names what an operator needs to abort them`() {
        assertEquals(
            """
            ListMultipartUploadsResult @ http://s3.amazonaws.com/doc/2006-03-01/
            ListMultipartUploadsResult/Bucket = photos
            ListMultipartUploadsResult/Prefix = big/
            ListMultipartUploadsResult/Delimiter = /
            ListMultipartUploadsResult/MaxUploads = 1000
            ListMultipartUploadsResult/IsTruncated = false
            ListMultipartUploadsResult/Upload/Key = big/a.bin
            ListMultipartUploadsResult/Upload/UploadId = upload-1
            ListMultipartUploadsResult/Upload/Initiator/ID = bochkaadmin
            ListMultipartUploadsResult/Upload/Initiator/DisplayName = bochkaadmin
            ListMultipartUploadsResult/Upload/Owner/ID = bochkaadmin
            ListMultipartUploadsResult/Upload/Owner/DisplayName = bochkaadmin
            ListMultipartUploadsResult/Upload/Initiated = $NOW
            ListMultipartUploadsResult/Upload/StorageClass = STANDARD
            """.trimIndent(),
            canonical(
                S3Documents.listMultipartUploadsResult(
                    bucket = "photos",
                    prefix = "big/".toByteArray(),
                    delimiter = "/".toByteArray(),
                    maxUploads = 1000,
                    isTruncated = false,
                    uploads =
                        listOf(
                            // With an owner, because S3 puts an `Initiator` and an `Owner` on every
                            // entry and clients dereference them without checking (M-303).
                            S3Documents.UploadEntry(ObjectKey.of("big/a.bin"), "upload-1", NOW, owner = "bochkaadmin"),
                        ),
                    encoding = S3Documents.KeyEncoding.NONE,
                ),
            ),
        )
    }

    @Test
    fun `object attributes carry only what the request asked for`() {
        // `x-amz-object-attributes` is a list and the answer carries exactly what it named. The
        // `ETag` loses its quotes here and only here — the same value is quoted in every other
        // document.
        assertEquals(
            """
            GetObjectAttributesOutput @ http://s3.amazonaws.com/doc/2006-03-01/
            GetObjectAttributesOutput/ETag = abc
            GetObjectAttributesOutput/ObjectSize = 4096
            """.trimIndent(),
            canonical(
                S3Documents.getObjectAttributesResult(
                    eTag = "\"abc\"",
                    checksum = null,
                    checksumType = null,
                    objectSize = 4096,
                    storageClass = null,
                    parts = null,
                    partsCount = 0,
                    partNumberMarker = 0,
                    maxParts = 1000,
                    isTruncated = false,
                ),
            ),
        )

        // `PartsCount` is the object's rather than the page's: a client asking for one part out of
        // ten thousand still has to be told there are ten thousand, or it cannot know it is
        // paginating.
        assertEquals(
            """
            GetObjectAttributesOutput @ http://s3.amazonaws.com/doc/2006-03-01/
            GetObjectAttributesOutput/Checksum/ChecksumCRC32 = q1D2Aw==
            GetObjectAttributesOutput/Checksum/ChecksumType = COMPOSITE
            GetObjectAttributesOutput/ObjectParts/PartsCount = 10000
            GetObjectAttributesOutput/ObjectParts/PartNumberMarker = 0
            GetObjectAttributesOutput/ObjectParts/NextPartNumberMarker = 2
            GetObjectAttributesOutput/ObjectParts/MaxParts = 2
            GetObjectAttributesOutput/ObjectParts/IsTruncated = true
            GetObjectAttributesOutput/ObjectParts/Part[1]/PartNumber = 1
            GetObjectAttributesOutput/ObjectParts/Part[1]/Size = 5242880
            GetObjectAttributesOutput/ObjectParts/Part[1]/ChecksumCRC32 = q1D2Aw==
            GetObjectAttributesOutput/ObjectParts/Part[2]/PartNumber = 2
            GetObjectAttributesOutput/ObjectParts/Part[2]/Size = 17
            GetObjectAttributesOutput/StorageClass = STANDARD
            GetObjectAttributesOutput/ObjectSize = 5242897
            """.trimIndent(),
            canonical(
                S3Documents.getObjectAttributesResult(
                    eTag = null,
                    checksum = "crc32" to "q1D2Aw==",
                    checksumType = "COMPOSITE",
                    objectSize = 5 * 1024 * 1024 + 17L,
                    storageClass = "STANDARD",
                    parts =
                        listOf(
                            S3Documents.PartEntry(1, NOW, "\"p1\"", 5 * 1024 * 1024, "crc32" to "q1D2Aw=="),
                            S3Documents.PartEntry(2, NOW, "\"p2\"", 17),
                        ),
                    partsCount = 10_000,
                    partNumberMarker = 0,
                    maxParts = 2,
                    isTruncated = true,
                ),
            ),
        )
    }

    @Test
    fun `a canned acl puts the group grants ahead of the owner`() {
        // An order rather than a taste: the foreign suite sorts both lists only when the first
        // grantee it sees has a display name, and a group has none. Owner first turns every canned
        // comparison into a sort of a list holding `None` — a `TypeError` inside the test rather
        // than a mismatch, and nine cases died that way.
        assertEquals(
            """
            AccessControlPolicy @ http://s3.amazonaws.com/doc/2006-03-01/
            AccessControlPolicy/Owner/ID = owner-1
            AccessControlPolicy/Owner/DisplayName = owner one
            AccessControlPolicy/AccessControlList/Grant[1]/Grantee/@xsi:type = Group
            AccessControlPolicy/AccessControlList/Grant[1]/Grantee/URI = http://acs.amazonaws.com/groups/global/AllUsers
            AccessControlPolicy/AccessControlList/Grant[1]/Permission = READ
            AccessControlPolicy/AccessControlList/Grant[2]/Grantee/@xsi:type = Group
            AccessControlPolicy/AccessControlList/Grant[2]/Grantee/URI = http://acs.amazonaws.com/groups/global/AllUsers
            AccessControlPolicy/AccessControlList/Grant[2]/Permission = WRITE
            AccessControlPolicy/AccessControlList/Grant[3]/Grantee/@xsi:type = CanonicalUser
            AccessControlPolicy/AccessControlList/Grant[3]/Grantee/ID = owner-1
            AccessControlPolicy/AccessControlList/Grant[3]/Grantee/DisplayName = owner one
            AccessControlPolicy/AccessControlList/Grant[3]/Permission = FULL_CONTROL
            """.trimIndent(),
            canonical(S3Documents.accessControlPolicy("owner-1", "owner one", acl = "public-read-write")),
        )

        // A grant naming a key rather than a group goes after the owner's, which is the order S3
        // answers in — and it is dropped when it would name the owner twice.
        assertEquals(
            """
            AccessControlPolicy @ http://s3.amazonaws.com/doc/2006-03-01/
            AccessControlPolicy/Owner/ID = writer
            AccessControlPolicy/Owner/DisplayName = writer
            AccessControlPolicy/AccessControlList/Grant[1]/Grantee/@xsi:type = CanonicalUser
            AccessControlPolicy/AccessControlList/Grant[1]/Grantee/ID = writer
            AccessControlPolicy/AccessControlList/Grant[1]/Grantee/DisplayName = writer
            AccessControlPolicy/AccessControlList/Grant[1]/Permission = FULL_CONTROL
            AccessControlPolicy/AccessControlList/Grant[2]/Grantee/@xsi:type = CanonicalUser
            AccessControlPolicy/AccessControlList/Grant[2]/Grantee/ID = bucket-owner
            AccessControlPolicy/AccessControlList/Grant[2]/Grantee/DisplayName = bucket-owner
            AccessControlPolicy/AccessControlList/Grant[2]/Permission = READ
            """.trimIndent(),
            canonical(
                S3Documents.accessControlPolicy(
                    ownerId = "writer",
                    ownerDisplayName = "writer",
                    acl = "bucket-owner-read",
                    bucketOwnerId = "bucket-owner",
                ),
            ),
        )

        // The other of the pair, and the case where the two ids are the same: a bucket owner who
        // wrote the object is already the `FULL_CONTROL` grantee, and granting them again would
        // hand a client the same key twice under one list.
        assertEquals(
            """
            AccessControlPolicy @ http://s3.amazonaws.com/doc/2006-03-01/
            AccessControlPolicy/Owner/ID = owner-1
            AccessControlPolicy/Owner/DisplayName = owner-1
            AccessControlPolicy/AccessControlList/Grant/Grantee/@xsi:type = CanonicalUser
            AccessControlPolicy/AccessControlList/Grant/Grantee/ID = owner-1
            AccessControlPolicy/AccessControlList/Grant/Grantee/DisplayName = owner-1
            AccessControlPolicy/AccessControlList/Grant/Permission = FULL_CONTROL
            """.trimIndent(),
            canonical(
                S3Documents.accessControlPolicy(
                    ownerId = "owner-1",
                    ownerDisplayName = "owner-1",
                    acl = "bucket-owner-full-control",
                    bucketOwnerId = "owner-1",
                ),
            ),
        )

        assertEquals(
            """
            AccessControlPolicy @ http://s3.amazonaws.com/doc/2006-03-01/
            AccessControlPolicy/Owner/ID = writer
            AccessControlPolicy/Owner/DisplayName = writer
            AccessControlPolicy/AccessControlList/Grant[1]/Grantee/@xsi:type = CanonicalUser
            AccessControlPolicy/AccessControlList/Grant[1]/Grantee/ID = writer
            AccessControlPolicy/AccessControlList/Grant[1]/Grantee/DisplayName = writer
            AccessControlPolicy/AccessControlList/Grant[1]/Permission = FULL_CONTROL
            AccessControlPolicy/AccessControlList/Grant[2]/Grantee/@xsi:type = CanonicalUser
            AccessControlPolicy/AccessControlList/Grant[2]/Grantee/ID = bucket-owner
            AccessControlPolicy/AccessControlList/Grant[2]/Grantee/DisplayName = bucket-owner
            AccessControlPolicy/AccessControlList/Grant[2]/Permission = FULL_CONTROL
            """.trimIndent(),
            canonical(
                S3Documents.accessControlPolicy(
                    ownerId = "writer",
                    ownerDisplayName = "writer",
                    acl = "bucket-owner-full-control",
                    bucketOwnerId = "bucket-owner",
                ),
            ),
        )
    }

    @Test
    fun `a lifecycle document writes every branch of a rule`() {
        assertEquals(
            """
            LifecycleConfiguration @ http://s3.amazonaws.com/doc/2006-03-01/
            LifecycleConfiguration/Rule/ID = everything
            LifecycleConfiguration/Rule/Filter/And/Prefix = logs/
            LifecycleConfiguration/Rule/Filter/And/Tag/Key = class
            LifecycleConfiguration/Rule/Filter/And/Tag/Value = cold
            LifecycleConfiguration/Rule/Filter/And/ObjectSizeGreaterThan = 1024
            LifecycleConfiguration/Rule/Filter/And/ObjectSizeLessThan = 4096
            LifecycleConfiguration/Rule/Status = Enabled
            LifecycleConfiguration/Rule/Expiration/Days = 30
            LifecycleConfiguration/Rule/Expiration/ExpiredObjectDeleteMarker = true
            LifecycleConfiguration/Rule/NoncurrentVersionExpiration/NoncurrentDays = 7
            LifecycleConfiguration/Rule/NoncurrentVersionExpiration/NewerNoncurrentVersions = 3
            LifecycleConfiguration/Rule/AbortIncompleteMultipartUpload/DaysAfterInitiation = 2
            """.trimIndent(),
            canonical(
                S3Documents.lifecycleResult(
                    Lifecycle(
                        listOf(
                            Lifecycle.Rule(
                                id = "everything",
                                enabled = true,
                                filter =
                                    Lifecycle.Filter(
                                        and =
                                            Lifecycle.And(
                                                prefix = "logs/",
                                                tags = listOf(Lifecycle.Tag("class", "cold")),
                                                sizeGreaterThan = 1024,
                                                sizeLessThan = 4096,
                                            ),
                                    ),
                                expiration = Lifecycle.Expiration(days = 30, expiredObjectDeleteMarker = true),
                                noncurrent = Lifecycle.Noncurrent(days = 7, newerVersions = 3),
                                abortIncompleteUploadDays = 2,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the small configuration documents say the one thing they are for`() {
        assertEquals(
            """
            Tagging @ http://s3.amazonaws.com/doc/2006-03-01/
            Tagging/TagSet/Tag[1]/Key = a
            Tagging/TagSet/Tag[1]/Value = 1
            Tagging/TagSet/Tag[2]/Key = b
            Tagging/TagSet/Tag[2]/Value = 2
            """.trimIndent(),
            // Sorted by key rather than by arrival: the same set arrives as a document in one order
            // and as `x-amz-tagging` in another, and the suite compares the document.
            canonical(S3Documents.taggingResult(linkedMapOf("b" to "2", "a" to "1"))),
        )

        assertEquals(
            """
            CORSConfiguration @ http://s3.amazonaws.com/doc/2006-03-01/
            CORSConfiguration/CORSRule/ID = one
            CORSConfiguration/CORSRule/AllowedMethod = GET
            CORSConfiguration/CORSRule/AllowedOrigin = https://example.com
            CORSConfiguration/CORSRule/AllowedHeader = *
            CORSConfiguration/CORSRule/ExposeHeader = ETag
            CORSConfiguration/CORSRule/MaxAgeSeconds = 60
            """.trimIndent(),
            canonical(
                S3Documents.corsResult(
                    CorsRules(
                        listOf(
                            CorsRules.Rule(
                                id = "one",
                                allowedMethods = listOf("GET"),
                                allowedOrigins = listOf("https://example.com"),
                                allowedHeaders = listOf("*"),
                                exposeHeaders = listOf("ETag"),
                                maxAgeSeconds = 60,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            """
            VersioningConfiguration @ http://s3.amazonaws.com/doc/2006-03-01/
            VersioningConfiguration/Status = Enabled
            """.trimIndent(),
            canonical(S3Documents.versioningResult(ObjectStore.Versioning.ENABLED)),
        )
        assertEquals(
            """
            VersioningConfiguration @ http://s3.amazonaws.com/doc/2006-03-01/
            VersioningConfiguration/Status = Suspended
            """.trimIndent(),
            canonical(S3Documents.versioningResult(ObjectStore.Versioning.SUSPENDED)),
        )
        // A bucket that was never versioned answers with an empty document rather than a status:
        // there is no `Status` for "never turned on", and inventing one would tell a client the
        // bucket had been suspended.
        assertEquals(
            """
            VersioningConfiguration @ http://s3.amazonaws.com/doc/2006-03-01/
            VersioningConfiguration =
            """.trimIndent(),
            canonical(S3Documents.versioningResult(ObjectStore.Versioning.NONE)),
        )

        assertEquals(
            """
            ObjectLockConfiguration @ http://s3.amazonaws.com/doc/2006-03-01/
            ObjectLockConfiguration/ObjectLockEnabled = Enabled
            ObjectLockConfiguration/Rule/DefaultRetention/Mode = GOVERNANCE
            ObjectLockConfiguration/Rule/DefaultRetention/Days = 5
            """.trimIndent(),
            canonical(S3Documents.objectLockResult(ObjectStore.ObjectLock(defaultMode = "GOVERNANCE", days = 5))),
        )

        assertEquals(
            """
            Retention @ http://s3.amazonaws.com/doc/2006-03-01/
            Retention/Mode = COMPLIANCE
            Retention/RetainUntilDate = 2026-08-17T10:00:00Z
            """.trimIndent(),
            canonical(
                S3Documents.retentionResult(
                    ObjectStore.Retention("COMPLIANCE", Instant.parse("2026-08-17T10:00:00Z").toEpochMilli()),
                ),
            ),
        )
        // Nothing held: the document is present and empty rather than absent, because the operation
        // still answered.
        assertEquals(
            """
            Retention @ http://s3.amazonaws.com/doc/2006-03-01/
            Retention =
            """.trimIndent(),
            canonical(S3Documents.retentionResult(null)),
        )

        assertEquals(
            """
            LegalHold @ http://s3.amazonaws.com/doc/2006-03-01/
            LegalHold/Status = ON
            """.trimIndent(),
            canonical(S3Documents.legalHoldResult(true)),
        )
        assertEquals(
            """
            LegalHold @ http://s3.amazonaws.com/doc/2006-03-01/
            LegalHold/Status = OFF
            """.trimIndent(),
            canonical(S3Documents.legalHoldResult(false)),
        )

        assertEquals(
            """
            PolicyStatus @ http://s3.amazonaws.com/doc/2006-03-01/
            PolicyStatus/IsPublic = true
            """.trimIndent(),
            canonical(S3Documents.policyStatusResult(true)),
        )
    }

    @Test
    fun `a copy answers with the modification time before the tag, both times`() {
        // `CopyObjectResult` and `CopyPartResult` are the same shape under different roots, and an
        // SDK for `UploadPartCopy` reads the second one by name.
        assertEquals(
            """
            CopyObjectResult @ http://s3.amazonaws.com/doc/2006-03-01/
            CopyObjectResult/LastModified = $NOW
            CopyObjectResult/ETag = "abc"
            """.trimIndent(),
            canonical(S3Documents.copyObjectResult("\"abc\"", NOW)),
        )
        assertEquals(
            """
            CopyPartResult @ http://s3.amazonaws.com/doc/2006-03-01/
            CopyPartResult/LastModified = $NOW
            CopyPartResult/ETag = "abc"
            """.trimIndent(),
            canonical(S3Documents.copyPartResult("\"abc\"", NOW)),
        )
    }

    @Test
    fun `a failed delete echoes the version the request named`() {
        // The only confirmation a client deleting by version gets that the one it meant is the one
        // that was refused.
        assertEquals(
            """
            DeleteResult @ http://s3.amazonaws.com/doc/2006-03-01/
            DeleteResult/Deleted/Key = gone.txt
            DeleteResult/Error/Key = kept.txt
            DeleteResult/Error/VersionId = v-9
            DeleteResult/Error/Code = AccessDenied
            DeleteResult/Error/Message = Access Denied.
            """.trimIndent(),
            canonical(
                S3Documents.deleteResult(
                    deleted = listOf(S3Documents.DeletedEntry(ObjectKey.of("gone.txt"))),
                    errors =
                        listOf(
                            S3Documents.DeleteError(
                                key = ObjectKey.of("kept.txt"),
                                code = "AccessDenied",
                                message = "Access Denied.",
                                versionId = "v-9",
                            ),
                        ),
                ),
            ),
        )
    }

    @Suppress("LongParameterList")
    private fun listing(
        bucket: String = "photos",
        prefix: ByteArray? = null,
        delimiter: ByteArray? = null,
        maxKeys: Int = 1000,
        keyCount: Int = 0,
        isTruncated: Boolean = false,
        contents: List<S3Documents.ObjectEntry> = emptyList(),
        commonPrefixes: List<ByteArray> = emptyList(),
        encoding: S3Documents.KeyEncoding = S3Documents.KeyEncoding.NONE,
        continuationToken: String? = null,
        nextContinuationToken: String? = null,
        startAfter: ByteArray? = null,
        owner: String? = null,
    ) = S3Documents.listBucketResult(
        bucket = bucket,
        prefix = prefix,
        delimiter = delimiter,
        maxKeys = maxKeys,
        keyCount = keyCount,
        isTruncated = isTruncated,
        contents = contents,
        commonPrefixes = commonPrefixes,
        encoding = encoding,
        continuationToken = continuationToken,
        nextContinuationToken = nextContinuationToken,
        startAfter = startAfter,
        owner = owner,
    )

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
