package io.github.youndie.bochka.s3.xml

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.s3.UriCodec

/**
 * The documents S3 puts on the wire, in both directions.
 *
 * Roots and namespaces come from the reference server's response structs (`minio/minio`,
 * `cmd/api-response.go:102,178,226,403,412,436` and `cmd/api-errors.go:64` for `<Error>`, which is
 * the one document with **no** namespace). Member names come from the machine model
 * (`docs/spec/s3-service-2.json`, `shapes.*.members`), which is also where the flattening is
 * recorded: `Contents`, `CommonPrefixes`, `Deleted` and the parts list repeat their element
 * directly, while `Buckets` wraps `Bucket`.
 *
 * Everything here is a pure function of its arguments. There is no storage behind it, which is what
 * lets the whole protocol layer be tested on recorded bytes (Р8).
 */
object S3Documents {
    /** How keys are written in a listing: `encoding-type=url` was asked for, or it was not. */
    enum class KeyEncoding {
        NONE,
        URL,
    }

    data class ObjectEntry(
        val key: ObjectKey,
        val lastModified: String,
        val eTag: String,
        val size: Long,
        val storageClass: String = "STANDARD",
    )

    data class BucketEntry(
        val name: String,
        val creationDate: String,
    )

    data class PartEntry(
        val partNumber: Int,
        val lastModified: String,
        val eTag: String,
        val size: Long,
        /** What the part hashed to, when it carried one: `crc32` to a base64 value. */
        val checksum: Pair<String, String>? = null,
    )

    data class DeletedEntry(
        val key: ObjectKey,
        /**
         * The version the request named, when it named one.
         *
         * S3 echoes it back, and a client that deletes by version has no other confirmation that
         * the one it meant is the one that went.
         */
        val versionId: String? = null,
        /**
         * Set when this delete **created** a tombstone rather than removing bytes.
         *
         * The pair `DeleteMarker` + `DeleteMarkerVersionId` is how a caller learns that its
         * delete is undoable and by what name — the batch form's equivalent of the headers a
         * single `DELETE` answers with, and the only place a batch hands the id out (M-139).
         */
        val deleteMarker: Boolean = false,
        val deleteMarkerVersionId: String? = null,
    )

    data class DeleteError(
        val key: ObjectKey,
        val code: String,
        val message: String,
        /**
         * Which version was refused, when the request named one.
         *
         * Read by the caller, not decoration: `nuke_bucket` in `ceph/s3-tests` takes an
         * `AccessDenied` here and asks `GetObjectRetention` about `err['VersionId']` to find out
         * how long to wait. Without it the cleanup raises `KeyError` and every following test
         * fails in its own fixture.
         */
        val versionId: String? = null,
    )

    /**
     * `<Error>`, and it carries no namespace — unlike every result document. Verified in the
     * reference server's `APIErrorResponse` (`cmd/api-errors.go:64-76`), and it matters: a client
     * matching on a namespaced root would never recognise an error.
     *
     * `RequestId` and `HostId` go out even when there is nothing else to say. The client library
     * next door records why: without that pair AWS support will not look at a report, so clients
     * carry them into their exceptions unconditionally.
     */
    fun error(
        code: String,
        message: String,
        resource: String,
        requestId: String,
        hostId: String = "",
        key: ObjectKey? = null,
        bucketName: String? = null,
    ): ByteArray =
        XmlWriter(256).document("Error", namespace = null) {
            text("Code", code)
            text("Message", message)
            if (key != null) raw("Key", key.toByteArray())
            text("BucketName", bucketName)
            text("Resource", resource)
            text("RequestId", requestId)
            text("HostId", hostId)
        }

