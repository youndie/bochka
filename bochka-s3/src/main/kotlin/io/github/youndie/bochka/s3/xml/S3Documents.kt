package io.github.youndie.bochka.s3.xml

import io.github.youndie.bochka.core.ObjectKey
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
    )

    data class DeletedEntry(
        val key: ObjectKey,
    )

    data class DeleteError(
        val key: ObjectKey,
        val code: String,
        val message: String,
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
        prefix: String?,
        delimiter: String?,
        maxKeys: Int,
        keyCount: Int,
        isTruncated: Boolean,
        contents: List<ObjectEntry>,
        commonPrefixes: List<ByteArray>,
        encoding: KeyEncoding,
        continuationToken: String? = null,
        nextContinuationToken: String? = null,
        startAfter: String? = null,
    ): ByteArray =
        XmlWriter(1024 + contents.size * 128).document("ListBucketResult") {
            text("Name", bucket)
            // Prefix is always present, empty when not asked for — clients read it back.
            encodedText("Prefix", (prefix ?: "").toByteArray(), encoding)
            if (delimiter != null) encodedText("Delimiter", delimiter.toByteArray(), encoding)
            text("MaxKeys", maxKeys.toLong())
            text("KeyCount", keyCount.toLong())
            text("IsTruncated", isTruncated)
            if (encoding == KeyEncoding.URL) text("EncodingType", "url")
            text("ContinuationToken", continuationToken)
            text("NextContinuationToken", nextContinuationToken)
            if (startAfter != null) encodedText("StartAfter", startAfter.toByteArray(), encoding)
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

    fun listAllMyBucketsResult(
        buckets: List<BucketEntry>,
        ownerId: String,
        ownerDisplayName: String,
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

    fun completeMultipartUploadResult(
        location: String,
        bucket: String,
        key: ObjectKey,
        eTag: String,
    ): ByteArray =
        XmlWriter(256).document("CompleteMultipartUploadResult") {
            text("Location", location)
            text("Bucket", bucket)
            raw("Key", key.toByteArray())
            text("ETag", eTag)
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
            for (part in parts) {
                element("Part") {
                    text("PartNumber", part.partNumber.toLong())
                    text("LastModified", part.lastModified)
                    text("ETag", part.eTag)
                    text("Size", part.size)
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
                }
            }
            for (entry in errors) {
                element("Error") {
                    raw("Key", entry.key.toByteArray())
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
