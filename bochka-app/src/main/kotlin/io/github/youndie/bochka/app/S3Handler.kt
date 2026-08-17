package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.http.HttpHandler
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.http.HttpResponse
import io.github.youndie.bochka.s3.BucketNameRules
import io.github.youndie.bochka.s3.ByteRanges
import io.github.youndie.bochka.s3.ListingRequest
import io.github.youndie.bochka.s3.ObjectHeaders
import io.github.youndie.bochka.s3.ObjectKeyRules
import io.github.youndie.bochka.s3.PayloadChecksums
import io.github.youndie.bochka.s3.S3ErrorResponse
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.UriCodec
import io.github.youndie.bochka.s3.sigv4.AwsChunkedDecoder
import io.github.youndie.bochka.s3.sigv4.CanonicalRequest
import io.github.youndie.bochka.s3.sigv4.S3Error
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import io.github.youndie.bochka.s3.xml.S3Documents
import io.github.youndie.bochka.s3.xml.S3Requests
import io.github.youndie.bochka.s3.xml.XmlReader
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Ties the three layers together: HTTP hands over a head, the S3 layer says what it is and whether
 * it is signed, the store keeps the bytes.
 *
 * The split between [screen] and [handle] is where the research lands (§1.2): routing, the
 * signature and the existence of the bucket are all decided from the head, so a refusal never
 * costs the body it refuses. What [handle] does is read bytes that have already been paid for.
 *
 * Routing runs twice — once to screen, once to handle — and that is on purpose: the handler is one
 * object shared by every connection, so it holds nothing between the two calls. Re-deriving a route
 * costs a string split; a field would cost a race.
 */