    @Suppress("LongParameterList")
    fun listBucketResult(
        bucket: String,
        prefix: ByteArray?,
        delimiter: ByteArray?,
        maxKeys: Int,
        keyCount: Int,
        isTruncated: Boolean,
        contents: List<ObjectEntry>,
        commonPrefixes: List<ByteArray>,
        encoding: KeyEncoding,
        continuationToken: String? = null,
        nextContinuationToken: String? = null,
        startAfter: ByteArray? = null,
        owner: String? = null,
    ): ByteArray =
        XmlWriter(1024 + contents.size * 128).document("ListBucketResult") {
            text("Name", bucket)
            // Prefix is always present, empty when not asked for — clients read it back.
            encodedText("Prefix", prefix ?: ByteArray(0), encoding)
            if (delimiter != null) encodedText("Delimiter", delimiter, encoding)
            text("MaxKeys", maxKeys.toLong())
            text("KeyCount", keyCount.toLong())
            text("IsTruncated", isTruncated)
            if (encoding == KeyEncoding.URL) text("EncodingType", "url")
            text("ContinuationToken", continuationToken)
            text("NextContinuationToken", nextContinuationToken)
            if (startAfter != null) encodedText("StartAfter", startAfter, encoding)
            for (entry in contents) {
                element("Contents") {
                    encodedText("Key", entry.key.toByteArray(), encoding)
                    text("LastModified", entry.lastModified)
                    text("ETag", entry.eTag)
                    text("Size", entry.size)
                    text("StorageClass", entry.storageClass)
                    // Only when `fetch-owner=true` asked for it: `shapes.ListObjectsV2Request`
                    // has the parameter precisely because the owner is not sent by default.
                    if (owner != null) {
                        element("Owner") {
                            text("ID", owner)
                            text("DisplayName", owner)
                        }
                    }
                }
            }
            for (commonPrefix in commonPrefixes) {
                element("CommonPrefixes") {
                    encodedText("Prefix", commonPrefix, encoding)
                }
            }
        }

    /**
     * `<ListBucketResult>` as `ListObjects` — the first version — writes it.
     *
     * The root element is the same and the field set is not, which is why this is its own function
     * rather than a flag. `shapes.ListObjectsOutput.members` has `Marker` and `NextMarker` and has
     * neither `KeyCount` nor `ContinuationToken`; a client of v1 reading a document with the v2
     * fields finds no marker and stops after one page.
     *
     * `NextMarker` goes out **only** when a delimiter was asked for — that is the model's own note
     * on the member ("This element is returned only if you have the delimiter request parameter
     * specified"), and it is there because without a delimiter the client can use the last key it
     * received, while a page that ended on a rolled-up prefix has no such key.
     */
    @Suppress("LongParameterList")
    fun listObjectsResult(
        bucket: String,
        prefix: ByteArray,
        delimiter: ByteArray?,
        marker: ByteArray?,
        nextMarker: ByteArray?,
        maxKeys: Int,
        isTruncated: Boolean,
        contents: List<ObjectEntry>,
        commonPrefixes: List<ByteArray>,
        encoding: KeyEncoding,
    ): ByteArray =
        XmlWriter(1024 + contents.size * 128).document("ListBucketResult") {
            text("Name", bucket)
            // **Not** encoded, alone among the fields of this document, and it is the client that
            // decides that rather than the specification. botocore's `decode_list_object` lists
            // `Delimiter`, `Marker` and `NextMarker` as the fields it percent-decodes for `v1` —
            // its own comment above that list names `Prefix` too, and the list does not contain
            // it. So a server that encodes this one hands a v1 client `%0A` where it asked for a
            // newline. `v2` decodes `Prefix` and so gets it encoded; the two versions of one
            // operation disagree, and both have to be answered the way they are read
            // (`test_bucket_list_prefix_unreadable` against `test_bucket_listv2_prefix_unreadable`).
            raw("Prefix", prefix)
            encodedText("Marker", marker ?: ByteArray(0), encoding)
            if (nextMarker != null) encodedText("NextMarker", nextMarker, encoding)
            text("MaxKeys", maxKeys.toLong())
            if (delimiter != null) encodedText("Delimiter", delimiter, encoding)
            text("IsTruncated", isTruncated)
            if (encoding == KeyEncoding.URL) text("EncodingType", "url")
            for (entry in contents) {
                element("Contents") {
                    encodedText("Key", entry.key.toByteArray(), encoding)
                    text("LastModified", entry.lastModified)
                    text("ETag", entry.eTag)
                    text("Size", entry.size)
                    text("StorageClass", entry.storageClass)
                }
            }
            for (commonPrefix in commonPrefixes) {
                element("CommonPrefixes") {
                    encodedText("Prefix", commonPrefix, encoding)
                }
            }
        }

