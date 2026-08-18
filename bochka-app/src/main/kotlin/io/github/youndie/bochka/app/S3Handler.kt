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
import io.github.youndie.bochka.s3.PostForm
import io.github.youndie.bochka.s3.PostPolicy
import io.github.youndie.bochka.s3.PostSignature
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
    /**
     * Hand a whole-object read to the terminator in front instead of sending it here.
     *
     * `null` — and the default — means this server sends every byte itself, which is the only
     * thing that works when nothing shares its filesystem. Set to an internal location prefix
     * (`/bochka-data`) and a `GET` of a whole object answers with `X-Accel-Redirect` and no body;
     * nginx then serves the file, which is the difference between relaying a socket and sending a
     * file — 2.408 processor-seconds per gibibyte against 0.898, measured (`docs/measurements.md`).
     *
     * **The cost is real and is why this is off by default.** The terminator must be able to read
     * the data directory, so it stops being a process that only speaks TLS; the two must sit on
     * one filesystem, which forbids putting the terminator on another host; and the bytes leave
     * without this server seeing them go.
     */
    private val accelRedirect: String? = null,
) : HttpHandler {
    override fun screen(head: HttpRequestParser.Head): HttpResponse? {
        val route = route(head)
        if (route is S3Router.Route.NotImplemented) {
            return error(head, S3Error.NOT_IMPLEMENTED, detail = "not implemented: ${route.what}")
        }

        // Preflight подписи не имеет и иметь не может: браузер шлёт `OPTIONS` до всякой
        // авторизации. Исключение сделано **по маршруту**, а не по методу вообще, чтобы
        // «неподписанный» не расползлось на что-нибудь ещё.
        if (route !is S3Router.Route.Preflight && route !is S3Router.Route.PostObject) {
            when (val verification = verifier.verify(head.toSignedRequest())) {
                is SignatureVerifier.Result.Failure -> return error(head, verification.error, verification)
                is SignatureVerifier.Result.Ok -> Unit
            }
        }

        // A body whose length is stated nowhere is refused before it is read, like everything else
        // decided from the head. `Content-Length` for an ordinary body, `X-Amz-Decoded-Content-Length`
        // for a streaming one; a chunked upload with neither could only be stored at whatever length
        // happened to arrive.
        // `Transfer-Encoding: chunked` counts as a stated length: the framing carries it, chunk by
        // chunk, and the body ends where it says it ends. Refusing it was over-reading the rule —
        // the rule is that a body whose length is stated **nowhere** cannot be stored, and a
        // chunked body states it.
        // `UploadPartCopy` is absent from this list on purpose: its bytes come from another object,
        // so it has no body and a `Content-Length` would describe nothing.
        if ((route is S3Router.Route.PutObject || route is S3Router.Route.UploadPart) &&
            head.contentLength == null &&
            !head.isChunked &&
            head.header("x-amz-decoded-content-length") == null
        ) {
            return error(head, S3Error.MISSING_CONTENT_LENGTH, key = keyOf(route), bucket = bucketOf(route))
        }

        // A part number outside 1..10 000 is a request that could never be completed
        // (`s3-service-2.json:1604`), and it is visible from the head.
        val partNumber =
            (route as? S3Router.Route.UploadPart)?.partNumber
                ?: (route as? S3Router.Route.UploadPartCopy)?.partNumber
        if (partNumber != null && partNumber !in 1..S3Requests.MAX_PARTS) {
            return error(
                head,
                S3Error.INVALID_ARGUMENT,
                detail = "part number $partNumber is outside 1..${S3Requests.MAX_PARTS}",
                key = keyOf(route),
                bucket = bucketOf(route),
            )
        }

        // An upload nobody started cannot take a part, and refusing here costs no body (§1.2).
        // The exception is a completion of an upload that already completed: that one is a retry
        // rather than a mistake, and `handle` answers it with what the first attempt answered
        // (M-88). Screening it out here would mean the retry never reaches the code that knows.
        uploadIdOf(route)?.let { uploadId ->
            val retriedCompletion =
                route is S3Router.Route.CompleteMultipartUpload && store.completion(uploadId) != null
            if (store.upload(uploadId) == null && !retriedCompletion) {
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
        // Preflight сюда доходит неподписанным (см. `screen`), так что верификация ленивая:
        // приведение типа на неподписанном запросе упало бы на маршруте, которому подпись
        // не нужна.
        val verification by lazy { verifier.verify(head.toSignedRequest()) as SignatureVerifier.Result.Ok }

        return when (route) {
            is S3Router.Route.ListBuckets -> {
                listBuckets(head)
            }

            is S3Router.Route.CreateBucket -> {
                createBucket(route.bucket)
            }

            is S3Router.Route.DeleteBucket -> {
                deleteBucket(head, route.bucket)
            }

            is S3Router.Route.HeadBucket -> {
                HttpResponse(200, "OK")
            }

            is S3Router.Route.GetBucketLocation -> {
                bucketLocation()
            }

            is S3Router.Route.ListObjectsV2 -> {
                listObjectsV2(head, route.bucket)
            }

            is S3Router.Route.ListObjects -> {
                listObjectsV1(head, route.bucket)
            }

            is S3Router.Route.ListObjectVersions -> {
                listVersions(head, route.bucket)
            }

            is S3Router.Route.PutObject -> {
                putObject(head, route, verification, body)
            }

            is S3Router.Route.PostObject -> {
                postObject(head, route.bucket, body)
            }

            is S3Router.Route.CopyObject -> {
                copyObject(head, route)
            }

            is S3Router.Route.GetObject -> {
                getObject(head, route.bucket, route.key, partNumber = route.partNumber, versionId = route.versionId)
            }

            is S3Router.Route.GetObjectAttributes -> {
                objectAttributes(head, route)
            }

            is S3Router.Route.BucketSubresource -> {
                bucketSubresource(head, route, body)
            }

            is S3Router.Route.ObjectTagging -> {
                objectTagging(head, route, body)
            }

            is S3Router.Route.Preflight -> {
                preflight(head, route)
            }

            is S3Router.Route.HeadObject -> {
                getObject(
                    head,
                    route.bucket,
                    route.key,
                    withBody = false,
                    partNumber = route.partNumber,
                    versionId = route.versionId,
                )
            }

            is S3Router.Route.DeleteObject -> {
                deleteObject(head, route.bucket, route.key, route.versionId)
            }

            is S3Router.Route.DeleteObjects -> {
                deleteObjects(head, route.bucket, body)
            }

            is S3Router.Route.CreateMultipartUpload -> {
                createUpload(head, route)
            }

            is S3Router.Route.UploadPart -> {
                uploadPart(head, route, verification, body)
            }

            is S3Router.Route.UploadPartCopy -> {
                uploadPartCopy(head, route)
            }

            is S3Router.Route.ListParts -> {
                listParts(head, route)
            }

            is S3Router.Route.AbortMultipartUpload -> {
                abortUpload(head, route)
            }

            is S3Router.Route.CompleteMultipartUpload -> {
                completeUpload(head, route, body)
            }

            is S3Router.Route.ListMultipartUploads -> {
                listUploads(head, route.bucket)
            }

            else -> {
                error(head, S3Error.NOT_IMPLEMENTED, detail = "not implemented: $route")
            }
        }
    }

    /**
     * `PUT` with `x-amz-copy-source` — the same object, under another key, without leaving the
     * server.
     *
     * `x-amz-metadata-directive` decides whose metadata the copy carries: `COPY` (the default)
     * keeps the source's, `REPLACE` takes the request's. The one refusal that reads as arbitrary
     * is a copy of an object onto itself with `COPY` — S3 rejects it because the request asks for
     * nothing, and a client that meant to rewrite metadata and forgot the directive would
     * otherwise be told it worked.
     */
    private fun copyObject(
        head: HttpRequestParser.Head,
        route: S3Router.Route.CopyObject,
    ): HttpResponse {
        if (!store.hasBucket(route.sourceBucket)) {
            return error(head, S3Error.NO_SUCH_BUCKET, bucket = route.sourceBucket)
        }
        val source =
            store.get(route.sourceBucket, route.sourceKey)
                ?: return error(head, S3Error.NO_SUCH_KEY, key = route.sourceKey, bucket = route.sourceBucket)

        // The conditions on a copy are about the **source**, and they have their own header names
        // for that reason: `If-Match` on this request would be a condition on the target, which is
        // a different question and one S3 answers separately.
        head.header("x-amz-copy-source-if-match")?.let { condition ->
            if (!matches(condition, source.eTag)) {
                return error(head, S3Error.PRECONDITION_FAILED, key = route.sourceKey, bucket = route.sourceBucket)
            }
        }
        head.header("x-amz-copy-source-if-none-match")?.let { condition ->
            if (matches(condition, source.eTag)) {
                return error(head, S3Error.PRECONDITION_FAILED, key = route.sourceKey, bucket = route.sourceBucket)
            }
        }

        val replacing = head.header("x-amz-metadata-directive").equals("REPLACE", ignoreCase = true)
        val sameObject = route.sourceBucket == route.bucket && route.sourceKey == route.key
        if (sameObject && !replacing) {
            return error(
                head,
                S3Error.INVALID_REQUEST,
                detail = "copying an object onto itself needs x-amz-metadata-directive: REPLACE",
                key = route.key,
                bucket = route.bucket,
            )
        }

        val metadata = if (replacing) ObjectHeaders.read(head.headers) else source.metadata
        val stored = store.copy(source, route.bucket, route.key, metadata)
        return xml(
            S3Documents.copyObjectResult(stored.eTag, timestamp(stored.lastModified)),
        )
    }

    // --- multipart upload (M7) ----------------------------------------------------------------

    private fun createUpload(
        head: HttpRequestParser.Head,
        route: S3Router.Route.CreateMultipartUpload,
    ): HttpResponse {
        // The metadata travels on this request and not on the parts: the parts are bytes, the
        // object is what they become, and only this request knows anything about the object. The
        // checksum algorithm is the same kind of thing — chosen once, here, and binding on every
        // part and on the completion minutes later.
        val algorithm =
            head.header("x-amz-checksum-algorithm")?.trim()?.lowercase()?.takeIf { name ->
                PayloadChecksums.Algorithm.entries.any { it.id == name }
            }
        val checksumType = head.header("x-amz-checksum-type")?.trim()?.uppercase()
        val upload =
            store.createUpload(
                route.bucket,
                route.key,
                ObjectHeaders.read(head.headers),
                algorithm,
                checksumType,
            )

        // Both go back as **headers**: `CreateMultipartUploadOutput` gives them
        // `location: header` in `s3-service-2.json`, and an SDK reads them off the response before
        // it sends a single part.
        val headers =
            buildList {
                algorithm?.let { add("x-amz-checksum-algorithm" to it.uppercase()) }
                add("x-amz-checksum-type" to typeOf(upload))
            }.takeIf { algorithm != null } ?: emptyList()
        return xml(S3Documents.initiateMultipartUploadResult(route.bucket, route.key, upload.id))
            .let { it.copy(headers = it.headers + headers) }
    }

    /**
     * Which of the two checksum types this upload is, stated rather than guessed.
     *
     * `COMPOSITE` is S3's default when a client names an algorithm and not a type, so an upload
     * that said nothing gets the answer it would get from S3 — and the value is decided here, at
     * the start, because the completion has to give the same answer and it happens much later.
     */
    private fun typeOf(upload: ObjectStore.Upload): String =
        upload.checksumType?.takeIf { it == "FULL_OBJECT" } ?: "COMPOSITE"

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

            val part = store.commitPart(route.uploadId, route.partNumber, staged, checksums.stored())
            staged = null
            // `UploadPartOutput.ChecksumSHA256` and its siblings are headers, and an SDK reads
            // them straight back into the part list it will send at completion.
            HttpResponse(200, "OK", headers = checksumHeaders(part.checksum) + ("ETag" to part.eTag))
        } catch (e: AwsChunkedDecoder.MalformedBody) {
            error(head, e.error, detail = e.message)
        } catch (e: ObjectStore.CompletionRefused) {
            error(head, refusalOf(e.reason), detail = e.message)
        } finally {
            staged?.let(store::discard)
        }
    }

    /**
     * A part whose bytes come from another object.
     *
     * `x-amz-copy-source-range` narrows the source, and it is the one place a malformed range is
     * an **error** rather than something to ignore: on a `GET` an unparseable `Range` means "send
     * everything" (RFC 9110 §14.2), but here it decides what the part *is*, and guessing would
     * assemble an object out of bytes nobody asked for.
     */
    private fun uploadPartCopy(
        head: HttpRequestParser.Head,
        route: S3Router.Route.UploadPartCopy,
    ): HttpResponse {
        if (!store.hasBucket(route.sourceBucket)) {
            return error(head, S3Error.NO_SUCH_BUCKET, bucket = route.sourceBucket)
        }
        val source =
            store.get(route.sourceBucket, route.sourceKey)
                ?: return error(head, S3Error.NO_SUCH_KEY, key = route.sourceKey, bucket = route.sourceBucket)

        val requested = head.header("x-amz-copy-source-range")
        val slice =
            if (requested == null) {
                0L to source.size
            } else {
                when (val range = ByteRanges.resolve(requested, source.size)) {
                    // Exact, not clamped: `ByteRanges` trims an end past the object because that is
                    // what a `GET` must do (RFC 9110 §14.1.2), and a copy must not — a client
                    // asking for `bytes=0-99` of an eighty-byte object would get a part of a size
                    // it did not choose, and find out at completion or never.
                    is ByteRanges.Resolved.Satisfiable -> {
                        if (endsPastTheObject(requested, source.size)) {
                            // `InvalidRange` and not `InvalidArgument`: the argument is a
                            // well-formed range, it just is not one this object has. A client told
                            // its argument is invalid looks at how it spelled the header; one told
                            // the range is invalid looks at the object, which is where the answer
                            // is (`test_multipart_copy_invalid_range`).
                            return error(
                                head,
                                S3Error.INVALID_RANGE,
                                detail = "x-amz-copy-source-range '$requested' runs past ${source.size} bytes",
                                key = route.key,
                                bucket = route.bucket,
                            )
                        }
                        range.start to range.length
                    }

                    // A range that parses and the object does not have. Same answer as the
                    // clamped case above and for the same reason: the argument is fine, the object
                    // is smaller than it says.
                    is ByteRanges.Resolved.Unsatisfiable -> {
                        return error(
                            head,
                            S3Error.INVALID_RANGE,
                            detail = "x-amz-copy-source-range '$requested' is outside ${source.size} bytes",
                            key = route.key,
                            bucket = route.bucket,
                        )
                    }

                    // `Whole` means the header did not parse at all, and a copy of everything is
                    // not what "bytes=abc" asked for. That one really is a bad argument.
                    else -> {
                        return error(
                            head,
                            S3Error.INVALID_ARGUMENT,
                            detail = "x-amz-copy-source-range '$requested' does not name a range",
                            key = route.key,
                            bucket = route.bucket,
                        )
                    }
                }
            }

        return try {
            val part =
                store.commitPart(
                    route.uploadId,
                    route.partNumber,
                    store.stagePartFrom(source, slice.first, slice.second),
                )
            xml(S3Documents.copyPartResult(part.eTag, timestamp(part.lastModified)))
        } catch (e: ObjectStore.CompletionRefused) {
            error(head, refusalOf(e.reason), detail = e.message, key = route.key, bucket = route.bucket)
        }
    }

    /** `bytes=0-99` of an eighty-byte object: satisfiable for a read, and not a range to copy. */
    private fun endsPastTheObject(
        requested: String,
        size: Long,
    ): Boolean =
        requested
            .substringAfter('-')
            .trim()
            .toLongOrNull()
            ?.let { it >= size } == true

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
                        S3Documents.PartEntry(
                            partNumber = it.number,
                            lastModified = timestamp(it.lastModified),
                            eTag = it.eTag,
                            size = it.size,
                            checksum = it.checksum?.let { checksum -> checksum.algorithm to checksum.value },
                        )
                    },
                // Which algorithm the parts were checksummed with, so a client resuming an upload
                // knows what to send for the ones it has not sent yet.
                checksumAlgorithm = store.upload(route.uploadId)?.checksumAlgorithm,
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

        // The completion may state what the finished object will hash to, and that is checked
        // rather than echoed: `test_multipart_checksum_sha256` sends `ChecksumSHA256: bad` and
        // requires `400 BadDigest`, which is the only answer that tells the client its parts and
        // its expectation disagree.
        val stated = PayloadChecksums.of { head.header(it) }
        stated.rejection?.let {
            return error(head, it.error, detail = it.detail, key = route.key, bucket = route.bucket)
        }

        return try {
            val stored =
                store.completeUpload(
                    route.uploadId,
                    requested.map { it.partNumber to it.eTag },
                    { parts, checksumType ->
                        PayloadChecksums.ofParts(
                            parts.map { PayloadChecksums.Piece(it.size, it.checksum) },
                            checksumType,
                        )
                    },
                    writePrecondition(head),
                    stated.stored(),
                )
            completed(route, stored.eTag, stored.metadata.checksum)
        } catch (e: ObjectStore.CompletionRefused) {
            // A repeat of a completion that already happened is answered with what it answered
            // (M-88). The upload is gone by then, so the store cannot be asked again — but what it
            // produced is remembered, and that is the question the client is really asking: an SDK
            // whose connection dropped while the answer travelled back cannot tell success from
            // failure, and `NoSuchUpload` tells it the upload was lost when the object is on disk.
            val already = store.completion(route.uploadId)
            if (e.reason == ObjectStore.CompletionRefused.Reason.NO_SUCH_UPLOAD && already != null) {
                completed(route, already.eTag, already.checksum)
            } else {
                error(head, refusalOf(e.reason), detail = e.message, key = route.key, bucket = route.bucket)
            }
        } catch (e: ObjectStore.PreconditionFailed) {
            error(head, refusalOf(e.outcome), detail = e.message, key = route.key, bucket = route.bucket)
        } catch (e: MalformedCondition) {
            error(head, S3Error.INVALID_ARGUMENT, detail = e.message, key = route.key, bucket = route.bucket)
        } catch (e: ObjectStore.CeilingExceeded) {
            error(head, S3Error.INSUFFICIENT_STORAGE, detail = e.message, key = route.key, bucket = route.bucket)
        }
    }

    private fun completed(
        route: S3Router.Route.CompleteMultipartUpload,
        eTag: String,
        checksum: Metadata.Checksum?,
    ): HttpResponse =
        xml(
            S3Documents.completeMultipartUploadResult(
                location = "/${route.bucket}/${UriCodec.encodePath(route.key.toByteArray())}",
                bucket = route.bucket,
                key = route.key,
                eTag = eTag,
                checksum = checksum?.let { it.algorithm to it.value },
                checksumType = checksum?.let(::typeOf),
            ),
        )

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
            ObjectStore.CompletionRefused.Reason.CHECKSUM_MISMATCH -> S3Error.BAD_DIGEST
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        prefix.size <= size && prefix.indices.all { this[it] == prefix[it] }

    /**
     * The answer to a bug in this server, which is a `500` with an error document like any other.
     *
     * `InternalError` is in the model and clients retry on it, which is the right behaviour for a
     * failure that might not repeat. The detail travels in the body deliberately: this is a store
     * somebody runs themselves, and the person reading the response is the person who can fix it.
     */
    override fun failed(
        head: HttpRequestParser.Head,
        cause: Throwable,
    ): HttpResponse = error(head, S3Error.INTERNAL_ERROR, detail = "${cause::class.simpleName}: ${cause.message}")

    /**
     * `GET /` — every bucket, in name order, a page at a time.
     *
     * The page is opt-in: without `max-buckets` the whole list goes out, which is what every
     * client that predates the parameter expects. With it, the token is the name of the last
     * bucket sent — buckets are ordered by name and names are unique, so a position in that order
     * **is** a name and needs nothing opaque behind it.
     */
    private fun listBuckets(head: HttpRequestParser.Head): HttpResponse {
        val params = rawQueryParams(head.query)
        val prefix = params["prefix"]?.let { String(it) }
        val after = params["continuation-token"]?.let { String(it) }
        val maxBuckets = params["max-buckets"]?.let { String(it) }?.toIntOrNull()

        val matching =
            store
                .bucketList()
                .filter { prefix == null || it.name.startsWith(prefix) }
                .filter { after == null || it.name > after }
        val page = if (maxBuckets != null) matching.take(maxBuckets) else matching

        return xml(
            S3Documents.listAllMyBucketsResult(
                buckets = page.map { S3Documents.BucketEntry(it.name, timestamp(it.createdAt)) },
                ownerId = OWNER,
                ownerDisplayName = OWNER,
                nextContinuationToken = page.lastOrNull()?.name?.takeIf { matching.size > page.size },
                prefix = prefix,
            ),
        )
    }

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
                continuationToken = request.continuationToken ?: request.emptyContinuationToken,
                nextContinuationToken = page.nextAfter?.let(ListingRequest::encodeToken),
                startAfter = request.startAfterParameter,
                owner = if (request.fetchOwner) OWNER else null,
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
                keyMarker = request.keyMarker,
                nextKeyMarker = page.nextAfter,
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
     * Upload by an HTML form, which is the one operation whose authorisation cannot precede its
     * body (M-100…M-102).
     *
     * Everywhere else a refusal costs the head alone (research, §1.2.2). Here the policy and the
     * signature are fields **inside** the multipart body, so the body has to arrive before it can
     * be refused. What is left is a ceiling — [MAX_FORM_BODY] — and the client's own
     * `content-length-range`, checked as soon as the file's length is known.
     *
     * The order below is the whole of it, and it is the order that matters: parse, verify the
     * signature over the policy **as it arrived**, check the policy against the fields, and only
     * then store. Checking the policy first would let an unsigned form decide which error it gets.
     */
    private suspend fun postObject(
        head: HttpRequestParser.Head,
        bucket: String,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val boundary =
            PostForm.boundaryOf(head.header("content-type"))
                ?: return error(head, S3Error.MALFORMED_POST_REQUEST, detail = "no multipart boundary", bucket = bucket)

        val collected = ByteArrayOutputStream()
        try {
            body.forEach { bytes, offset, length ->
                if (collected.size() + length > MAX_FORM_BODY) {
                    throw FormTooLarge("the form exceeds $MAX_FORM_BODY bytes")
                }
                collected.write(bytes, offset, length)
            }
        } catch (e: FormTooLarge) {
            return error(head, S3Error.ENTITY_TOO_LARGE, detail = e.message, bucket = bucket)
        }
        val raw = collected.toByteArray()

        val form: PostForm.Parsed
        val accessKeyId: String
        val keyText: String
        try {
            form = PostForm.parse(raw, boundary)
            val policyField =
                form["policy"]
                    ?: throw PostSignature.Refused(S3Error.ACCESS_DENIED, "the form carries no policy")
            accessKeyId = PostSignature.verify(form.fields, policyField, verifier.credentials, verifier.region)
            // `${'$'}{filename}` is substituted **before** the policy is checked, and the order is the
            // whole of `test_post_object_set_key_from_filename:2167`: the form sends
            // `key=${'$'}{filename}` under a policy demanding `starts-with ${'$'}key, "foo"`, and the literal
            // `${'$'}{filename}` starts with no such thing. What the signer constrained is the key the
            // object gets, not the template the browser sent.
            keyText = (form["key"] ?: "").replace("${'$'}{filename}", form.fileName ?: "")
            // `bucket` is a condition of every policy and a field of no form: it travels in the
            // URL, and the form has no reason to repeat it. Checking the fields alone refuses
            // every real form with "the policy requires field bucket" — which is how this was
            // found. What the condition is actually about is the bucket that was posted to, so
            // that is what it gets.
            // `key` is substituted in, not added: a form that sent no key must not acquire one
            // here. It did acquire one, and the policy then refused the field nobody sent —
            // `test_post_object_no_key_specified:2448` got `403` where it wanted `400`, which is
            // the difference between "you may not" and "your form is incomplete".
            val checked =
                form.fields + mapOf("bucket" to bucket) +
                    (if (form["key"] != null) mapOf("key" to keyText) else emptyMap())
            PostPolicy.check(
                PostPolicy.decode(policyField),
                checked,
                form.fileLength.toLong(),
                java.time.Instant.now(),
            )
        } catch (e: PostForm.Malformed) {
            return error(head, e.error, detail = e.message, bucket = bucket)
        } catch (e: PostSignature.Refused) {
            return error(head, e.error, detail = e.message, bucket = bucket)
        } catch (e: PostPolicy.Refused) {
            return error(head, e.error, detail = e.message, bucket = bucket)
        }

        // `acl` is a boundary, not a field to store. `private` is what this server does, so it can
        // be accepted truthfully; `public-read-write` (`test_post_object_anonymous_request:1948`)
        // would promise anonymous access that does not exist here, and a promise kept nowhere is
        // found out by a leak rather than by an error — the same rule that refuses `PutBucketAcl`.
        val acl = form["acl"]
        if (acl != null && acl != "private") {
            return error(
                head,
                S3Error.ACCESS_DENIED,
                detail = "acl '$acl' is not honoured by this server; only 'private' is",
                bucket = bucket,
            )
        }

        if (keyText.isEmpty()) {
            return error(head, S3Error.MALFORMED_POST_REQUEST, detail = "the form has no key", bucket = bucket)
        }
        val key = ObjectKey(keyText.toByteArray(Charsets.UTF_8))
        ObjectKeyRules.check(key)?.let { rejection ->
            return error(
                head,
                if (rejection == ObjectKeyRules.Rejection.TOO_LONG) S3Error.KEY_TOO_LONG else S3Error.INVALID_URI,
                detail = rejection.message,
                bucket = bucket,
            )
        }

        // The field names of a form are the header names of a `PUT`, which is why the metadata is
        // read by the same code — `content-type`, `cache-control`, `x-amz-meta-*` and the rest.
        val metadata = ObjectHeaders.read(form.fields.map { it.key to it.value })

        var staged: ObjectStore.Staged? = null
        return try {
            staged = store.stage { out -> out.write(raw, form.fileOffset, form.fileLength) }
            val stored = store.commit(bucket, key, metadata, staged, ObjectStore.Precondition())
            staged = null
            formSuccess(form, bucket, key, stored.eTag, accessKeyId)
        } catch (e: ObjectStore.CeilingExceeded) {
            error(head, S3Error.INSUFFICIENT_STORAGE, detail = e.message, key = key, bucket = bucket)
        } finally {
            staged?.let(store::discard)
        }
    }

    /**
     * What a browser gets back, which is not what an SDK gets back.
     *
     * A form post comes from a page, and the page has to end up somewhere: `success_action_redirect`
     * sends it there with `303`, `success_action_status: 201` answers with a document naming the
     * object, and everything else is `204` — an empty answer the browser shows as nothing happening.
     * Guessing `200` here would leave the user staring at a blank page.
     */
    private fun formSuccess(
        form: PostForm.Parsed,
        bucket: String,
        key: ObjectKey,
        eTag: String,
        accessKeyId: String,
    ): HttpResponse {
        val location = "/$bucket/$key"
        val redirect = form["success_action_redirect"] ?: form["redirect"]
        if (redirect != null) {
            val separator = if ('?' in redirect) '&' else '?'
            // The ETag keeps its quotes, percent-encoded: `test_post_object_success_redirect_action:2321`
            // compares the landing URL against `etag=%22…%22`. They are part of the value, not
            // punctuation around it, and stripping them hands the page a tag that matches no object.
            val target = "$redirect${separator}bucket=$bucket&key=$key&etag=%22${eTag.trim('"')}%22"
            return HttpResponse(303, "See Other", headers = listOf("Location" to target, "ETag" to eTag))
        }

        val status = form["success_action_status"]?.trim()?.toIntOrNull()
        if (status == 201) {
            val document = S3Documents.postResponse(location, bucket, key.toString(), eTag)
            return HttpResponse(
                201,
                "Created",
                headers = listOf("Content-Type" to "application/xml", "ETag" to eTag),
                body = document,
            )
        }
        // The access key is named nowhere in the answer, and that is deliberate: the browser that
        // posted the form is not the party that signed the policy, and telling it whose key it
        // used would hand a page the identity of the service behind it.
        check(accessKeyId.isNotEmpty())
        return HttpResponse(
            if (status ==
                200
            ) {
                200
            } else {
                204
            },
            if (status == 200) "OK" else "No Content",
            headers = listOf("ETag" to eTag),
        )
    }

    /** A form larger than the server will hold in memory, which is a refusal and not a crash. */
    private class FormTooLarge(
        override val message: String,
    ) : RuntimeException(message)

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

            val stored = store.commit(route.bucket, route.key, metadata, staged, writePrecondition(head))
            staged = null
            HttpResponse(
                200,
                "OK",
                headers = checksumHeaders(metadata.checksum) + ("ETag" to stored.eTag) + versionHeader(stored),
            )
        } catch (e: AwsChunkedDecoder.MalformedBody) {
            error(head, e.error, detail = e.message)
        } catch (e: ObjectStore.CeilingExceeded) {
            error(head, S3Error.INSUFFICIENT_STORAGE, detail = e.message, key = route.key, bucket = route.bucket)
        } catch (e: ObjectStore.PreconditionFailed) {
            error(head, refusalOf(e.outcome), detail = e.message, key = route.key, bucket = route.bucket)
        } catch (e: MalformedCondition) {
            error(head, S3Error.INVALID_ARGUMENT, detail = e.message, key = route.key, bucket = route.bucket)
        } finally {
            // Anything still staged at this point is a body that was written and refused.
            staged?.let(store::discard)
        }
    }

    /** The same three conditions as the headers of a single `DELETE`, read off one `<Object>`. */
    private fun conditionOf(target: S3Requests.Target): ObjectStore.Precondition =
        ObjectStore.Precondition(
            ifMatch = target.eTag?.let(::tags),
            size = target.size?.let { it.toLongOrNull() ?: throw MalformedCondition("<Size> is not a number: '$it'") },
            lastModifiedMillis =
                target.lastModifiedTime?.let {
                    parseHttpDate(it) ?: throw MalformedCondition("<LastModifiedTime> is not a date: '$it'")
                },
        )

    /**
     * Which refusal a failed write precondition is.
     *
     * `MISMATCH` is `412`: the client described an object and described it wrongly. `ABSENT` is
     * `404`, and the difference is not pedantry — a `412` tells a client to go and re-read the
     * ETag of an object that does not exist, which is advice it cannot act on. The suite pins both
     * (`test_put_object_if_match`, `test_put_object_ifmatch_nonexisted_failed`).
     */
    private fun refusalOf(outcome: ObjectStore.Outcome): S3Error =
        when (outcome) {
            ObjectStore.Outcome.ABSENT -> S3Error.NO_SUCH_KEY
            else -> S3Error.PRECONDITION_FAILED
        }

    /**
     * `If-Match` and `If-None-Match` on a write.
     *
     * The same two headers as a conditional read and a different meaning: on a read they say "do
     * not send me what I have", on a write they say "do not overwrite what I did not see".
     * `If-None-Match: *` is how a client creates a key only if nobody else got there first, and it
     * is only worth anything if the check and the write cannot be separated — which is why this
     * ends up in [ObjectStore.commit] rather than being decided here.
     */
    private fun writePrecondition(head: HttpRequestParser.Head): ObjectStore.Precondition =
        ObjectStore.Precondition(
            ifMatch = head.header("if-match")?.let(::tags),
            ifNoneMatch = head.header("if-none-match")?.let(::tags),
            // `x-amz-if-match-size` and `x-amz-if-match-last-modified-time` (M-84): conditions on
            // what the object **is** rather than on the tag it was handed. They combine with
            // `If-Match` and with each other — `s3-service-2.json` says so in the documentation of
            // `DeleteObjectRequest.members.IfMatchSize` — which is why they are fields of one
            // object and not a choice between three.
            size =
                head.header("x-amz-if-match-size")?.let {
                    it.trim().toLongOrNull()
                        ?: throw MalformedCondition("x-amz-if-match-size is not a number: '$it'")
                },
            lastModifiedMillis =
                head.header("x-amz-if-match-last-modified-time")?.let {
                    parseHttpDate(it)
                        ?: throw MalformedCondition("x-amz-if-match-last-modified-time is not a date: '$it'")
                },
        )

    /**
     * A condition that cannot be read at all, which is not a condition that did not hold.
     *
     * Answering `412` to a malformed header tells the client its object changed underneath it, and
     * sends it off to re-read an object that is exactly as it left it. `400` says what is true.
     */
    private class MalformedCondition(
        override val message: String,
    ) : RuntimeException(message)

    /** `rfc822`, which is the format `s3-service-2.json` gives these timestamps and `Last-Modified` uses. */
    private fun parseHttpDate(value: String): Long? =
        try {
            java.time.ZonedDateTime
                .parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }

    private fun tags(condition: String): List<String> = condition.split(',').map { it.trim().removePrefix("W/").trim() }

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
     * `x-amz-version-id`, when there is a version worth naming.
     *
     * Absent for [ObjectStore.NULL_VERSION], and that is S3's behaviour rather than a shortcut:
     * every object in a bucket that never versioned is at version `null`, and a header repeating
     * that on every response would tell a client its bucket is versioning when it is not.
     */
    private fun versionHeader(stored: ObjectStore.Stored): List<Pair<String, String>> =
        if (stored.versionId == ObjectStore.NULL_VERSION) {
            emptyList()
        } else {
            listOf("x-amz-version-id" to stored.versionId)
        }

    /**
     * What a read lands on when the newest version is a tombstone.
     *
     * Two different answers, and the difference is which question was asked. Reading the key
     * asked for the object: it is gone, so `404` — with `x-amz-delete-marker`, because a client
     * that does not know a marker is there cannot know the object can be brought back. Reading the
     * marker **by its id** asked for that version, and it is there; it just has no bytes, so the
     * answer is `405` rather than `404`. Answering `404` to the second would say the version does
     * not exist, and the client would stop trying to delete it.
     */
    private fun deleteMarkerRefusal(
        head: HttpRequestParser.Head,
        bucket: String,
        key: ObjectKey,
        marker: ObjectStore.Stored,
        named: Boolean,
    ): HttpResponse =
        error(
            head,
            if (named) S3Error.METHOD_NOT_ALLOWED else S3Error.NO_SUCH_KEY,
            key = key,
            bucket = bucket,
        ).let {
            it.copy(
                headers =
                    it.headers +
                        listOf(
                            "x-amz-delete-marker" to "true",
                            "x-amz-version-id" to marker.versionId,
                        ),
            )
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
        partNumber: Int? = null,
        versionId: String? = null,
    ): HttpResponse {
        // A named version is fetched whatever it is; an unnamed one goes through `get`, which
        // answers `null` for a tombstone. The two refusals below are different on purpose.
        val found = if (versionId != null) store.get(bucket, key, versionId) else store.currentVersion(bucket, key)
        if (found == null) return error(head, S3Error.NO_SUCH_KEY, key = key, bucket = bucket)
        if (found.deleteMarker) return deleteMarkerRefusal(head, bucket, key, found, named = versionId != null)
        val stored = found
        val path = store.pathOf(stored)
        val headers =
            overridden(
                head,
                buildList {
                    add("ETag" to stored.eTag)
                    add("Last-Modified" to httpDate(stored.lastModified))
                    addAll(versionHeader(stored))
                    add("Accept-Ranges" to "bytes")
                    addAll(ObjectHeaders.write(stored.metadata))
                    // Сколько тегов, а не какие: список отдаёт `?tagging`, а здесь клиенту нужно
                    // знать, стоит ли за ним идти. S3 шлёт заголовок только когда теги есть.
                    if (stored.metadata.tags.isNotEmpty()) {
                        add(
                            "x-amz-tagging-count" to
                                stored.metadata.tags.size
                                    .toString(),
                        )
                    }
                    // S3 answers with a content type whether or not one was given:
                    // `binary/octet-stream` is the model's own default, and a client that reads
                    // the header unconditionally gets an error rather than a default of its own.
                    if (stored.metadata.contentType == null) add("Content-Type" to DEFAULT_CONTENT_TYPE)
                },
            )

        conditional(head, stored)?.let { status ->
            // `304` is the one status in this server that carries no body, because HTTP says a
            // `304` has none. `412` is an error like any other and carries the error document —
            // without it botocore fails inside its own parser instead of raising the error the
            // caller is waiting for, which looks to the caller like a broken connection rather
            // than a refusal.
            return if (status == 304) {
                HttpResponse(304, "Not Modified", headers, contentLength = 0)
            } else {
                error(head, S3Error.PRECONDITION_FAILED, key = key, bucket = bucket)
            }
        }

        // `?partNumber=N` is a `Range` the client did not have to compute: the object remembers
        // where its seams were, so a resumable download asks for the piece by number. It is only
        // ever a range — the bytes were joined at completion and there is nothing else to read.
        if (partNumber != null) {
            // An object that was never assembled has exactly one part, and it is the object. S3
            // answers `partNumber=1` on an ordinary upload with the whole thing and a parts count
            // of one, which is what lets a client use one download loop for both kinds.
            val slice =
                if (stored.parts.isEmpty()) {
                    if (partNumber != 1) return error(head, S3Error.INVALID_PART, key = key, bucket = bucket)
                    0L to stored.size
                } else {
                    sliceOfPart(stored, partNumber)
                        ?: return error(head, S3Error.INVALID_PART, key = key, bucket = bucket)
                }
            return HttpResponse(
                206,
                "Partial Content",
                headers =
                    headers +
                        ("Content-Range" to "bytes ${slice.first}-${slice.first + slice.second - 1}/${stored.size}") +
                        ("x-amz-mp-parts-count" to maxOf(stored.parts.size, 1).toString()) +
                        // **That part's** checksum, not the object's. The rule is the one a `206`
                        // already taught this server: a checksum beside a response describes the
                        // bytes in that response, and the object's would be a true statement about
                        // bytes the client did not receive.
                        partChecksumIfAsked(head, stored, partNumber),
                file = if (withBody) HttpResponse.FileSlice(path, slice.first, slice.second) else null,
                contentLength = slice.second,
            )
        }

        // Handing the file to the terminator in front, when the deployment says to. Only a `GET`
        // of the whole object: a `HEAD` has no body to hand over, and `partNumber` names a slice
        // that no header can express — `X-Accel-Redirect` says which file, never which part of it.
        // A `Range` **is** handed over, because nginx applies the client's own `Range` to the
        // internal file and answers the `206` itself.
        if (accelRedirect != null && withBody && partNumber == null) {
            val relative = store.dataRoot.relativize(path)
            return HttpResponse(
                200,
                "OK",
                headers = headers + checksumIfAsked(head, stored) + ("X-Accel-Redirect" to "$accelRedirect/$relative"),
                contentLength = 0,
            )
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
                    headers = headers + checksumIfAsked(head, stored),
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

    /**
     * The conditional headers of a `GET` or `HEAD`, resolved against what is stored.
     *
     * `s3-service-2.json`, `GetObjectRequest.members`: `IfMatch`, `IfNoneMatch`, `IfModifiedSince`,
     * `IfUnmodifiedSince`. The statuses are HTTP's (RFC 9110 §13.2.2) and the precedence is too —
     * the `Match` conditions are evaluated before the date ones, and a failed `If-None-Match` on a
     * read is `304`, not an error.
     *
     * Returns the status to answer with, or `null` to carry on and serve the object.
     */
    private fun conditional(
        head: HttpRequestParser.Head,
        stored: ObjectStore.Stored,
    ): Int? {
        val eTag = stored.eTag
        head.header("if-match")?.let { condition ->
            if (!matches(condition, eTag)) return 412
        }
        head.header("if-none-match")?.let { condition ->
            if (matches(condition, eTag)) return 304
        }
        // Whole seconds: `Last-Modified` has no sub-second precision, so comparing against a
        // timestamp that does would make an object modified in the same second look newer than a
        // copy the client already has.
        val modified = stored.lastModified.epochSecond
        if (head.header("if-none-match") == null) {
            head.header("if-modified-since")?.let { since ->
                httpDateSeconds(since)?.let { if (modified <= it) return 304 }
            }
        }
        if (head.header("if-match") == null) {
            head.header("if-unmodified-since")?.let { since ->
                httpDateSeconds(since)?.let { if (modified > it) return 412 }
            }
        }
        return null
    }

    /** `*` matches anything that exists; otherwise any of the comma-separated tags, quotes and all. */
    private fun matches(
        condition: String,
        eTag: String,
    ): Boolean {
        val wanted = condition.trim()
        if (wanted == "*") return true
        return wanted.split(',').any { it.trim().removePrefix("W/").trim('"') == eTag.trim('"') }
    }

    private fun httpDateSeconds(value: String): Long? =
        runCatching {
            java.time.ZonedDateTime
                .parse(value.trim(), HTTP_DATE)
                .toEpochSecond()
        }.getOrNull()

    /**
     * Where part [number] sits in the assembled object, or `null` if there is no such part.
     *
     * The offsets are added up rather than stored, because storing them would be the same numbers
     * twice: the sizes are already there and a stored offset that disagreed with them would be a
     * second source of truth about the same bytes.
     */
    private fun sliceOfPart(
        stored: ObjectStore.Stored,
        number: Int,
    ): Pair<Long, Long>? {
        var offset = 0L
        for (part in stored.parts) {
            if (part.number == number) return offset to part.size
            offset += part.size
        }
        return null
    }

    /**
     * `response-content-type=foo/bar` and its five siblings, which replace the headers of **this**
     * answer without touching the object (M-79).
     *
     * `shapes.GetObjectRequest.members.ResponseContentType` and the rest of the family are
     * querystring parameters, and `HeadObjectRequest` has the same six. What they are for is a
     * browser: a presigned link can hand out a stored `application/octet-stream` as
     * `text/csv; charset=utf-8` with a `Content-Disposition` that names the file, and none of that
     * is a property of the object — the next reader sees what was stored.
     *
     * This is what the case behind M-79 was actually about. The task read "a Content-Type set at
     * upload came back as the default", which is what its failure message looks like
     * (`assert 'binary/octet-stream' == 'foo/bar'`) if you have not seen the request: the type was
     * never set at upload at all, it was asked for in the query.
     */
    private fun overridden(
        head: HttpRequestParser.Head,
        headers: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        // The common `GET` has no query at all, and this sits on the read path: splitting an empty
        // string to find nothing is an allocation per request for a parameter almost nobody sends.
        if (head.query.isEmpty()) return headers
        val params = rawQueryParams(head.query)
        val overrides =
            RESPONSE_OVERRIDES.mapNotNull { (parameter, header) ->
                params[parameter]?.let { header to String(it) }
            }
        if (overrides.isEmpty()) return headers
        val replaced = overrides.map { it.first.lowercase() }.toSet()
        return headers.filterNot { it.first.lowercase() in replaced } + overrides
    }

    /**
     * `GET /<bucket>/<key>?attributes` — the object's shape, without its bytes.
     *
     * `x-amz-object-attributes` names which members to answer, and only those are sent. A server
     * that answered all of them regardless would be handing a client `ObjectParts` it did not ask
     * for, which on a ten-thousand-part object is a megabyte of document instead of a line.
     */
    private fun objectAttributes(
        head: HttpRequestParser.Head,
        route: S3Router.Route.GetObjectAttributes,
    ): HttpResponse {
        val stored =
            store.get(route.bucket, route.key)
                ?: return error(head, S3Error.NO_SUCH_KEY, key = route.key, bucket = route.bucket)

        val asked =
            head
                .header("x-amz-object-attributes")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: return error(
                    head,
                    S3Error.INVALID_ARGUMENT,
                    detail = "x-amz-object-attributes names which attributes to return",
                    key = route.key,
                    bucket = route.bucket,
                )

        conditional(head, stored)?.let { status ->
            return if (status == 304) {
                HttpResponse(304, "Not Modified", contentLength = 0)
            } else {
                error(head, S3Error.PRECONDITION_FAILED, key = route.key, bucket = route.bucket)
            }
        }

        // `x-amz-max-parts` and `x-amz-part-number-marker` page the part list, and they have to:
        // `ObjectParts` on a ten-thousand-part object is a megabyte of document, which is the same
        // reason the attribute list is opt-in in the first place.
        val marker = head.header("x-amz-part-number-marker")?.trim()?.toIntOrNull() ?: 0
        val maxParts = head.header("x-amz-max-parts")?.trim()?.toIntOrNull() ?: S3Requests.MAX_PARTS
        val after = stored.parts.filter { it.number > marker }
        val page = after.take(maxParts)

        return xml(
            S3Documents.getObjectAttributesResult(
                eTag = stored.eTag.takeIf { "ETag" in asked },
                checksum =
                    stored.metadata.checksum
                        ?.takeIf { "Checksum" in asked }
                        ?.let { it.algorithm to it.value },
                checksumType =
                    stored.metadata.checksum
                        ?.takeIf { "Checksum" in asked }
                        ?.let(::typeOf),
                objectSize = stored.size.takeIf { "ObjectSize" in asked },
                storageClass = "STANDARD".takeIf { "StorageClass" in asked },
                parts =
                    page
                        .takeIf { "ObjectParts" in asked && stored.parts.isNotEmpty() }
                        ?.map {
                            S3Documents.PartEntry(
                                partNumber = it.number,
                                lastModified = timestamp(stored.lastModified),
                                eTag = it.eTag,
                                size = it.size,
                                checksum = it.checksum?.let { checksum -> checksum.algorithm to checksum.value },
                            )
                        },
                // The object's count, not the page's: a client that asked for one part still has
                // to be told how many there are, or it cannot know it is paginating.
                partsCount = stored.parts.size,
                partNumberMarker = marker,
                maxParts = maxParts,
                isTruncated = after.size > page.size,
            ),
        ).copy(headers = listOf("Content-Type" to "application/xml", "Last-Modified" to httpDate(stored.lastModified)))
    }

    /**
     * `?versioning` — the one bucket sub-resource that changes what writing does.
     *
     * Kept as store state rather than as the document that carried it, so the answer is rendered
     * from the state and cannot drift from the behaviour. `DELETE` is not a method S3 offers here:
     * versioning is switched on and suspended, never taken back off.
     */
    private suspend fun bucketVersioning(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse =
        when (route.method) {
            "GET" -> {
                xml(S3Documents.versioningResult(store.versioning(route.bucket)))
            }

            "PUT" -> {
                val collected = ByteArrayOutputStream()
                body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
                try {
                    store.setVersioning(route.bucket, S3Requests.parseVersioning(collected.toByteArray()))
                    HttpResponse(200, "OK")
                } catch (e: XmlReader.MalformedXmlException) {
                    error(head, S3Error.MALFORMED_XML, detail = e.message, bucket = route.bucket)
                }
            }

            else -> {
                error(head, S3Error.NOT_IMPLEMENTED, detail = "${route.method} ?versioning", bucket = route.bucket)
            }
        }

    /**
     * `?tagging` и `?cors` у бакета: положить, прочитать, снять.
     *
     * Документ разбирается **до** записи и хранится перерисованным, а не как пришёл: то, что
     * нельзя разобрать, не должно попасть в журнал и вернуться клиенту как настройка, а
     * перерисовка снимает вопрос, что делать с чужим форматированием.
     */
    private suspend fun bucketSubresource(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        // `versioning` is answered even when nobody set it, and that is why it leaves this branch
        // early: "no configuration" has a defined document, while "no tag set" and "no CORS rules"
        // are refusals with codes of their own. Three sub-resources, two different right answers.
        if (route.name == "versioning") return bucketVersioning(head, route, body)

        val absent = if (route.name == "tagging") S3Error.NO_SUCH_TAG_SET else S3Error.NO_SUCH_CORS_CONFIGURATION
        return when (route.method) {
            "GET" -> {
                val stored =
                    store.bucketSubresource(route.bucket, route.name)
                        ?: return error(head, absent, bucket = route.bucket)
                xml(stored)
            }

            "DELETE" -> {
                store.putBucketSubresource(route.bucket, route.name, null)
                HttpResponse(204, "No Content")
            }

            else -> {
                val collected = ByteArrayOutputStream()
                body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
                val document =
                    try {
                        if (route.name == "tagging") {
                            S3Documents.taggingResult(S3Requests.parseTagging(collected.toByteArray()))
                        } else {
                            S3Documents.corsResult(S3Requests.parseCors(collected.toByteArray()))
                        }
                    } catch (e: XmlReader.MalformedXmlException) {
                        return error(head, S3Error.MALFORMED_XML, detail = e.message, bucket = route.bucket)
                    }
                store.putBucketSubresource(route.bucket, route.name, document)
                HttpResponse(200, "OK")
            }
        }
    }

    /**
     * `?tagging` у объекта — и ответ на отсутствие здесь **другой**, чем у бакета.
     *
     * У бакета без набора — `404 NoSuchTagSet`; у объекта без тегов — `200` с пустым `TagSet`,
     * потому что сам объект есть, и `404` сказал бы неправду про него. Одно имя операции, два
     * разных правильных ответа.
     */
    private suspend fun objectTagging(
        head: HttpRequestParser.Head,
        route: S3Router.Route.ObjectTagging,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val stored =
            store.get(route.bucket, route.key)
                ?: return error(head, S3Error.NO_SUCH_KEY, key = route.key, bucket = route.bucket)

        return when (route.method) {
            "GET" -> {
                xml(S3Documents.taggingResult(stored.metadata.tags))
            }

            "DELETE" -> {
                store.setTags(route.bucket, route.key, emptyMap())
                HttpResponse(204, "No Content")
            }

            else -> {
                val collected = ByteArrayOutputStream()
                body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
                val tags =
                    try {
                        S3Requests.parseTagging(collected.toByteArray())
                    } catch (e: XmlReader.MalformedXmlException) {
                        return error(head, S3Error.MALFORMED_XML, detail = e.message, bucket = route.bucket)
                    }
                store.setTags(route.bucket, route.key, tags)
                HttpResponse(200, "OK")
            }
        }
    }

    /**
     * `OPTIONS` — preflight, единственный неподписанный ответ этого сервера.
     *
     * Отказ здесь — `403`, а не `200` без заголовков доступа. Браузер прочтёт оба одинаково,
     * а человек, отлаживающий CORS, — по-разному: «правило не подошло» против «правило подошло
     * и ничего не разрешило».
     */
    private fun preflight(
        head: HttpRequestParser.Head,
        route: S3Router.Route.Preflight,
    ): HttpResponse {
        val origin = head.header("origin") ?: return error(head, S3Error.ACCESS_DENIED, bucket = route.bucket)
        val method = head.header("access-control-request-method") ?: "GET"
        val document =
            store.bucketSubresource(route.bucket, "cors")
                ?: return error(head, S3Error.ACCESS_DENIED, bucket = route.bucket)
        val rule =
            S3Requests
                .parseCors(document)
                .matching(origin, method)
                ?: return error(head, S3Error.ACCESS_DENIED, bucket = route.bucket)

        return HttpResponse(
            200,
            "OK",
            headers =
                buildList {
                    add("Access-Control-Allow-Origin" to origin)
                    add("Access-Control-Allow-Methods" to rule.allowedMethods.joinToString(", "))
                    if (rule.allowedHeaders.isNotEmpty()) {
                        add("Access-Control-Allow-Headers" to rule.allowedHeaders.joinToString(", "))
                    }
                    if (rule.exposeHeaders.isNotEmpty()) {
                        add("Access-Control-Expose-Headers" to rule.exposeHeaders.joinToString(", "))
                    }
                    rule.maxAgeSeconds?.let { add("Access-Control-Max-Age" to it.toString()) }
                },
            contentLength = 0,
        )
    }

    /** `x-amz-checksum-<algorithm>`, echoed back exactly as the client stated it on upload. */
    private fun checksumHeaders(checksum: Metadata.Checksum?): List<Pair<String, String>> =
        checksum?.let {
            listOf(
                "x-amz-checksum-${it.algorithm}" to it.value,
                // `GetObjectOutput.ChecksumType`: which of the two things the value is. Without it
                // a client holding `a1b2c3==-3` has to infer from the suffix, and one holding a
                // full-object CRC cannot tell it from an ordinary upload's at all.
                "x-amz-checksum-type" to typeOf(it),
            )
        } ?: emptyList()

    /**
     * `COMPOSITE` when the value is a checksum of checksums, which the `-N` says, `FULL_OBJECT`
     * otherwise — and that reading is exact rather than a heuristic. A checksum with the suffix
     * describes the parts' checksums; one without describes the object's bytes, whether it was
     * assembled from three parts or uploaded in one go, and both of those are `FULL_OBJECT`.
     */
    private fun typeOf(checksum: Metadata.Checksum): String = if ('-' in checksum.value) "COMPOSITE" else "FULL_OBJECT"

    /** The checksum of the one part being read, when the object remembers one for it. */
    private fun partChecksumIfAsked(
        head: HttpRequestParser.Head,
        stored: ObjectStore.Stored,
        partNumber: Int,
    ): List<Pair<String, String>> =
        if (head.header("x-amz-checksum-mode").equals("ENABLED", ignoreCase = true)) {
            // An object with no seams has one part and it is the object, so its own checksum is
            // the right answer for `partNumber=1` — the same equivalence that lets a client use
            // one download loop for both kinds of object.
            val checksum =
                stored.parts.firstOrNull { it.number == partNumber }?.checksum
                    ?: stored.metadata.checksum.takeIf { stored.parts.isEmpty() }
            // The **value** describes this part; the **type** describes the object. A part of a
            // `COMPOSITE` object has an ordinary-looking checksum with no `-N`, and answering
            // `FULL_OBJECT` beside it would tell the client the object's checksum is one it can
            // reproduce by hashing what it downloads. It cannot.
            checksum?.let {
                listOf(
                    "x-amz-checksum-${it.algorithm}" to it.value,
                    "x-amz-checksum-type" to (stored.metadata.checksum?.let(::typeOf) ?: typeOf(it)),
                )
            } ?: emptyList()
        } else {
            emptyList()
        }

    /**
     * The checksum, but only with the whole object.
     *
     * The stored value describes every byte of the object, so answering with it beside a `206`
     * tells the client something false about the bytes it actually received — botocore checksums
     * what arrived, finds a different value and reports the server as corrupt, for correctly
     * answering the question it was asked.
     */
    private fun checksumIfAsked(
        head: HttpRequestParser.Head,
        stored: ObjectStore.Stored,
    ): List<Pair<String, String>> =
        if (head.header("x-amz-checksum-mode").equals("ENABLED", ignoreCase = true)) {
            checksumHeaders(stored.metadata.checksum)
        } else {
            emptyList()
        }

    /**
     * `POST /<bucket>?delete` — the batch delete, and the operation whose absence is felt long
     * before anybody asks for it: it is how `aws s3 rm --recursive`, `mc rm --recursive` and every
     * test-suite cleanup empties a bucket. Without it a bucket can be filled and never emptied,
     * and the compatibility suite errors every test after the first in its own teardown.
     */
    private suspend fun deleteObjects(
        head: HttpRequestParser.Head,
        bucket: String,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val collected = ByteArrayOutputStream()
        body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
        val request =
            try {
                S3Requests.parseDelete(collected.toByteArray())
            } catch (e: XmlReader.MalformedXmlException) {
                // A list of more than a thousand keys lands here, and so does a body that is not
                // XML at all. Both have to be an answer: an exception escaping the handler closes
                // the connection, and the client sees "the server hung up" rather than "your
                // request was too large" — the least actionable failure a server can produce.
                return error(head, S3Error.MALFORMED_XML, detail = e.message, bucket = bucket)
            }

        val deleted = mutableListOf<S3Documents.DeletedEntry>()
        val errors = mutableListOf<S3Documents.DeleteError>()
        for (target in request.targets) {
            // A refusal here belongs to **the key**, not to the request: the other 999 have to go
            // on being deleted, and the client has to be told which one did not. Answering `412`
            // for the batch would refuse work it never asked to be conditional. Without this the
            // suite reads `Errors` off a response that says everything was deleted and finds
            // nothing there, having been told an object it protected is gone (`KeyError: 'Errors'`).
            val precondition =
                try {
                    conditionOf(target)
                } catch (e: MalformedCondition) {
                    errors += S3Documents.DeleteError(target.key, S3Error.INVALID_ARGUMENT.code, e.message)
                    continue
                }
            try {
                store.delete(bucket, target.key, precondition)
                // Deleting what is not there is a success in S3, so every key that got this far is
                // reported deleted.
                deleted += S3Documents.DeletedEntry(target.key)
            } catch (e: ObjectStore.PreconditionFailed) {
                errors += S3Documents.DeleteError(target.key, S3Error.PRECONDITION_FAILED.code, e.message)
            }
        }
        // In quiet mode only failures are reported.
        return xml(S3Documents.deleteResult(if (request.quiet) emptyList() else deleted, errors))
    }

    private fun deleteObject(
        head: HttpRequestParser.Head,
        bucket: String,
        key: ObjectKey,
        versionId: String? = null,
    ): HttpResponse =
        try {
            // Deleting what is not there is a success in S3, and the test for that lives in the
            // contract because intuition says otherwise. It carries over to the conditional form:
            // a precondition against a key that is not there cannot fail, because there is nothing
            // for it to protect (`s3-service-2.json`, `DeleteObjectRequest.members.IfMatchSize`).
            // Naming a version is the one operation here that loses data, and it is not the
            // conditional form of the other: a precondition describes the current object, and the
            // version named may not be it. S3 has no conditional delete of a named version.
            if (versionId != null) {
                val removed =
                    store.deleteVersion(bucket, key, versionId)
                        ?: return HttpResponse(204, "No Content")
                return HttpResponse(
                    204,
                    "No Content",
                    headers =
                        buildList {
                            add("x-amz-version-id" to removed.versionId)
                            if (removed.deleteMarker) add("x-amz-delete-marker" to "true")
                        },
                )
            }
            val deletion = store.delete(bucket, key, writePrecondition(head))
            // A versioning bucket answers with the tombstone it just laid down, and says that is
            // what it is. A client that got a bare `204` here would have no way to undo the delete:
            // bringing the key back means naming that version, and this is the only place its id
            // is ever handed out.
            val headers =
                deletion.marker?.let {
                    listOf("x-amz-delete-marker" to "true", "x-amz-version-id" to it.versionId)
                } ?: emptyList()
            HttpResponse(204, "No Content", headers = headers)
        } catch (e: ObjectStore.PreconditionFailed) {
            error(head, S3Error.PRECONDITION_FAILED, detail = e.message, key = key, bucket = bucket)
        } catch (e: MalformedCondition) {
            error(head, S3Error.INVALID_ARGUMENT, detail = e.message, key = key, bucket = bucket)
        }

    private fun route(head: HttpRequestParser.Head): S3Router.Route =
        router.route(
            head.method,
            head.header("host") ?: "",
            head.path,
            head.query,
            head.header("x-amz-copy-source"),
        )

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
            is S3Router.Route.CopyObject -> route.bucket
            is S3Router.Route.GetObjectAttributes -> route.bucket
            is S3Router.Route.BucketSubresource -> route.bucket
            is S3Router.Route.ObjectTagging -> route.bucket
            is S3Router.Route.Preflight -> route.bucket
            is S3Router.Route.CreateMultipartUpload -> route.bucket
            is S3Router.Route.UploadPart -> route.bucket
            is S3Router.Route.UploadPartCopy -> route.bucket
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
            is S3Router.Route.UploadPartCopy -> route.uploadId
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
            is S3Router.Route.CopyObject -> route.key
            is S3Router.Route.GetObjectAttributes -> route.key
            is S3Router.Route.ObjectTagging -> route.key
            is S3Router.Route.CreateMultipartUpload -> route.key
            is S3Router.Route.UploadPart -> route.key
            is S3Router.Route.UploadPartCopy -> route.key
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

        /**
         * The ceiling on a form upload, and the one place this server holds a body in memory.
         *
         * Everywhere else bytes go from socket to file without being collected. A form cannot: its
         * signature is in the body, so the body exists before it is trusted, and trusting it far
         * enough to spool five gigabytes to disk would be the wrong way round. Sixteen mebibytes
         * covers what a browser form actually posts; past it the answer is `EntityTooLarge`, which
         * is the truth — the object is too large **for this path**, and `PUT` has no such limit.
         */
        const val MAX_FORM_BODY = 16 * 1024 * 1024

        /** `s3-service-2.json`, `GetObjectOutput.members.ContentType`: what S3 says when nobody said. */
        const val DEFAULT_CONTENT_TYPE = "binary/octet-stream"

        /** `response-<header>` query parameter to the header it replaces, in the model's order. */
        private val RESPONSE_OVERRIDES =
            listOf(
                "response-cache-control" to "Cache-Control",
                "response-content-disposition" to "Content-Disposition",
                "response-content-encoding" to "Content-Encoding",
                "response-content-language" to "Content-Language",
                "response-content-type" to "Content-Type",
                "response-expires" to "Expires",
            )

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