class S3Handler(
    private val store: ObjectStore,
    private val verifier: SignatureVerifier,
    private val router: S3Router,
) : HttpHandler {
    override fun screen(head: HttpRequestParser.Head): HttpResponse? {
        val route = route(head)
        if (route is S3Router.Route.NotImplemented) {
            return error(head, S3Error.NOT_IMPLEMENTED, detail = "not implemented: ${route.what}")
        }

        when (val verification = verifier.verify(head.toSignedRequest())) {
            is SignatureVerifier.Result.Failure -> return error(head, verification.error, verification)
            is SignatureVerifier.Result.Ok -> Unit
        }

        // A body whose length is stated nowhere is refused before it is read, like everything else
        // decided from the head. `Content-Length` for an ordinary body, `X-Amz-Decoded-Content-Length`
        // for a streaming one; a chunked upload with neither could only be stored at whatever length
        // happened to arrive.
        if ((route is S3Router.Route.PutObject || route is S3Router.Route.UploadPart) &&
            head.contentLength == null &&
            head.header("x-amz-decoded-content-length") == null
        ) {
            return error(head, S3Error.MISSING_CONTENT_LENGTH, key = keyOf(route), bucket = bucketOf(route))
        }

        // A part number outside 1..10 000 is a request that could never be completed
        // (`s3-service-2.json:1604`), and it is visible from the head.
        if (route is S3Router.Route.UploadPart && route.partNumber !in 1..S3Requests.MAX_PARTS) {
            return error(
                head,
                S3Error.INVALID_ARGUMENT,
                detail = "part number ${route.partNumber} is outside 1..${S3Requests.MAX_PARTS}",
                key = route.key,
                bucket = route.bucket,
            )
        }

        // An upload nobody started cannot take a part, and refusing here costs no body (§1.2).
        uploadIdOf(route)?.let { uploadId ->
            if (store.upload(uploadId) == null) {
                return error(head, S3Error.NO_SUCH_UPLOAD, key = keyOf(route), bucket = bucketOf(route))
            }
        }

        keyOf(route)?.let { key ->
            ObjectKeyRules.check(key)?.let { rejection ->
                return error(
                    head,
                    if (rejection == ObjectKeyRules.Rejection.TOO_LONG) S3Error.KEY_TOO_LONG else S3Error.INVALID_URI,
                    detail = rejection.message,
                )
            }
        }

        // The bucket is checked here rather than in `handle` for the same reason as the signature:
        // uploading five gigabytes into a bucket that does not exist should cost nothing.
        bucketOf(route)?.let { bucket ->
            // The name is judged before existence, because a name that cannot exist is not a
            // missing bucket — `NoSuchBucket` for `AB` would tell a client to go and create it.
            BucketNameRules.check(bucket)?.let { rejection ->
                return error(head, S3Error.INVALID_BUCKET_NAME, bucket = bucket, detail = rejection.message)
            }
            val mustExist = route !is S3Router.Route.CreateBucket && route !is S3Router.Route.ListBuckets
            if (mustExist && !store.hasBucket(bucket)) {
                return error(head, S3Error.NO_SUCH_BUCKET, bucket = bucket)
            }
        }

        if (route is S3Router.Route.PutObject) {
            PayloadChecksums.of { head.header(it) }.rejection?.let { rejection ->
                return error(head, rejection.error, detail = rejection.detail, key = route.key, bucket = route.bucket)
            }
            ObjectHeaders.checkSize(ObjectHeaders.read(head.headers))?.let { rejection ->
                return error(head, rejection.error, detail = rejection.detail, key = route.key, bucket = route.bucket)
            }
        }

        // A batch delete states a checksum of its own body or it does not happen (M-45): the body
        // is a list of things to destroy, and a request that arrives corrupted destroys different
        // objects with nothing left to notice it by.
        if (route is S3Router.Route.DeleteObjects && !PayloadChecksums.anyStated { head.header(it) }) {
            return error(head, S3Error.MISSING_CONTENT_MD5, bucket = route.bucket)
        }

        return null
    }

    override suspend fun handle(
        head: HttpRequestParser.Head,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val route = route(head)
        val verification = verifier.verify(head.toSignedRequest()) as SignatureVerifier.Result.Ok

        return when (route) {
            is S3Router.Route.ListBuckets -> listBuckets()
            is S3Router.Route.CreateBucket -> createBucket(route.bucket)
            is S3Router.Route.DeleteBucket -> deleteBucket(head, route.bucket)
            is S3Router.Route.HeadBucket -> HttpResponse(200, "OK")
            is S3Router.Route.GetBucketLocation -> bucketLocation()
            is S3Router.Route.ListObjectsV2 -> listObjectsV2(head, route.bucket)
            is S3Router.Route.ListObjects -> listObjectsV1(head, route.bucket)
            is S3Router.Route.ListObjectVersions -> listVersions(head, route.bucket)
            is S3Router.Route.PutObject -> putObject(head, route, verification, body)
            is S3Router.Route.GetObject -> getObject(head, route.bucket, route.key)
            is S3Router.Route.HeadObject -> getObject(head, route.bucket, route.key, withBody = false)
            is S3Router.Route.DeleteObject -> deleteObject(route.bucket, route.key)
            is S3Router.Route.DeleteObjects -> deleteObjects(route.bucket, body)
            is S3Router.Route.CreateMultipartUpload -> createUpload(head, route)
            is S3Router.Route.UploadPart -> uploadPart(head, route, verification, body)
            is S3Router.Route.ListParts -> listParts(head, route)
            is S3Router.Route.AbortMultipartUpload -> abortUpload(head, route)
            is S3Router.Route.CompleteMultipartUpload -> completeUpload(head, route, body)
            is S3Router.Route.ListMultipartUploads -> listUploads(head, route.bucket)
            else -> error(head, S3Error.NOT_IMPLEMENTED, detail = "not implemented: $route")
        }
    }

    // --- multipart upload (M7) ----------------------------------------------------------------

    private fun createUpload(
        head: HttpRequestParser.Head,
        route: S3Router.Route.CreateMultipartUpload,
    ): HttpResponse {
        // The metadata travels on this request and not on the parts: the parts are bytes, the
        // object is what they become, and only this request knows anything about the object.
        val upload = store.createUpload(route.bucket, route.key, ObjectHeaders.read(head.headers))
        return xml(S3Documents.initiateMultipartUploadResult(route.bucket, route.key, upload.id))
    }

    private suspend fun uploadPart(
        head: HttpRequestParser.Head,
        route: S3Router.Route.UploadPart,
        verification: SignatureVerifier.Result.Ok,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val checksums = PayloadChecksums.of { head.header(it) }
        val streaming = verification.payloadHash in SignatureVerifier.ALL_STREAMING
        val signedHash =
            if (!streaming && verification.payloadHash != SignatureVerifier.UNSIGNED_PAYLOAD) {
                MessageDigest.getInstance("SHA-256")
            } else {
                null
            }

        // A part goes through the same four framings as an object, because it is the same upload
        // path: `aws s3 cp` of a large file sends every part aws-chunked with a signature apiece.
        var staged: ObjectStore.Staged? = null
        return try {
            staged =
                if (streaming) {
                    stageStreaming(head, verification, body, checksums)
                } else {
                    stageWhole(body, checksums, signedHash)
                }
            if (signedHash != null) {
                val computed = signedHash.digest().joinToString("") { "%02x".format(it) }
                if (computed != verification.payloadHash) {
                    return error(head, S3Error.CONTENT_SHA256_MISMATCH, detail = "computed $computed")
                }
            }
            checksums.verify()?.let { return error(head, it.error, detail = it.detail) }

            val part = store.commitPart(route.uploadId, route.partNumber, staged)
            staged = null
            HttpResponse(200, "OK", headers = listOf("ETag" to part.eTag))
        } catch (e: AwsChunkedDecoder.MalformedBody) {
            error(head, e.error, detail = e.message)
        } catch (e: ObjectStore.CompletionRefused) {
            error(head, refusalOf(e.reason), detail = e.message)
        } finally {
            staged?.let(store::discard)
        }
    }

    private fun listParts(
        head: HttpRequestParser.Head,
        route: S3Router.Route.ListParts,
    ): HttpResponse {
        store.upload(route.uploadId)
            ?: return error(head, S3Error.NO_SUCH_UPLOAD, key = route.key, bucket = route.bucket)
        val parts = store.parts(route.uploadId)
        return xml(
            S3Documents.listPartsResult(
                bucket = route.bucket,
                key = route.key,
                uploadId = route.uploadId,
                partNumberMarker = 0,
                nextPartNumberMarker = parts.lastOrNull()?.number ?: 0,
                maxParts = S3Requests.MAX_PARTS,
                isTruncated = false,
                parts =
                    parts.map {
                        S3Documents.PartEntry(it.number, timestamp(it.lastModified), it.eTag, it.size)
                    },
            ),
        )
    }

    private fun abortUpload(
        head: HttpRequestParser.Head,
        route: S3Router.Route.AbortMultipartUpload,
    ): HttpResponse =
        if (store.abortUpload(route.uploadId)) {
            HttpResponse(204, "No Content")
        } else {
            error(head, S3Error.NO_SUCH_UPLOAD, key = route.key, bucket = route.bucket)
        }

    /**
     * Joins the parts, or says why it will not.
     *
     * S3 has a second shape for this response — `200 OK` with an `<Error>` in the body — because
     * the assembly can take long enough that the status has to go out before the outcome is known.
     * bochka never needs it: everything refusable is decided before a byte is copied, so the
     * status is always the true one. The compatibility suite expects exactly that (`400` with
     * `EntityTooSmall`, `test_multipart_upload_size_too_small`).
     */
    private suspend fun completeUpload(
        head: HttpRequestParser.Head,
        route: S3Router.Route.CompleteMultipartUpload,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val collected = ByteArrayOutputStream()
        body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }

        val requested =
            try {
                S3Requests.parseCompleteMultipartUpload(collected.toByteArray())
            } catch (e: XmlReader.MalformedXmlException) {
                return error(head, S3Error.MALFORMED_XML, detail = e.message, key = route.key, bucket = route.bucket)
            }

        return try {
            val stored = store.completeUpload(route.uploadId, requested.map { it.partNumber to it.eTag })
            xml(
                S3Documents.completeMultipartUploadResult(
                    location = "/${route.bucket}/${UriCodec.encodePath(route.key.toByteArray())}",
                    bucket = route.bucket,
                    key = route.key,
                    eTag = stored.eTag,
                ),
            )
        } catch (e: ObjectStore.CompletionRefused) {
            error(head, refusalOf(e.reason), detail = e.message, key = route.key, bucket = route.bucket)
        } catch (e: ObjectStore.CeilingExceeded) {
            error(head, S3Error.INSUFFICIENT_STORAGE, detail = e.message, key = route.key, bucket = route.bucket)
        }
    }

    private fun listUploads(
        head: HttpRequestParser.Head,
        bucket: String,
    ): HttpResponse {
        val request =
            try {
                ListingRequest.of(rawQueryParams(head.query))
            } catch (e: ListingRequest.Malformed) {
                return error(head, e.error, detail = e.message, bucket = bucket)
            }
        val uploads = store.uploads(bucket).filter { it.key.toByteArray().startsWith(request.prefix) }
        return xml(
            S3Documents.listMultipartUploadsResult(
                bucket = bucket,
                prefix = request.prefix,
                delimiter = request.delimiter,
                maxUploads = request.requestedMaxKeys,
                isTruncated = false,
                uploads =
                    uploads.map {
                        S3Documents.UploadEntry(it.key, it.id, timestamp(it.startedAt))
                    },
                encoding = request.encoding(),
            ),
        )
    }

    private fun refusalOf(reason: ObjectStore.CompletionRefused.Reason): S3Error =
        when (reason) {
            ObjectStore.CompletionRefused.Reason.NO_SUCH_UPLOAD -> S3Error.NO_SUCH_UPLOAD
            ObjectStore.CompletionRefused.Reason.NO_PARTS -> S3Error.INVALID_REQUEST
            ObjectStore.CompletionRefused.Reason.INVALID_PART -> S3Error.INVALID_PART
            ObjectStore.CompletionRefused.Reason.INVALID_PART_ORDER -> S3Error.INVALID_PART_ORDER
            ObjectStore.CompletionRefused.Reason.ENTITY_TOO_SMALL -> S3Error.ENTITY_TOO_SMALL
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        prefix.size <= size && prefix.indices.all { this[it] == prefix[it] }

    private fun listBuckets(): HttpResponse =
        xml(
            S3Documents.listAllMyBucketsResult(
                buckets = store.bucketNames().map { S3Documents.BucketEntry(it, timestamp(java.time.Instant.EPOCH)) },
                ownerId = OWNER,
                ownerDisplayName = OWNER,
            ),
        )

    private fun createBucket(bucket: String): HttpResponse {
        store.createBucket(bucket)
        // Creating a bucket that is already yours is a success, not a conflict — the model has
        // BucketAlreadyOwnedByYou for the AWS case, and every client treats `mb` as idempotent.
        return HttpResponse(200, "OK", headers = listOf("Location" to "/$bucket"))
    }

    private fun deleteBucket(
        head: HttpRequestParser.Head,
        bucket: String,
    ): HttpResponse =
        if (store.deleteBucket(bucket)) {
            HttpResponse(204, "No Content")
        } else {
            error(head, S3Error.BUCKET_NOT_EMPTY, bucket = bucket)
        }

    private fun bucketLocation(): HttpResponse =
        xml(
            (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                    "us-east-1</LocationConstraint>"
            ).toByteArray(),
        )

    /**
     * `ListObjectsV2` — one page, in key order, with groups rolled up.
     *
     * The walk itself is [ObjectStore.list]; what is here is the wire: which parameter says where
     * to resume, and what the next page's token looks like.
     */
    private fun listObjectsV2(
        head: HttpRequestParser.Head,
        bucket: String,
    ): HttpResponse =
        listing(head, bucket) { request, page ->
            S3Documents.listBucketResult(
                bucket = bucket,
                prefix = request.prefix,
                delimiter = request.delimiter,
                maxKeys = request.requestedMaxKeys,
                // KeyCount is the size of the page, and a rolled-up prefix is one of its entries.
                keyCount = page.size,
                isTruncated = page.isTruncated,
                contents = page.entries(),
                commonPrefixes = page.commonPrefixes,
                encoding = request.encoding(),
                continuationToken = request.continuationToken,
                nextContinuationToken = page.nextAfter?.let(ListingRequest::encodeToken),
                startAfter = request.startAfterParameter,
            )
        }

    /**
     * `ListObjects`, the first version, which old clients still speak.
     *
     * Same walk, different document: the position travels as `marker`, which is the key itself
     * rather than an opaque token.
     */
    private fun listObjectsV1(
        head: HttpRequestParser.Head,
        bucket: String,
    ): HttpResponse =
        listing(head, bucket) { request, page ->
            S3Documents.listObjectsResult(
                bucket = bucket,
                prefix = request.prefix,
                delimiter = request.delimiter,
                marker = request.marker,
                // Only with a delimiter, per the model's own note on the member: without one the
                // client continues from the last key it was given, and a page that ended on a
                // rolled-up prefix has no such key.
                nextMarker = if (request.delimiter != null) page.nextAfter else null,
                maxKeys = request.requestedMaxKeys,
                isTruncated = page.isTruncated,
                contents = page.entries(),
                commonPrefixes = page.commonPrefixes,
                encoding = request.encoding(),
            )
        }

    /** The same listing, every object at version `null` — see [S3Documents.listVersionsResult]. */
    private fun listVersions(
        head: HttpRequestParser.Head,
        bucket: String,
    ): HttpResponse =
        listing(head, bucket) { request, page ->
            S3Documents.listVersionsResult(
                bucket = bucket,
                prefix = request.prefix,
                delimiter = request.delimiter,
                maxKeys = request.requestedMaxKeys,
                isTruncated = page.isTruncated,
                contents = page.entries(),
                commonPrefixes = page.commonPrefixes,
                encoding = request.encoding(),
            )
        }

    private inline fun listing(
        head: HttpRequestParser.Head,
        bucket: String,
        document: (ListingRequest, ObjectStore.Page) -> ByteArray,
    ): HttpResponse {
        val request =
            try {
                ListingRequest.of(rawQueryParams(head.query))
            } catch (e: ListingRequest.Malformed) {
                return error(head, e.error, detail = e.message, bucket = bucket)
            }
        val page =
            store.list(
                bucket = bucket,
                prefix = request.prefix,
                delimiter = request.delimiter,
                startAfter = request.startAfter,
                maxKeys = request.maxKeys,
            )
        return xml(document(request, page))
    }

    private fun ObjectStore.Page.entries(): List<S3Documents.ObjectEntry> =
        keys.map { (key, stored) ->
            S3Documents.ObjectEntry(key, timestamp(stored.lastModified), stored.eTag, stored.size)
        }

    private fun ListingRequest.encoding(): S3Documents.KeyEncoding =
        if (encodeKeys) S3Documents.KeyEncoding.URL else S3Documents.KeyEncoding.NONE

    /**
     * The four framings collapse to two paths here, and what they share is the order.
     *
     * The bytes are staged first, then everything the client said about them is checked, and only a
     * body that survives all of it becomes the object. Verifying after committing would mean a
     * refused upload had already replaced the object that was there — the client would be told its
     * request failed and would have lost the version it had.
     */
    private suspend fun putObject(
        head: HttpRequestParser.Head,
        route: S3Router.Route.PutObject,
        verification: SignatureVerifier.Result.Ok,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val payloadHash = verification.payloadHash
        val streaming = payloadHash in SignatureVerifier.ALL_STREAMING
        val checksums = PayloadChecksums.of { head.header(it) }
        val metadata = ObjectHeaders.read(head.headers).copy(checksum = checksums.stored())
        // The signed hash of a whole body is verified here rather than in the signature layer: it
        // is a statement about bytes, and the bytes only exist once they have been read.
        val signedHash =
            if (!streaming && payloadHash != SignatureVerifier.UNSIGNED_PAYLOAD) {
                MessageDigest.getInstance("SHA-256")
            } else {
                null
            }

        var staged: ObjectStore.Staged? = null
        return try {
            staged =
                if (streaming) {
                    stageStreaming(head, verification, body, checksums)
                } else {
                    stageWhole(body, checksums, signedHash)
                }

            if (signedHash != null) {
                val computed = signedHash.digest().joinToString("") { "%02x".format(it) }
                if (computed != payloadHash) {
                    return error(
                        head,
                        S3Error.CONTENT_SHA256_MISMATCH,
                        detail = "client said $payloadHash, computed $computed",
                        key = route.key,
                        bucket = route.bucket,
                    )
                }
            }
            checksums.verify()?.let { rejection ->
                return error(head, rejection.error, detail = rejection.detail, key = route.key, bucket = route.bucket)
            }

            val stored = store.commit(route.bucket, route.key, metadata, staged)
            staged = null
            HttpResponse(200, "OK", headers = checksumHeaders(metadata) + ("ETag" to stored.eTag))
        } catch (e: AwsChunkedDecoder.MalformedBody) {
            error(head, e.error, detail = e.message)
        } catch (e: ObjectStore.CeilingExceeded) {
            error(head, S3Error.INSUFFICIENT_STORAGE, detail = e.message, key = route.key, bucket = route.bucket)
        } finally {
            // Anything still staged at this point is a body that was written and refused.
            staged?.let(store::discard)
        }
    }

    private suspend fun stageStreaming(
        head: HttpRequestParser.Head,
        verification: SignatureVerifier.Result.Ok,
        body: HttpHandler.RequestBody,
        checksums: PayloadChecksums,
    ): ObjectStore.Staged {
        // The real length of the object is here rather than in Content-Length, which describes the
        // framing and which the client removed anyway (§1.1).
        val declared =
            head.header("x-amz-decoded-content-length")?.trim()?.toLongOrNull()
                ?: throw AwsChunkedDecoder.MalformedBody(
                    S3Error.INCOMPLETE_BODY,
                    "a streaming upload without x-amz-decoded-content-length",
                )
        val announced =
            head
                .header("x-amz-trailer")
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        return store.stage { out ->
            val sink =
                AwsChunkedDecoder(
                    decodedLength = declared,
                    signing = verification.chunkSigning,
                    expectedTrailers = announced,
                ) { bytes, offset, length ->
                    out.write(bytes, offset, length)
                    checksums.update(bytes, offset, length)
                }
            body.forEach { bytes, offset, length -> sink.feed(bytes, offset, length) }
            sink.finish()
        }
    }

    private suspend fun stageWhole(
        body: HttpHandler.RequestBody,
        checksums: PayloadChecksums,
        signedHash: MessageDigest?,
    ): ObjectStore.Staged =
        store.stage { out ->
            body.forEach { bytes, offset, length ->
                out.write(bytes, offset, length)
                checksums.update(bytes, offset, length)
                signedHash?.update(bytes, offset, length)
            }
        }

    /**
     * `GET` and `HEAD` of an object, which are the same answer with and without its body.
     *
     * The body never becomes a byte array: what goes back is the file and the stretch of it that
     * was asked for, and the server writes it with `transferTo` (M-59). That also makes `Range`
     * cost nothing — a suffix range of a five-gigabyte object reads the bytes it returns and no
     * others.
     */
    private fun getObject(
        head: HttpRequestParser.Head,
        bucket: String,
        key: ObjectKey,
        withBody: Boolean = true,
    ): HttpResponse {
        val stored = store.get(bucket, key) ?: return error(head, S3Error.NO_SUCH_KEY, key = key, bucket = bucket)
        val path = store.pathOf(stored)
        val headers =
            buildList {
                add("ETag" to stored.eTag)
                add("Last-Modified" to httpDate(stored.lastModified))
                add("Accept-Ranges" to "bytes")
                addAll(ObjectHeaders.write(stored.metadata))
                if (head.header("x-amz-checksum-mode").equals("ENABLED", ignoreCase = true)) {
                    addAll(checksumHeaders(stored.metadata))
                }
            }

        return when (val range = ByteRanges.resolve(head.header("range"), stored.size)) {
            is ByteRanges.Resolved.Unsatisfiable -> {
                error(
                    head,
                    S3Error.INVALID_RANGE,
                    key = key,
                    bucket = bucket,
                    detail = head.header("range"),
                    extraHeaders = listOf("Content-Range" to ByteRanges.unsatisfiedRange(stored.size)),
                )
            }

            is ByteRanges.Resolved.Whole -> {
                HttpResponse(
                    200,
                    "OK",
                    headers = headers,
                    file = HttpResponse.FileSlice(path, 0, stored.size),
                    // HEAD announces the length of the body it is not sending. Answering 0 made
                    // rclone treat a perfectly good upload as corrupted and delete it — and only
                    // rclone, because it is the one client of the four that checks afterwards.
                    contentLength = stored.size,
                )
            }

            is ByteRanges.Resolved.Satisfiable -> {
                HttpResponse(
                    206,
                    "Partial Content",
                    headers = headers + ("Content-Range" to ByteRanges.contentRange(range, stored.size)),
                    file = HttpResponse.FileSlice(path, range.start, range.length),
                    contentLength = range.length,
                )
            }
        }.let { if (withBody) it else it.copy(file = null) }
    }

    /** `x-amz-checksum-<algorithm>`, echoed back exactly as the client stated it on upload. */
    private fun checksumHeaders(metadata: Metadata): List<Pair<String, String>> =
        metadata.checksum?.let { listOf("x-amz-checksum-${it.algorithm}" to it.value) } ?: emptyList()

    /**
     * `POST /<bucket>?delete` — the batch delete, and the operation whose absence is felt long
     * before anybody asks for it: it is how `aws s3 rm --recursive`, `mc rm --recursive` and every
     * test-suite cleanup empties a bucket. Without it a bucket can be filled and never emptied,
     * and the compatibility suite errors every test after the first in its own teardown.
     */
    private suspend fun deleteObjects(
        bucket: String,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val collected = ByteArrayOutputStream()
        body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
        val request = S3Requests.parseDelete(collected.toByteArray())

        val deleted = mutableListOf<S3Documents.DeletedEntry>()
        for (key in request.keys) {
            store.delete(bucket, key)
            // Deleting what is not there is a success in S3, so every key is reported deleted.
            deleted += S3Documents.DeletedEntry(key)
        }
        // In quiet mode only failures are reported, and there are none to report.
        return xml(S3Documents.deleteResult(if (request.quiet) emptyList() else deleted, emptyList()))
    }

    private fun deleteObject(
        bucket: String,
        key: ObjectKey,
    ): HttpResponse {
        // Deleting what is not there is a success in S3, and the test for that lives in the
        // contract because intuition says otherwise.
        store.delete(bucket, key)
        return HttpResponse(204, "No Content")
    }

    private fun route(head: HttpRequestParser.Head): S3Router.Route =
        router.route(head.method, head.header("host") ?: "", head.path, head.query)

    private fun bucketOf(route: S3Router.Route): String? =
        when (route) {
            is S3Router.Route.CreateBucket -> route.bucket
            is S3Router.Route.DeleteBucket -> route.bucket
            is S3Router.Route.HeadBucket -> route.bucket
            is S3Router.Route.GetBucketLocation -> route.bucket
            is S3Router.Route.ListObjectsV2 -> route.bucket
            is S3Router.Route.ListObjectVersions -> route.bucket
            is S3Router.Route.ListObjects -> route.bucket
            is S3Router.Route.DeleteObjects -> route.bucket
            is S3Router.Route.PutObject -> route.bucket
            is S3Router.Route.GetObject -> route.bucket
            is S3Router.Route.HeadObject -> route.bucket
            is S3Router.Route.DeleteObject -> route.bucket
            is S3Router.Route.CreateMultipartUpload -> route.bucket
            is S3Router.Route.UploadPart -> route.bucket
            is S3Router.Route.CompleteMultipartUpload -> route.bucket
            is S3Router.Route.AbortMultipartUpload -> route.bucket
            is S3Router.Route.ListParts -> route.bucket
            is S3Router.Route.ListMultipartUploads -> route.bucket
            else -> null
        }

    /** The upload a request names, when it names one — what has to exist before the body is read. */
    private fun uploadIdOf(route: S3Router.Route): String? =
        when (route) {
            is S3Router.Route.UploadPart -> route.uploadId
            is S3Router.Route.CompleteMultipartUpload -> route.uploadId
            is S3Router.Route.AbortMultipartUpload -> route.uploadId
            is S3Router.Route.ListParts -> route.uploadId
            else -> null
        }

    private fun keyOf(route: S3Router.Route): ObjectKey? =
        when (route) {
            is S3Router.Route.PutObject -> route.key
            is S3Router.Route.GetObject -> route.key
            is S3Router.Route.HeadObject -> route.key
            is S3Router.Route.DeleteObject -> route.key
            is S3Router.Route.CreateMultipartUpload -> route.key
            is S3Router.Route.UploadPart -> route.key
            is S3Router.Route.CompleteMultipartUpload -> route.key
            is S3Router.Route.AbortMultipartUpload -> route.key
            is S3Router.Route.ListParts -> route.key
            else -> null
        }

    private fun HttpRequestParser.Head.toSignedRequest(): CanonicalRequest.Request =
        CanonicalRequest.Request(method = method, path = path, query = query, headers = headers)

    private fun error(
        head: HttpRequestParser.Head,
        error: S3Error,
        verification: SignatureVerifier.Result.Failure? = null,
        key: ObjectKey? = null,
        bucket: String? = null,
        detail: String? = null,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): HttpResponse {
        val rendered = S3ErrorResponse.render(error, resource = head.path, key = key, bucket = bucket, detail = detail)
        val body =
            if (verification?.canonicalRequest != null) {
                // M-19: the client has nothing to compare against otherwise. Appended rather than
                // put in the XML because it is a debugging aid, not a field of the error shape.
                val extra =
                    ByteArrayOutputStream().apply {
                        write(rendered.body)
                        write("\n<!--\nCanonicalRequest:\n${verification.canonicalRequest}\n-->".toByteArray())
                    }
                extra.toByteArray()
            } else {
                rendered.body
            }
        return HttpResponse(rendered.status, reasonFor(rendered.status), rendered.headers + extraHeaders, body)
    }

    private fun xml(body: ByteArray): HttpResponse =
        HttpResponse(200, "OK", headers = listOf("Content-Type" to "application/xml"), body = body)

    /**
     * Query parameters with their values as bytes.
     *
     * A prefix, a delimiter and a marker are all pieces of keys, and a key is a byte string that
     * need not be valid UTF-8 (Р3). Decoding them to `String` on the way in would replace whatever
     * did not decode with `U+FFFD`, and the listing would then be bounded by a prefix the client
     * never sent.
     */
    private fun rawQueryParams(query: String): Map<String, ByteArray> =
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { token ->
                val eq = token.indexOf('=')
                val name = if (eq < 0) token else token.substring(0, eq)
                val value = if (eq < 0) "" else token.substring(eq + 1)
                String(UriCodec.decode(name, plusIsSpace = true)) to UriCodec.decode(value, plusIsSpace = true)
            }

    private fun timestamp(instant: java.time.Instant): String = ISO.format(instant)

    private fun httpDate(instant: java.time.Instant): String = HTTP_DATE.format(instant)

    private companion object {
        const val OWNER = "bochka"

        val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        val HTTP_DATE: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.ENGLISH)
                .withZone(ZoneOffset.UTC)

        fun reasonFor(status: Int): String =
            when (status) {
                200 -> "OK"
                204 -> "No Content"
                206 -> "Partial Content"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                411 -> "Length Required"
                416 -> "Requested Range Not Satisfiable"
                501 -> "Not Implemented"
                else -> "Error"
            }
    }
}