    /**
     * `<GetObjectAttributesOutput>` — only the members the request asked for.
     *
     * `x-amz-object-attributes` is a list, and the response carries **exactly** what it named:
     * `shapes.ObjectAttributes` enumerates `ETag`, `Checksum`, `ObjectParts`, `StorageClass` and
     * `ObjectSize`, and a server that answered all five regardless would be sending a client
     * fields it deliberately did not ask for — which for `ObjectParts` on a ten-thousand-part
     * object is the difference between a header-sized answer and a megabyte.
     */
    @Suppress("LongParameterList")
    fun getObjectAttributesResult(
        eTag: String?,
        checksum: Pair<String, String>?,
        checksumType: String?,
        objectSize: Long?,
        storageClass: String?,
        parts: List<PartEntry>?,
        partsCount: Int,
        partNumberMarker: Int,
        maxParts: Int,
        isTruncated: Boolean,
    ): ByteArray =
        XmlWriter(256 + (parts?.size ?: 0) * 96).document("GetObjectAttributesOutput") {
            // The quotes come off here and only here: `ETag` is the one member of this document
            // that S3 sends unquoted, unlike the same value in every other response.
            if (eTag != null) text("ETag", eTag.trim('"'))
            if (checksum != null) {
                element("Checksum") {
                    text("Checksum${checksum.first.uppercase()}", checksum.second)
                    if (checksumType != null) text("ChecksumType", checksumType)
                }
            }
            // Present only for an object that **was** assembled from parts. S3 omits the member
            // entirely for an ordinary upload, and a client reads its absence as "not multipart";
            // an empty `ObjectParts` with `PartsCount` of zero says something different and wrong.
            if (parts != null && parts.isNotEmpty()) {
                element("ObjectParts") {
                    // `shapes.GetObjectAttributesParts` is a paginated shape, and its members are
                    // not optional to a generated client: botocore reads `IsTruncated` and
                    // `MaxParts` off it whether or not there is a second page.
                    // `PartsCount` is the object's, not the page's: a client asking for one part
                    // out of ten thousand still has to be told there are ten thousand, or it has
                    // no way to know it is paginating (`test_get_paginated_multipart_object_attributes`
                    // reads both off this one document).
                    text("PartsCount", partsCount.toLong())
                    text("PartNumberMarker", partNumberMarker.toLong())
                    text("NextPartNumberMarker", parts.lastOrNull()?.partNumber?.toLong() ?: 0L)
                    text("MaxParts", maxParts.toLong())
                    text("IsTruncated", isTruncated)
                    for (part in parts) {
                        element("Part") {
                            text("PartNumber", part.partNumber.toLong())
                            text("Size", part.size)
                            // Only when the part actually carried one: an SDK reads the absence of
                            // this element as "this part was not checksummed", and the suite
                            // asserts exactly that for an upload that stated no algorithm.
                            part.checksum?.let { text("Checksum${it.first.uppercase()}", it.second) }
                        }
                    }
                }
            }
            if (storageClass != null) text("StorageClass", storageClass)
            if (objectSize != null) text("ObjectSize", objectSize)
        }

    /** `<Tagging><TagSet>` — тот же документ на чтении, что и на записи (`s3-service-2.json:13301`). */
    fun taggingResult(tags: Map<String, String>): ByteArray =
        XmlWriter(128 + tags.size * 64).document("Tagging") {
            element("TagSet") {
                for ((key, value) in tags) {
                    element("Tag") {
                        text("Key", key)
                        text("Value", value)
                    }
                }
            }
        }

