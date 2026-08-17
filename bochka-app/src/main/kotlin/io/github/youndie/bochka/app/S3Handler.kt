package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.http.HttpHandler
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.http.HttpResponse
import io.github.youndie.bochka.s3.ObjectKeyRules
import io.github.youndie.bochka.s3.S3ErrorResponse
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.sigv4.AwsChunkedDecoder
import io.github.youndie.bochka.s3.sigv4.CanonicalRequest
import io.github.youndie.bochka.s3.sigv4.S3Error
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import io.github.youndie.bochka.s3.xml.S3Documents
import io.github.youndie.bochka.s3.xml.S3Requests
import java.io.ByteArrayOutputStream
import java.nio.file.Files
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
    private val store: DraftStore,
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
            val mustExist = route !is S3Router.Route.CreateBucket && route !is S3Router.Route.ListBuckets
            if (mustExist && !store.hasBucket(bucket)) {
                return error(head, S3Error.NO_SUCH_BUCKET, bucket = bucket)
            }
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
            is S3Router.Route.ListObjectsV2 -> listObjects(head, route.bucket)
            is S3Router.Route.ListObjects -> listObjects(head, route.bucket)
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
     * A listing good enough for a client to see what it just uploaded, and no more.
     *
     * No `delimiter`, no `continuation-token`, no `CommonPrefixes` — those are M6, and the jump
     * over a group that makes `delimiter` affordable is the whole reason they are their own
     * milestone. `IsTruncated` is answered honestly rather than always false.
     */
    private fun listObjects(
        head: HttpRequestParser.Head,
        bucket: String,
    ): HttpResponse {
        val params = queryParams(head.query)
        val prefix = params["prefix"] ?: ""
        val maxKeys = params["max-keys"]?.toIntOrNull() ?: 1000
        val encoding =
            if (params["encoding-type"] == "url") S3Documents.KeyEncoding.URL else S3Documents.KeyEncoding.NONE

        val found = store.list(bucket, prefix.toByteArray(), maxKeys + 1)
        val page = found.take(maxKeys)

        return xml(
            S3Documents.listBucketResult(
                bucket = bucket,
                prefix = prefix,
                delimiter = null,
                maxKeys = maxKeys,
                keyCount = page.size,
                isTruncated = found.size > page.size,
                contents =
                    page.map { (key, stored) ->
                        S3Documents.ObjectEntry(key, timestamp(stored.lastModified), stored.eTag, stored.size)
                    },
                commonPrefixes = emptyList(),
                encoding = encoding,
            ),
        )
    }

    /** The same listing, every object at version `null` — see [S3Documents.listVersionsResult]. */
    private fun listVersions(
        head: HttpRequestParser.Head,
        bucket: String,
    ): HttpResponse {
        val params = queryParams(head.query)
        val prefix = params["prefix"] ?: ""
        val maxKeys = params["max-keys"]?.toIntOrNull() ?: 1000
        val encoding =
            if (params["encoding-type"] == "url") S3Documents.KeyEncoding.URL else S3Documents.KeyEncoding.NONE

        val found = store.list(bucket, prefix.toByteArray(), maxKeys + 1)
        val page = found.take(maxKeys)

        return xml(
            S3Documents.listVersionsResult(
                bucket = bucket,
                prefix = prefix,
                maxKeys = maxKeys,
                isTruncated = found.size > page.size,
                contents =
                    page.map { (key, stored) ->
                        S3Documents.ObjectEntry(key, timestamp(stored.lastModified), stored.eTag, stored.size)
                    },
                encoding = encoding,
            ),
        )
    }

    private suspend fun putObject(
        head: HttpRequestParser.Head,
        route: S3Router.Route.PutObject,
        verification: SignatureVerifier.Result.Ok,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val payloadHash = verification.payloadHash
        val streaming = payloadHash in SignatureVerifier.ALL_STREAMING

        return try {
            val stored =
                if (streaming) {
                    putStreaming(head, route, verification, body)
                } else {
                    putWhole(head, route, payloadHash, body)
                }
            HttpResponse(200, "OK", headers = listOf("ETag" to stored.eTag))
        } catch (e: AwsChunkedDecoder.MalformedBody) {
            error(head, e.error, detail = e.message)
        } catch (e: PayloadMismatch) {
            error(head, S3Error.CONTENT_SHA256_MISMATCH, detail = e.message)
        }
    }

    private class PayloadMismatch(
        message: String,
    ) : RuntimeException(message)

    private suspend fun putStreaming(
        head: HttpRequestParser.Head,
        route: S3Router.Route.PutObject,
        verification: SignatureVerifier.Result.Ok,
        body: HttpHandler.RequestBody,
    ): DraftStore.StoredObject {
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

        var decoder: AwsChunkedDecoder? = null
        val stored =
            store.put(route.bucket, route.key, head.header("content-type")) { out ->
                val sink =
                    AwsChunkedDecoder(
                        decodedLength = declared,
                        signing = verification.chunkSigning,
                        expectedTrailers = announced,
                    ) { bytes, offset, length -> out.write(bytes, offset, length) }
                decoder = sink
                body.forEach { bytes, offset, length -> sink.feed(bytes, offset, length) }
                sink.finish()
            }
        checkNotNull(decoder)
        return stored
    }

    private suspend fun putWhole(
        head: HttpRequestParser.Head,
        route: S3Router.Route.PutObject,
        payloadHash: String,
        body: HttpHandler.RequestBody,
    ): DraftStore.StoredObject {
        val digest = MessageDigest.getInstance("SHA-256")
        val verify = payloadHash != SignatureVerifier.UNSIGNED_PAYLOAD

        val stored =
            store.put(route.bucket, route.key, head.header("content-type")) { out ->
                body.forEach { bytes, offset, length ->
                    out.write(bytes, offset, length)
                    if (verify) digest.update(bytes, offset, length)
                }
            }

        if (verify) {
            val computed = digest.digest().joinToString("") { "%02x".format(it) }
            if (computed != payloadHash) {
                store.delete(route.bucket, route.key)
                throw PayloadMismatch("client said $payloadHash, computed $computed")
            }
        }
        return stored
    }

    private fun getObject(
        head: HttpRequestParser.Head,
        bucket: String,
        key: ObjectKey,
        withBody: Boolean = true,
    ): HttpResponse {
        val stored = store.get(bucket, key) ?: return error(head, S3Error.NO_SUCH_KEY, key = key, bucket = bucket)
        val headers =
            buildList {
                add("ETag" to stored.eTag)
                add("Last-Modified" to httpDate(stored.lastModified))
                add("Accept-Ranges" to "bytes")
                stored.contentType?.let { add("Content-Type" to it) }
            }
        // Reading the object into a byte array is the draft part: M-59 hands the socket to
        // `transferTo` instead, which is the reason the connection exposes its channel at all.
        val bytes = if (withBody) Files.readAllBytes(stored.path) else ByteArray(0)
        // HEAD announces the length of the body it is not sending. Answering 0 made rclone treat a
        // perfectly good upload as corrupted and delete it — and only rclone, because it is the one
        // client of the four that checks the size afterwards.
        return HttpResponse(
            200,
            "OK",
            headers = headers,
            body = bytes,
            contentLength = if (withBody) null else stored.size,
        )
    }

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
            is S3Router.Route.DeleteObjects -> route.bucket
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
        return HttpResponse(rendered.status, reasonFor(rendered.status), rendered.headers, body)
    }

    private fun xml(body: ByteArray): HttpResponse =
        HttpResponse(200, "OK", headers = listOf("Content-Type" to "application/xml"), body = body)

    private fun queryParams(query: String): Map<String, String> =
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { token ->
                val eq = token.indexOf('=')
                val name = if (eq < 0) token else token.substring(0, eq)
                val value = if (eq < 0) "" else token.substring(eq + 1)
                name to
                    String(
                        io.github.youndie.bochka.s3.UriCodec
                            .decode(value, plusIsSpace = true),
                    )
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
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                501 -> "Not Implemented"
                else -> "Error"
            }
    }
}
