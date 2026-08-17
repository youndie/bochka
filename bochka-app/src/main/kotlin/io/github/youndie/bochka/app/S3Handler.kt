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
        if (route is S3Router.Route.PutObject &&
            head.contentLength == null &&
            head.header("x-amz-decoded-content-length") == null
        ) {
            return error(head, S3Error.MISSING_CONTENT_LENGTH, key = route.key, bucket = route.bucket)
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
            else -> error(head, S3Error.NOT_IMPLEMENTED, detail = "not implemented: $route")
        }
    }

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
            else -> null
        }

    private fun keyOf(route: S3Router.Route): ObjectKey? =
        when (route) {
            is S3Router.Route.PutObject -> route.key
            is S3Router.Route.GetObject -> route.key
            is S3Router.Route.HeadObject -> route.key
            is S3Router.Route.DeleteObject -> route.key
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