    /** `<CORSConfiguration>` — `s3-service-2.json:2241`. */
    fun corsResult(rules: io.github.youndie.bochka.s3.CorsRules): ByteArray =
        XmlWriter(256 + rules.rules.size * 128).document("CORSConfiguration") {
            for (rule in rules.rules) {
                element("CORSRule") {
                    text("ID", rule.id)
                    for (method in rule.allowedMethods) text("AllowedMethod", method)
                    for (origin in rule.allowedOrigins) text("AllowedOrigin", origin)
                    for (header in rule.allowedHeaders) text("AllowedHeader", header)
                    for (header in rule.exposeHeaders) text("ExposeHeader", header)
                    rule.maxAgeSeconds?.let { text("MaxAgeSeconds", it.toLong()) }
                }
            }
        }

    /**
     * `<VersioningConfiguration>` — and the empty one is an answer, not a refusal.
     *
     * A bucket nobody configured answers with the element and nothing inside it. That is what S3
     * does, and it is the difference this repository has already paid for once: `NotImplemented`
     * reads to a client as "the server is broken", and it cost 837 cases on `?versions` in M3.
     */
    fun versioningResult(state: ObjectStore.Versioning): ByteArray =
        XmlWriter(128).document("VersioningConfiguration") {
            when (state) {
                ObjectStore.Versioning.ENABLED -> text("Status", "Enabled")
                ObjectStore.Versioning.SUSPENDED -> text("Status", "Suspended")
                ObjectStore.Versioning.NONE -> Unit
            }
        }

    fun objectLockResult(lock: ObjectStore.ObjectLock): ByteArray =
        XmlWriter(256).document("ObjectLockConfiguration") {
            text("ObjectLockEnabled", "Enabled")
            if (lock.defaultMode != null) {
                element("Rule") {
                    element("DefaultRetention") {
                        text("Mode", lock.defaultMode)
                        lock.days?.let { text("Days", it.toLong()) }
                        lock.years?.let { text("Years", it.toLong()) }
                    }
                }
            }
        }

    /**
     * `<Retention>` — and an object with none answers with an **empty** one rather than a `404`.
     *
     * The object is there; what is absent is a rule about it, and those are different facts. The
     * same distinction the bucket sub-resources already draw.
     */
    fun retentionResult(retention: ObjectStore.Retention?): ByteArray =
        XmlWriter(192).document("Retention") {
            if (retention != null) {
                text("Mode", retention.mode)
                text(
                    "RetainUntilDate",
                    java.time.Instant
                        .ofEpochMilli(retention.untilMillis)
                        .toString(),
                )
            }
        }

    fun legalHoldResult(held: Boolean): ByteArray =
        XmlWriter(128).document("LegalHold") {
            text("Status", if (held) "ON" else "OFF")
        }

    /**
     * `<AccessControlPolicy>` — the owner with `FULL_CONTROL`, and nothing else.
     *
     * The one document in this file that is genuinely a stand-in, and it is written to be a
     * **true** one: there are no access controls here, so the honest report is that the caller
     * owns the thing and may do everything to it. A client reading it learns that nothing is
     * restricted, which is the case.
     *
     * It answers the read side only. `PutBucketAcl` stays refused, because a grant accepted and
     * not enforced is discovered as a leak rather than as an error.
     */
    fun accessControlPolicy(
        ownerId: String,
        ownerDisplayName: String,
    ): ByteArray =
        XmlWriter(384).document("AccessControlPolicy") {
            element("Owner") {
                text("ID", ownerId)
                text("DisplayName", ownerDisplayName)
            }
            element("AccessControlList") {
                element("Grant") {
                    // Without the `xsi:type="CanonicalUser"` attribute real S3 puts here: this
                    // writer has no attributes, and botocore reads the grantee by its elements
                    // rather than by that type. Written down because it is a difference from the
                    // wire, not an oversight — a client that keys off the attribute would see a
                    // grantee of no type.
                    element("Grantee") {
                        text("ID", ownerId)
                        text("DisplayName", ownerDisplayName)
                    }
                    text("Permission", "FULL_CONTROL")
                }
            }
        }

    fun listAllMyBucketsResult(
        buckets: List<BucketEntry>,
        ownerId: String,
        ownerDisplayName: String,
        /**
         * Where the next page starts, when there is one.
         *
         * The member is called `ContinuationToken` on the way out as well as on the way in
         * (`shapes.ListBucketsOutput.members`), which reads as an echo and is not one: it is the
         * **next** token, and its absence is how a client knows it has seen everything.
         */
        nextContinuationToken: String? = null,
        prefix: String? = null,
    ): ByteArray =
        XmlWriter(256 + buckets.size * 96).document("ListAllMyBucketsResult") {
            element("Owner") {
                text("ID", ownerId)
                text("DisplayName", ownerDisplayName)
            }
            // Not flattened, unlike the lists in a listing: `shapes.ListBucketsOutput.members`
            // wraps them, so a client looking for <Buckets><Bucket> finds nothing if this is
            // flattened by analogy with Contents.
            element("Buckets") {
                for (bucket in buckets) {
                    element("Bucket") {
                        text("Name", bucket.name)
                        text("CreationDate", bucket.creationDate)
                    }
                }
            }
            text("ContinuationToken", nextContinuationToken)
            text("Prefix", prefix)
        }

    /**
     * `<CopyObjectResult>` — the answer to a copy, and the second document that carries a result
     * inside a `200` for a reason.
     *
     * A copy of a large object takes time, so S3 defined this response to start before the copy
     * finishes, with the outcome in the body. bochka copies before answering, so the status is
     * always the true one — the same choice as `CompleteMultipartUpload` and for the same reason.
     */
    fun copyObjectResult(
        eTag: String,
        lastModified: String,
    ): ByteArray =
        XmlWriter(256).document("CopyObjectResult") {
            text("LastModified", lastModified)
            text("ETag", eTag)
        }

    /**
     * `<PostResponse>` — what a form upload answers with when it asked for `201`.
     *
     * The only success document whose shape is chosen by the client: a form that said nothing gets
     * `204` and no body at all. `Location` is here because the browser that posted has no other way
     * to learn where the object landed.
     */
    fun postResponse(
        location: String,
        bucket: String,
        key: String,
        eTag: String,
    ): ByteArray =
        XmlWriter(256).document("PostResponse") {
            text("Location", location)
            text("Bucket", bucket)
            text("Key", key)
            text("ETag", eTag)
        }

    /** `<CopyPartResult>` — the answer to `UploadPartCopy`, and the same shape as a copy's. */
    fun copyPartResult(
        eTag: String,
        lastModified: String,
    ): ByteArray =
        XmlWriter(256).document("CopyPartResult") {
            text("LastModified", lastModified)
            text("ETag", eTag)
        }

    fun initiateMultipartUploadResult(
        bucket: String,
        key: ObjectKey,
        uploadId: String,
    ): ByteArray =
        XmlWriter(256).document("InitiateMultipartUploadResult") {
            text("Bucket", bucket)
            raw("Key", key.toByteArray())
            text("UploadId", uploadId)
        }

    /**
     * `<CompleteMultipartUploadResult>`, and the checksum belongs in the **body** of it.
     *
     * Unlike `CreateMultipartUpload`, whose algorithm and type ride as headers,
     * `CompleteMultipartUploadOutput.ChecksumCRC32` and `.ChecksumType` carry no `location` in
     * `s3-service-2.json`, which makes them elements. An SDK reads them from there and nowhere
     * else, so the same value in a header would be invisible to it.
     */
    fun completeMultipartUploadResult(
        location: String,
        bucket: String,
        key: ObjectKey,
        eTag: String,
        checksum: Pair<String, String>? = null,
        checksumType: String? = null,
    ): ByteArray =
        XmlWriter(320).document("CompleteMultipartUploadResult") {
            text("Location", location)
            text("Bucket", bucket)
            raw("Key", key.toByteArray())
            text("ETag", eTag)
            if (checksum != null) {
                text("Checksum${checksum.first.uppercase()}", checksum.second)
                if (checksumType != null) text("ChecksumType", checksumType)
            }
        }

    @Suppress("LongParameterList")
    fun listPartsResult(
        bucket: String,
        key: ObjectKey,
        uploadId: String,
        partNumberMarker: Int,
        nextPartNumberMarker: Int,
        maxParts: Int,
        isTruncated: Boolean,
        parts: List<PartEntry>,
        storageClass: String = "STANDARD",
        checksumAlgorithm: String? = null,
    ): ByteArray =
        XmlWriter(512 + parts.size * 96).document("ListPartsResult") {
            text("Bucket", bucket)
            raw("Key", key.toByteArray())
            text("UploadId", uploadId)
            text("PartNumberMarker", partNumberMarker.toLong())
            text("NextPartNumberMarker", nextPartNumberMarker.toLong())
            text("MaxParts", maxParts.toLong())
            text("IsTruncated", isTruncated)
            text("StorageClass", storageClass)
            // Which algorithm the parts were checksummed with, so a client resuming an upload
            // knows what to send for the parts it has not sent yet.
            if (checksumAlgorithm != null) text("ChecksumAlgorithm", checksumAlgorithm.uppercase())
            for (part in parts) {
                element("Part") {
                    text("PartNumber", part.partNumber.toLong())
                    text("LastModified", part.lastModified)
                    text("ETag", part.eTag)
                    text("Size", part.size)
                    part.checksum?.let { text("Checksum${it.first.uppercase()}", it.second) }
                }
            }
        }

    data class UploadEntry(
        val key: ObjectKey,
        val uploadId: String,
        val initiated: String,
    )

    /**
     * `<ListMultipartUploadsResult>` — the uploads that have begun and not finished.
     *
     * Worth having for a reason that is not completeness: an abandoned upload holds its parts on
     * the disk and nothing else will ever mention it. This is how an operator finds them, and
     * `AbortMultipartUpload` is how they go (M-57).
     */
    @Suppress("LongParameterList")
    fun listMultipartUploadsResult(
        bucket: String,
        prefix: ByteArray,
        delimiter: ByteArray?,
        maxUploads: Int,
        isTruncated: Boolean,
        uploads: List<UploadEntry>,
        encoding: KeyEncoding,
    ): ByteArray =
        XmlWriter(512 + uploads.size * 128).document("ListMultipartUploadsResult") {
            text("Bucket", bucket)
            encodedText("Prefix", prefix, encoding)
            if (delimiter != null) encodedText("Delimiter", delimiter, encoding)
            text("MaxUploads", maxUploads.toLong())
            text("IsTruncated", isTruncated)
            if (encoding == KeyEncoding.URL) text("EncodingType", "url")
            for (entry in uploads) {
                element("Upload") {
                    encodedText("Key", entry.key.toByteArray(), encoding)
                    text("UploadId", entry.uploadId)
                    text("Initiated", entry.initiated)
                    text("StorageClass", "STANDARD")
                }
            }
        }

    /**
     * One row of `ListObjectVersions`, which is a `<Version>` or a `<DeleteMarker>`.
     *
     * Two element names for one shape, minus the two fields a tombstone has no answer for: it
     * holds no bytes, so it has neither `ETag` nor `Size`. Emitting them as zero would let a client
     * compare a tombstone against an empty object and find them equal.
     */
    data class VersionEntry(
        val key: ObjectKey,
        val versionId: String,
        val isLatest: Boolean,
        val lastModified: String,
        val eTag: String,
        val size: Long,
        val deleteMarker: Boolean,
        val storageClass: String = "STANDARD",
    )

    /**
     * `<ListVersionsResult>` — every version of every key, tombstones included.
     *
     * It was a second rendering of the ordinary listing until M-107, and answering it that way was
     * wrong in a particular direction: the document said the bucket held one version of everything
     * and had never deleted anything. Well-formed, and unfalsifiable from the outside — which is
     * the shape of answer this file exists to avoid.
     *
     * It is still answered for a bucket that has no versioning at all, and that part has not
     * changed: real S3 answers it there too, with the objects at `VersionId=null`. Found by the
     * compatibility suite, whose cleanup fixture calls this before every single test — a `501`
     * here errored 837 of 838 tests without any of them reaching the thing they check.
     */
    @Suppress("LongParameterList")
    fun listVersionsResult(
        bucket: String,
        prefix: ByteArray?,
        delimiter: ByteArray?,
        keyMarker: ByteArray?,
        nextKeyMarker: ByteArray?,
        versionIdMarker: String?,
        nextVersionIdMarker: String?,
        maxKeys: Int,
        isTruncated: Boolean,
        versions: List<VersionEntry>,
        commonPrefixes: List<ByteArray>,
        encoding: KeyEncoding,
    ): ByteArray =
        XmlWriter(1024 + versions.size * 160).document("ListVersionsResult") {
            text("Name", bucket)
            encodedText("Prefix", prefix ?: ByteArray(0), encoding)
            if (delimiter != null) encodedText("Delimiter", delimiter, encoding)
            // The markers travel even when they are empty, and this is the cheapest field in the
            // whole protocol to get wrong: botocore's paginator reads `NextKeyMarker` out of the
            // response and puts it straight into the next request. Absent, it sends `None`, which
            // its own parameter validation then rejects — inside `nuke_prefixed_buckets`, which
            // the compatibility suite runs as a fixture around **every** test. Dozens of cases
            // that had nothing to do with versioning failed in their teardown and passed when run
            // alone, which is exactly the shape of failure that gets blamed on the harness.
            encodedText("KeyMarker", keyMarker ?: ByteArray(0), encoding)
            encodedText("NextKeyMarker", nextKeyMarker ?: ByteArray(0), encoding)
            text("VersionIdMarker", versionIdMarker ?: "")
            text("NextVersionIdMarker", nextVersionIdMarker ?: "")
            text("MaxKeys", maxKeys.toLong())
            text("IsTruncated", isTruncated)
            if (encoding == KeyEncoding.URL) text("EncodingType", "url")
            for (entry in versions) {
                element(if (entry.deleteMarker) "DeleteMarker" else "Version") {
                    encodedText("Key", entry.key.toByteArray(), encoding)
                    text("VersionId", entry.versionId)
                    text("IsLatest", entry.isLatest)
                    text("LastModified", entry.lastModified)
                    if (!entry.deleteMarker) {
                        text("ETag", entry.eTag)
                        text("Size", entry.size)
                        text("StorageClass", entry.storageClass)
                    }
                }
            }
            for (commonPrefix in commonPrefixes) {
                element("CommonPrefixes") {
                    encodedText("Prefix", commonPrefix, encoding)
                }
            }
        }

    /**
     * `<DeleteResult>`. In quiet mode the successes are left out and only the failures are
     * reported; the caller decides, this only writes what it is given.
     */
    fun deleteResult(
        deleted: List<DeletedEntry>,
        errors: List<DeleteError>,
    ): ByteArray =
        XmlWriter(256 + (deleted.size + errors.size) * 64).document("DeleteResult") {
            for (entry in deleted) {
                element("Deleted") {
                    raw("Key", entry.key.toByteArray())
                    entry.versionId?.let { text("VersionId", it) }
                    if (entry.deleteMarker) {
                        text("DeleteMarker", true)
                        entry.deleteMarkerVersionId?.let { text("DeleteMarkerVersionId", it) }
                    }
                }
            }
            for (entry in errors) {
                element("Error") {
                    raw("Key", entry.key.toByteArray())
                    entry.versionId?.let { text("VersionId", it) }
                    text("Code", entry.code)
                    text("Message", entry.message)
                }
            }
        }

    private fun XmlWriter.encodedText(
        name: String,
        value: ByteArray,
        encoding: KeyEncoding,
    ) {
        when (encoding) {
            KeyEncoding.URL -> text(name, UriCodec.encodeForListing(value))
            KeyEncoding.NONE -> raw(name, value)
        }
    }
}
