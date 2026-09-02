package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.http.HttpHandler
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.http.HttpResponse
import io.github.youndie.bochka.s3.AccessControl
import io.github.youndie.bochka.s3.BucketLogging
import io.github.youndie.bochka.s3.BucketNameRules
import io.github.youndie.bochka.s3.BucketPolicy
import io.github.youndie.bochka.s3.ByteRanges
import io.github.youndie.bochka.s3.Lifecycle
import io.github.youndie.bochka.s3.Lifecycles
import io.github.youndie.bochka.s3.ListingRequest
import io.github.youndie.bochka.s3.ObjectHeaders
import io.github.youndie.bochka.s3.ObjectKeyRules
import io.github.youndie.bochka.s3.PayloadChecksums
import io.github.youndie.bochka.s3.PolicyStatus
import io.github.youndie.bochka.s3.PostForm
import io.github.youndie.bochka.s3.PostPolicy
import io.github.youndie.bochka.s3.PostSignature
import io.github.youndie.bochka.s3.PublicAccessBlock
import io.github.youndie.bochka.s3.S3ErrorResponse
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.SseC
import io.github.youndie.bochka.s3.TagRules
import io.github.youndie.bochka.s3.UriCodec
import io.github.youndie.bochka.s3.sigv4.AwsChunkedDecoder
import io.github.youndie.bochka.s3.sigv4.CanonicalRequest
import io.github.youndie.bochka.s3.sigv4.ChunkSigning
import io.github.youndie.bochka.s3.sigv4.KeyScope
import io.github.youndie.bochka.s3.sigv4.S3Error
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import io.github.youndie.bochka.s3.xml.S3Documents
import io.github.youndie.bochka.s3.xml.S3Requests
import io.github.youndie.bochka.s3.xml.XmlReader
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Duration
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
    /**
     * How long a lifecycle rule's "day" lasts.
     *
     * Twenty-four hours, and nothing else in a shipped build. Shorter in the embedded mode and in a
     * run of the foreign suite: a rule saying "delete after a day" is otherwise untestable, and "an
     * S3 you start inside a test" is the niche the README names. It is needed here for exactly one
     * thing — so that `x-amz-expiration` promises the same term the sweep will later delete on.
     */
    private val lifecycleDay: Duration = Lifecycle.DAY,
    /**
     * Whether a request carrying no credentials is allowed to reach the access model at all (M28).
     *
     * Off, and the default is the point rather than caution. Until this milestone "no signature
     * means 403" was one branch anybody could read; with it on, whether a stranger may read an
     * object becomes a computation, and a mistake in a computation of that kind is a hole rather
     * than a difference of opinion with S3. A deployment that has no public objects should never
     * run that computation at all.
     *
     * It can only ever take away, which is the same rule the key scope follows: with it on, an
     * unsigned request still has to be allowed by the object's or the bucket's ACL, and those are
     * `private` unless somebody said otherwise. With it off, no ACL matters — the answer is `403`.
     */
    private val anonymous: Boolean = false,
) : HttpHandler {
    private val lifecycles = Lifecycles(store)

    /**
     * Who is asking, and how the body is framed — the two things a request carries past the head.
     *
     * A type of its own since M28, because until then they arrived together as a verified
     * signature and now they do not: a request may carry no credentials and still have a body to
     * read. `accessKeyId` is `null` for exactly that request and for no other — a signature that
     * failed never gets here, `screen` refuses it.
     */
    private data class Caller(
        val accessKeyId: String?,
        val payloadHash: String,
        val chunkSigning: ChunkSigning? = null,
    )

    /**
     * Who owns a version an unsigned request creates (M28).
     *
     * Not nobody, and that is the point rather than a detail. In this model `owner == null` means
     * "written before the model existed, so no model applies" — an object with no owner is open to
     * every key. A `public-read-write` bucket would therefore turn each anonymous write into an
     * object nobody can close, which is a hole made by two correct rules meeting.
     *
     * The bucket's owner instead: a request that named nobody has no identity to own anything, and
     * the bucket's owner is who is accountable for what lands there. It is the same reasoning
     * `bucket-owner-full-control` exists for, arrived at from the other side.
     */
    private fun ownerFor(
        bucket: String,
        accessKeyId: String?,
    ): String? = accessKeyId ?: store.bucketOwner(bucket)

    /**
     * The key on a route an unsigned request can never reach.
     *
     * `screen` refuses those before a handler is chosen — `ListBuckets` has no bucket whose ACL
     * could grant anything, and creating a bucket is not a question an ACL answers. This says so
     * out loud instead of a `!!`: if the invariant ever breaks, the failure is a `500` naming the
     * route, not an operation quietly performed for nobody.
     */
    private fun Caller.somebody(route: S3Router.Route): String =
        accessKeyId ?: error("screen let an unsigned request reach $route, which no acl can grant")

    /**
     * Re-derives the caller for [handle], which is a second verification of the same head.
     *
     * Twice on purpose (see the note on [screen]): the handler is one object shared by every
     * connection, so nothing may be carried between the two calls in a field.
     */
    private fun callerOf(head: HttpRequestParser.Head): Caller =
        when (val verified = verifier.verify(head.toSignedRequest())) {
            is SignatureVerifier.Result.Ok -> {
                Caller(verified.accessKeyId, verified.payloadHash, verified.chunkSigning)
            }

            // No credentials, and `screen` has already decided whether that is allowed at all. What
            // is left is the framing, which the head states on its own: an unsigned body cannot be
            // one of the signed shapes, so there is no chain to seed.
            SignatureVerifier.Result.Anonymous -> {
                Caller(
                    accessKeyId = null,
                    payloadHash =
                        head.header("x-amz-content-sha256")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: SignatureVerifier.UNSIGNED_PAYLOAD,
                )
            }

            is SignatureVerifier.Result.Failure -> {
                error("handle reached a request screen refused: ${verified.error}")
            }
        }

    override fun screen(head: HttpRequestParser.Head): HttpResponse? = withCors(head, screened(head))

    override suspend fun handle(
        head: HttpRequestParser.Head,
        body: HttpHandler.RequestBody,
    ): HttpResponse = withCors(head, handled(head, body))!!

    override fun failed(
        head: HttpRequestParser.Head,
        cause: Throwable,
    ): HttpResponse = withCors(head, failedWith(head, cause))!!

    private fun screened(head: HttpRequestParser.Head): HttpResponse? {
        val route = route(head)
        if (route is S3Router.Route.NotImplemented) {
            return error(head, S3Error.NOT_IMPLEMENTED, detail = "not implemented: ${route.what}")
        }

        // A preflight carries no signature and cannot: the browser sends `OPTIONS` before any
        // authorisation. The exemption is made **per route** rather than per method, so that
        // "unsigned" does not spread to anything else.
        if (route !is S3Router.Route.Preflight &&
            route !is S3Router.Route.PostObject &&
            route !is S3Router.Route.Health
        ) {
            when (val verification = verifier.verify(head.toSignedRequest())) {
                is SignatureVerifier.Result.Failure -> {
                    return error(head, verification.error, verification)
                }

                is SignatureVerifier.Result.Ok -> {
                    scopeRefusal(head, route, verification.accessKeyId)?.let { return it }
                    aclRefusal(head, route, verification.accessKeyId)?.let { return it }
                    statedAclRefusal(head)?.let { return it }
                    publicAclRefusal(head, route)?.let { return it }
                }

                is SignatureVerifier.Result.Anonymous -> {
                    // Nobody claimed to be anybody. With the switch off that is the end of it, and
                    // the answer is the one this server has always given.
                    if (!anonymous) {
                        return error(head, S3Error.ACCESS_DENIED, detail = "no credentials on the request")
                    }
                    // With it on, the same gate decides — the one the signed path goes through, not
                    // a second one written for this case. A separate path is how the two drift, and
                    // a permission model that drifts leaks in the direction nobody is testing.
                    //
                    // `scopeRefusal` is absent because a scope narrows a **key**, and there is no
                    // key here; the switch above is what a deployment narrows this with.
                    aclRefusal(head, route, accessKeyId = null)?.let { return it }
                    statedAclRefusal(head)?.let { return it }
                    publicAclRefusal(head, route)?.let { return it }
                }
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

        // The customer key on a part, checked here and not where the part is stored (M-189).
        //
        // **The difference is a hang.** A refusal written after the body has started arriving means
        // the server answers and closes while the client is still pushing five mebibytes: both
        // sides then wait, and the suite reports a timeout rather than a refusal. This repository
        // has the lesson written down from the other direction — a test that sent four mebibytes to
        // prove the body was not read hung the run — and every head-decided refusal lives here for
        // exactly that reason.
        if (route is S3Router.Route.UploadPart) {
            val presented =
                try {
                    SseC.of { name -> head.header(name) }
                } catch (refused: SseC.Refused) {
                    return error(head, refused.error, detail = refused.detail)
                }
            val wanted = store.upload(route.uploadId)?.encryption
            if (wanted != null && presented?.keyMd5 != wanted.keyMd5) {
                return error(
                    head,
                    S3Error.INVALID_ARGUMENT,
                    detail = "this upload was started with a customer key and this part does not carry it",
                )
            }
            if (wanted == null && presented != null) {
                return error(
                    head,
                    S3Error.INVALID_ARGUMENT,
                    detail = "this upload was not started with a customer key",
                )
            }
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
        }

        // Everything the head says about the object it is about to carry, judged before the body.
        // `CreateMultipartUpload` is here beside `PutObject` because it takes the same headers and
        // starts an upload that would otherwise carry the bad ones to its completion.
        if (route is S3Router.Route.PutObject || route is S3Router.Route.CreateMultipartUpload) {
            val stated =
                try {
                    ObjectHeaders.read(head.headers)
                } catch (e: ObjectHeaders.Malformed) {
                    return error(
                        head,
                        S3Error.INVALID_TAG,
                        detail = e.message,
                        key = keyOf(route),
                        bucket = bucketOf(route),
                    )
                }
            ObjectHeaders.check(stated)?.let { rejection ->
                return error(
                    head,
                    rejection.error,
                    detail = rejection.detail,
                    key = keyOf(route),
                    bucket = bucketOf(route),
                )
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

    private suspend fun handled(
        head: HttpRequestParser.Head,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val route = route(head)
        // A preflight reaches this point unsigned (see `screen`), so verification is lazy: a cast
        // on an unsigned request would fail on a route that needs no signature.
        val verification by lazy { callerOf(head) }

        return when (route) {
            is S3Router.Route.ListBuckets -> {
                listBuckets(head, verification.somebody(route))
            }

            is S3Router.Route.CreateBucket -> {
                createBucket(head, route.bucket, verification.somebody(route))
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
                postObject(head, route, body)
            }

            is S3Router.Route.CopyObject -> {
                copyObject(head, route, verification.accessKeyId)
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

            is S3Router.Route.ObjectLockSubresource -> {
                objectLockSubresource(head, route, body)
            }

            is S3Router.Route.ObjectAcl -> {
                objectAcl(head, route, body)
            }

            is S3Router.Route.ObjectTagging -> {
                objectTagging(head, route, body)
            }

            is S3Router.Route.Preflight -> {
                preflight(head, route)
            }

            // Answered by `handle` and not by `screen`, even though the answer is known in
            // advance: a probe has to travel the same path a request does, or it checks less than a
            // `tcpSocket` would. The body is one word so the answer reads for a human who came by
            // hand.
            is S3Router.Route.Health -> {
                HttpResponse(
                    200,
                    "OK",
                    headers = listOf("content-type" to "text/plain"),
                    body = "ok\n".toByteArray(),
                )
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
                createUpload(head, route, verification.accessKeyId)
            }

            is S3Router.Route.UploadPart -> {
                uploadPart(head, route, verification, body)
            }

            is S3Router.Route.UploadPartCopy -> {
                uploadPartCopy(head, route, callerOf(head).accessKeyId)
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
        accessKeyId: String?,
    ): HttpResponse {
        if (!store.hasBucket(route.sourceBucket)) {
            return error(head, S3Error.NO_SUCH_BUCKET, bucket = route.sourceBucket)
        }
        val source =
            // The named version of the source, or its current one (M-163). Reading the current
            // one for a request that named another would answer a question nobody asked.
            route.sourceVersionId
                ?.let { store.get(route.sourceBucket, route.sourceKey, it) }
                ?: store.get(route.sourceBucket, route.sourceKey)
                ?: return error(head, S3Error.NO_SUCH_KEY, key = route.sourceKey, bucket = route.sourceBucket)

        // Reading the source is a permission of its own, and nothing upstream asked for it: the
        // screen sees a write to the destination, because the source travels in a header.
        copySourceRefusal(head, route, route.sourceBucket, route.sourceKey, route.sourceVersionId, accessKeyId)
            ?.let { return it }

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
        // The two date conditions, which were read by nothing at all until mint asked (M-304). A
        // client uses `if-unmodified-since` to avoid overwriting something newer than what it saw;
        // a server that ignores it does the overwrite and answers `200`, which is the accepted-and-
        // not-enforced failure this repository refuses everywhere else.
        //
        // To the second, like every other timestamp comparison here: `Last-Modified` is published
        // in `rfc822`, which has no sub-second field, so a client can only ever have seen a whole
        // second and comparing finer would make the condition impossible to satisfy from outside.
        head.header("x-amz-copy-source-if-unmodified-since")?.let { condition ->
            val at = httpDateSeconds(condition) ?: return@let
            if (source.lastModified.epochSecond > at) {
                return error(head, S3Error.PRECONDITION_FAILED, key = route.sourceKey, bucket = route.sourceBucket)
            }
        }
        head.header("x-amz-copy-source-if-modified-since")?.let { condition ->
            val at = httpDateSeconds(condition) ?: return@let
            if (source.lastModified.epochSecond <= at) {
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
        // The copy belongs to whoever made it, not to whoever owned the source: a copy is a new
        // object, and S3 gives it the caller as its owner.
        val stored =
            store.copy(source, route.bucket, route.key, metadata, ownerFor(route.bucket, accessKeyId), statedAcl(head))
        return xml(
            S3Documents.copyObjectResult(stored.eTag, timestamp(stored.lastModified)),
        )
    }

    // --- multipart upload (M7) ----------------------------------------------------------------

    private fun createUpload(
        head: HttpRequestParser.Head,
        route: S3Router.Route.CreateMultipartUpload,
        accessKeyId: String?,
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
        // `x-amz-object-lock-*` on the request that **starts** the upload, held until the
        // completion has something to put them on. Dropped until M-175, which made a locked
        // multipart upload finish as an object anybody could delete — and the client was told the
        // upload succeeded, which it had.
        val lock = lockOnUpload(head)
        // The customer key, stated here and binding on every part (M-189). Refused now rather than
        // at the first part: an upload started with a key that is not a key is a client that thinks
        // it is encrypting, and it should find out on the request that said so.
        val startingKey =
            try {
                SseC.of { name -> head.header(name) }
            } catch (refused: SseC.Refused) {
                return error(head, refused.error, detail = refused.detail, key = route.key, bucket = route.bucket)
            }
        val upload =
            store.createUpload(
                route.bucket,
                route.key,
                ObjectHeaders.read(head.headers),
                algorithm,
                checksumType,
                retention = lock?.retention,
                legalHold = lock?.legalHold == true,
                // The key is not kept — only what identifies it. Every part brings the key again,
                // which is what S3 asks of them, and this is what those parts are checked against.
                encryption = startingKey?.let { ObjectStore.Encryption(it.algorithm, it.keyMd5, ByteArray(0)) },
                // Kept with the upload rather than applied at the completion: the object appears
                // minutes later, and an `x-amz-acl` that lived only in this request would be a
                // permission accepted and then quietly dropped.
                owner = ownerFor(route.bucket, accessKeyId),
                acl = statedAcl(head),
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
        verification: Caller,
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

        // The key travels on every part and is checked in `screen`, before a byte of the part is
        // read — see the note there. Here it is only unpacked again, and it cannot refuse.
        val presented = runCatching { SseC.of { name -> head.header(name) } }.getOrNull()
        val wanted = store.upload(route.uploadId)?.encryption
        // An IV of this part's own. The alternative — one IV for the object, with each part
        // encrypted at its future offset — cannot work: the offset is not known until every other
        // part has arrived, and parts arrive in any order.
        val partIv = if (wanted != null) SseC.newIv() else null
        val partEncryption = partIv?.let { ObjectStore.Encryption(wanted!!.algorithm, wanted.keyMd5, it) }

        // A part goes through the same four framings as an object, because it is the same upload
        // path: `aws s3 cp` of a large file sends every part aws-chunked with a signature apiece.
        var staged: ObjectStore.Staged? = null
        return try {
            staged =
                if (streaming) {
                    stageStreaming(head, verification, body, checksums, presented, partEncryption)
                } else {
                    stageWhole(body, checksums, signedHash, presented, partEncryption)
                }
            if (signedHash != null) {
                val computed = signedHash.digest().joinToString("") { "%02x".format(it) }
                if (computed != verification.payloadHash) {
                    return error(head, S3Error.CONTENT_SHA256_MISMATCH, detail = "computed $computed")
                }
            }
            checksums.verify()?.let { return error(head, it.error, detail = it.detail) }

            val part = store.commitPart(route.uploadId, route.partNumber, staged, checksums.stored(), partIv)
            staged = null
            // `UploadPartOutput.ChecksumSHA256` and its siblings are headers, and an SDK reads
            // them straight back into the part list it will send at completion.
            HttpResponse(
                200,
                "OK",
                headers =
                    checksumHeaders(part.checksum) + ("ETag" to part.eTag) +
                        (
                            wanted?.let {
                                listOf(SseC.ALGORITHM_HEADER to it.algorithm, SseC.KEY_MD5_HEADER to it.keyMd5)
                            } ?: emptyList()
                        ),
            )
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
        accessKeyId: String?,
    ): HttpResponse {
        if (!store.hasBucket(route.sourceBucket)) {
            return error(head, S3Error.NO_SUCH_BUCKET, bucket = route.sourceBucket)
        }
        val source =
            // The named version of the source, or its current one (M-163). Reading the current
            // one for a request that named another would answer a question nobody asked.
            route.sourceVersionId
                ?.let { store.get(route.sourceBucket, route.sourceKey, it) }
                ?: store.get(route.sourceBucket, route.sourceKey)
                ?: return error(head, S3Error.NO_SUCH_KEY, key = route.sourceKey, bucket = route.sourceBucket)

        // Same permission as a whole-object copy, and for the same reason: a part is still a read
        // of somebody's object. Split across two routes, this is the half that a test written for
        // the other half does not cover.
        copySourceRefusal(head, route, route.sourceBucket, route.sourceKey, route.sourceVersionId, accessKeyId)
            ?.let { return it }

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
            completed(route, stored.eTag, stored.metadata.checksum, stored.versionId)
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
        } catch (e: ObjectStore.BucketGone) {
            error(head, S3Error.NO_SUCH_BUCKET, detail = e.message, key = route.key, bucket = route.bucket)
        }
    }

    private fun completed(
        route: S3Router.Route.CompleteMultipartUpload,
        eTag: String,
        checksum: Metadata.Checksum?,
        versionId: String? = null,
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
        ).let { response ->
            if (versionId == null || versionId == ObjectStore.NULL_VERSION) {
                response
            } else {
                response.copy(headers = response.headers + ("x-amz-version-id" to versionId))
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
                        S3Documents.UploadEntry(it.key, it.id, timestamp(it.startedAt), owner = it.owner)
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
    private fun failedWith(
        head: HttpRequestParser.Head,
        cause: Throwable,
    ): HttpResponse =
        // A URI that cannot be read is the client's mistake, not this server's, and it is answered
        // here rather than at each call site because the codec is reached from nine of them: the
        // path, the object key, the query, the copy source, and the components a signature is
        // rebuilt from. Catching it beside one of those would leave the other eight at `500`.
        if (cause is UriCodec.Malformed) {
            error(head, S3Error.INVALID_URI, detail = cause.message)
        } else {
            error(head, S3Error.INTERNAL_ERROR, detail = "${cause::class.simpleName}: ${cause.message}")
        }

    /**
     * `GET /` — every bucket, in name order, a page at a time.
     *
     * The page is opt-in: without `max-buckets` the whole list goes out, which is what every
     * client that predates the parameter expects. With it, the token is the name of the last
     * bucket sent — buckets are ordered by name and names are unique, so a position in that order
     * **is** a name and needs nothing opaque behind it.
     */
    private fun listBuckets(
        head: HttpRequestParser.Head,
        accessKeyId: String,
    ): HttpResponse {
        val params = rawQueryParams(head.query)
        val prefix = params["prefix"]?.let { String(it) }
        val after = params["continuation-token"]?.let { String(it) }
        val maxBuckets = params["max-buckets"]?.let { String(it) }?.toIntOrNull()

        val matching =
            store
                .bucketList()
                // Filtered, not refused, and the order matters: a listing that shows a bucket and
                // then denies it has already told the caller the name exists. Invisibility is the
                // stronger half of a scope, so it is the half applied first (M-127).
                .filter { verifier.credentials.scopeFor(accessKeyId).sees(it.name) }
                // Your buckets, not everybody's (M27). `ListBuckets` has no bucket in its request
                // to be refused about, so ownership can only show up here as a filter — and it has
                // to: the canonical caller is somebody's clean-up loop, which lists buckets and
                // then empties what it finds. Handing it another key's buckets makes every one of
                // those loops fail on a refusal it cannot do anything about. A bucket with no
                // recorded owner stays visible to everyone, like the rest of the upgrade rule.
                .filter { store.bucketOwner(it.name).let { owner -> owner == null || owner == accessKeyId } }
                .filter { prefix == null || it.name.startsWith(prefix) }
                .filter { after == null || it.name > after }
        val page = if (maxBuckets != null) matching.take(maxBuckets) else matching

        return xml(
            S3Documents.listAllMyBucketsResult(
                buckets = page.map { S3Documents.BucketEntry(it.name, timestamp(it.createdAt)) },
                // The caller: `ListBuckets` answers the buckets of whoever asked, so the owner of
                // that answer is that key. `OWNER` is what a store with no owners has to say, and
                // it stays as the fallback for exactly those.
                ownerId = accessKeyId,
                ownerDisplayName = accessKeyId,
                nextContinuationToken = page.lastOrNull()?.name?.takeIf { matching.size > page.size },
                prefix = prefix,
            ),
        )
    }

    private fun createBucket(
        head: HttpRequestParser.Head,
        bucket: String,
        accessKeyId: String,
    ): HttpResponse {
        val stated = AccessControl.Canned.of(head.header("x-amz-acl"))?.wireName
        if (!store.createBucket(bucket, owner = accessKeyId, acl = stated)) {
            // The name is one namespace for every key, so a second creator is refused rather than
            // handed a share of somebody's bucket. Recreating your own stays the success it has
            // always been — `mb` is idempotent in every client — **until** an ACL is in it on
            // either side: "make it exist like this" is a different request from "make sure it
            // exists", and a bucket that already holds objects is not re-shared on the way past.
            val owner = store.bucketOwner(bucket)
            if (owner != null && (owner != accessKeyId || stated != null || store.bucketAcl(bucket) != null)) {
                return error(head, S3Error.BUCKET_ALREADY_EXISTS, bucket = bucket)
            }
            return HttpResponse(200, "OK", headers = listOf("Location" to "/$bucket"))
        }
        // `x-amz-bucket-object-lock-enabled` is the only way object lock is ever switched on: S3
        // has no operation that adds it later, so a bucket either was created for it or never can
        // be. It brings versioning with it — a retention on something that can be overwritten in
        // place protects nothing.
        if (head.header("x-amz-bucket-object-lock-enabled")?.equals("true", ignoreCase = true) == true) {
            store.setObjectLock(bucket, ObjectStore.ObjectLock())
        }
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

    /**
     * `GET /<bucket>?versions` — every version of every key, tombstones included (M-107).
     *
     * Not the ordinary listing with a different document around it, which is what it used to be:
     * that one has a row per key and skips tombstones, and reusing it made `?versions` answer that
     * a bucket has one version of everything and no deletions. The answer was well-formed, which
     * is the problem with it — a client cannot tell a lie of that shape from the truth.
     *
     * It also does not go through [listing]: that helper carries a continuation token, and this
     * operation resumes on two markers instead, because a page can end in the middle of a key.
     */
    private fun listVersions(
        head: HttpRequestParser.Head,
        bucket: String,
    ): HttpResponse {
        val params = rawQueryParams(head.query)
        val request =
            try {
                ListingRequest.of(params)
            } catch (e: ListingRequest.Malformed) {
                return error(head, e.error, detail = e.message, bucket = bucket)
            }
        val versionIdMarker = params["version-id-marker"]?.takeIf { it.isNotEmpty() }?.let { String(it) }
        val page =
            store.versionPage(
                bucket = bucket,
                prefix = request.prefix,
                delimiter = request.delimiter,
                keyMarker = request.keyMarker,
                versionIdMarker = versionIdMarker,
                maxKeys = request.requestedMaxKeys,
            )
        return xml(
            S3Documents.listVersionsResult(
                bucket = bucket,
                prefix = request.prefix,
                delimiter = request.delimiter,
                keyMarker = request.keyMarker,
                nextKeyMarker = page.nextKeyMarker,
                versionIdMarker = versionIdMarker,
                nextVersionIdMarker = page.nextVersionIdMarker,
                maxKeys = request.requestedMaxKeys,
                isTruncated = page.isTruncated,
                versions =
                    page.versions.map {
                        S3Documents.VersionEntry(
                            key = it.key,
                            versionId = it.stored.versionId,
                            isLatest = it.isLatest,
                            lastModified = timestamp(it.stored.lastModified),
                            eTag = it.stored.eTag,
                            size = it.stored.size,
                            deleteMarker = it.stored.deleteMarker,
                        )
                    },
                commonPrefixes = page.commonPrefixes,
                encoding = request.encoding(),
            ),
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
            S3Documents.ObjectEntry(
                key,
                timestamp(stored.lastModified),
                stored.eTag,
                stored.size,
                storageClass = stored.storageClass,
                owner = stored.owner,
            )
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
        route: S3Router.Route.PostObject,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val bucket = route.bucket
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
        val accessKeyId: String?
        val keyText: String
        try {
            form = PostForm.parse(raw, boundary)
            val policyField = form["policy"]
            accessKeyId =
                if (policyField == null) {
                    // A form with no policy names nobody, and that is layer two arriving at the one
                    // entrance `screen` cannot judge (M-225). Until M28 this was `403` outright,
                    // which was true then and became a second lock afterwards: the door had opened
                    // everywhere else and this one branch still held it shut.
                    //
                    // **What replaces the refusal is not the absence of a check.** The two gates
                    // below are the ones every unnamed request goes through — the deployment's
                    // switch, then the bucket's ACL — and they are asked a few lines down, in the
                    // same call the signed path makes. Nothing here decides anything.
                    //
                    // A signature without a policy is a different answer, and it is `400`: a
                    // signature is taken over the policy, so one with no policy under it is a form
                    // missing a part it declared. The suite pins the mirror image of that at `400`
                    // (`test_post_object_missing_signature:2455`), and reading it as "anonymous"
                    // instead would let a form shed its own conditions and be judged by the bucket
                    // alone — a way around exactly what the signed path enforces.
                    if (form["signature"] != null || form["x-amz-signature"] != null) {
                        throw PostForm.Malformed(
                            S3Error.MALFORMED_POST_REQUEST,
                            "the form has a signature but no policy for it to be over",
                        )
                    }
                    null
                } else {
                    PostSignature.verify(form.fields, policyField, verifier.credentials, verifier.region)
                }
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
            if (policyField != null) {
                val checked =
                    form.fields + mapOf("bucket" to bucket) +
                        (if (form["key"] != null) mapOf("key" to keyText) else emptyMap())
                PostPolicy.check(
                    PostPolicy.decode(policyField),
                    checked,
                    form.fileLength.toLong(),
                    java.time.Instant.now(),
                )
            }
        } catch (e: PostForm.Malformed) {
            return error(head, e.error, detail = e.message, bucket = bucket)
        } catch (e: PostSignature.Refused) {
            return error(head, e.error, detail = e.message, bucket = bucket)
        } catch (e: PostPolicy.Refused) {
            return error(head, e.error, detail = e.message, bucket = bucket)
        }

        // The form is the one operation whose authorisation is decided **after** its body arrives,
        // so the access model is asked here rather than in `screen` — where every other route's is.
        // Without these two lines a signed form would write into anybody's bucket, and an unsigned
        // one into every bucket.
        //
        // The switch first, exactly as in `screen`: a deployment with no public buckets should
        // never run the computation behind it. Then `aclRefusal`, which is the **same** call the
        // signed path makes — a second gate written for this entrance is how the two drift, and a
        // permission model that drifts leaks in the direction nobody is testing. It brings the
        // bucket policy with it (M29), which the hand-rolled check that stood here did not: a form
        // is a `s3:PutObject` like any other.
        if (accessKeyId == null && !anonymous) {
            return error(head, S3Error.ACCESS_DENIED, detail = "no credentials on the request", bucket = bucket)
        }
        aclRefusal(head, route, accessKeyId)?.let { return it }

        // The form's `acl` field is a canned name like the header's, and since M27 it is stored and
        // enforced rather than refused. `public-read` still promises **less** here than it does on
        // AWS — an unsigned reader is refused until M28 opens that door — and the honest place for
        // that difference is the contract, not a refusal: a form that names a canned ACL now gets
        // the sharing among keys that it asked for.
        val acl = form["acl"]
        val canned = AccessControl.Canned.of(acl)
        if (acl != null && canned == null) {
            return error(
                head,
                S3Error.INVALID_ARGUMENT,
                detail = "acl '$acl' is not one this server enforces",
                bucket = bucket,
            )
        }
        // `BlockPublicAcls` asked here as well as in the screen, because this is the one operation
        // whose ACL is not in the head (§4.4): a form naming `public-read` would otherwise walk
        // straight past the refusal every other route gets. The same shape as the bucket's ACL
        // being checked here rather than upstream — a form is authorised after its body arrives.
        if (canned != null && canned.public && publicAccessBlockOf(bucket)?.blockPublicAcls == true) {
            return error(
                head,
                S3Error.ACCESS_DENIED,
                detail = "BlockPublicAcls is on for this bucket, and '${canned.wireName}' is a public acl",
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
        //
        // Tags are the exception, and they are the one place where the same thing has two shapes
        // on the same request. `x-amz-tagging` is `a=1&b=2`; the form field is called `tagging` and
        // holds a whole `<Tagging>` **document** (`test_post_object_tags_anonymous_request:12203`).
        // Nothing read it until M-225, and the authenticated twin of that case
        // (`test_post_object_tags_authenticated_request:12234`) never noticed, because it checks
        // the status and the body and never asks for the tags back.
        val metadata =
            try {
                val fromFields = ObjectHeaders.read(form.fields.map { it.key to it.value })
                val document = form["tagging"]?.takeIf { it.isNotEmpty() }
                val tagged =
                    if (document == null) {
                        fromFields
                    } else {
                        fromFields.copy(tags = S3Requests.parseTagging(document.toByteArray(Charsets.UTF_8)))
                    }
                ObjectHeaders.check(tagged)?.let {
                    return error(head, it.error, detail = it.detail, key = key, bucket = bucket)
                }
                tagged
            } catch (e: ObjectHeaders.Malformed) {
                return error(head, S3Error.INVALID_TAG, detail = e.message, key = key, bucket = bucket)
            } catch (e: XmlReader.MalformedXmlException) {
                return error(head, S3Error.MALFORMED_XML, detail = "tagging: ${e.message}", key = key, bucket = bucket)
            }

        // And the checksum by the same code as well, for the same reason: `x-amz-checksum-sha256`
        // is a field of a form and a header of a `PUT`, and it means one thing in both. Excluding
        // it from the policy's coverage check was only half of M-158 — a form that states a
        // checksum nobody verifies promises bytes it did not deliver.
        val checksums = PayloadChecksums.of { name -> form[name] }
        checksums.rejection?.let {
            return error(head, it.error, detail = it.detail, key = key, bucket = bucket)
        }
        checksums.update(raw, form.fileOffset, form.fileLength)
        checksums.verify()?.let {
            return error(head, it.error, detail = it.detail, key = key, bucket = bucket)
        }

        // The customer key by the same code again, and for the same reason as the metadata and the
        // checksum above: the field names of a form are the header names of a `PUT`, and this one
        // means the same thing in both (M-190б). The checksum was taken over the plaintext a few
        // lines up, which is where it belongs — it describes the object, not what the disk holds.
        val sse =
            try {
                SseC.of { name -> form[name] }
            } catch (refused: SseC.Refused) {
                return error(head, refused.error, detail = refused.detail, key = key, bucket = bucket)
            }
        val encryption = sse?.let { ObjectStore.Encryption(it.algorithm, it.keyMd5, SseC.newIv()) }

        var staged: ObjectStore.Staged? = null
        return try {
            staged =
                if (sse == null || encryption == null) {
                    store.stage { out -> out.write(raw, form.fileOffset, form.fileLength) }
                } else {
                    val cipher = sse.cipherAt(encryption.iv, 0)
                    val encrypted = ByteArray(form.fileLength)
                    cipher.update(raw, form.fileOffset, form.fileLength, encrypted, 0)
                    // The third path that encrypts, and it needs the same ETag as the other two
                    // (M-190а): taken over the plaintext with the client's key, so the same bytes
                    // sent again answer the same way whatever the IV was.
                    val mac = sse.eTagMac()
                    mac.update(raw, form.fileOffset, form.fileLength)
                    store
                        .stage { out -> out.write(encrypted, 0, encrypted.size) }
                        .copy(eTag = hexETag(mac.doFinal()))
                }
            val stored =
                store.commit(
                    bucket,
                    key,
                    metadata.copy(checksum = checksums.stored()),
                    staged,
                    ObjectStore.Precondition(),
                    encryption = encryption,
                    // The same answer an unsigned `PUT` gets (see `ownerFor`): a form that named
                    // nobody has no identity to own anything, and an object with no owner is open
                    // to every key — which would make a `public-read-write` bucket a place where
                    // anybody can create something nobody can close.
                    owner = ownerFor(bucket, accessKeyId),
                    acl = acl,
                )
            staged = null
            formSuccess(form, bucket, key, stored.eTag)
        } catch (e: ObjectStore.CeilingExceeded) {
            error(head, S3Error.INSUFFICIENT_STORAGE, detail = e.message, key = key, bucket = bucket)
        } catch (e: ObjectStore.BucketGone) {
            error(head, S3Error.NO_SUCH_BUCKET, detail = e.message, key = key, bucket = bucket)
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
        // used would hand a page the identity of the service behind it. Since M-225 there may be
        // no key at all — a form with no policy names nobody — which is the second reason it is
        // not a parameter here.
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
        verification: Caller,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        // Refused before a byte of body is read, like every other refusal decided from the head
        // (research, §1.2.2): a class this server cannot honour is the client's mistake, and it
        // costs them nothing to be told at once.
        val storageClass =
            storageClassOf(head)
                ?: return error(
                    head,
                    S3Error.INVALID_STORAGE_CLASS,
                    detail = head.header("x-amz-storage-class"),
                    key = route.key,
                    bucket = route.bucket,
                )

        val payloadHash = verification.payloadHash
        val streaming = payloadHash in SignatureVerifier.ALL_STREAMING
        val checksums = PayloadChecksums.of { head.header(it) }
        val fromHead = ObjectHeaders.read(head.headers)
        // Assigned after staging when the checksum came in the trailer, which is after the object
        // (M-219). Everything else about the metadata is decided by the head and decided once.
        var metadata = fromHead.copy(checksum = checksums.stored())
        // Refused here, before the body is read, like everything else decided from the head: a key
        // whose MD5 does not describe it is a mistake the client made now, and storing the object
        // to discover it at the first read turns a typo into an object nobody can open.
        val sse =
            try {
                SseC.of { name -> head.header(name) }
            } catch (refused: SseC.Refused) {
                return error(head, refused.error, detail = refused.detail, key = route.key, bucket = route.bucket)
            }
        val encryption = sse?.let { ObjectStore.Encryption(it.algorithm, it.keyMd5, SseC.newIv()) }
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
                    stageStreaming(head, verification, body, checksums, sse, encryption)
                } else {
                    stageWhole(body, checksums, signedHash, sse, encryption)
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
            metadata = metadata.copy(checksum = checksums.stored())

            var stored =
                store.commit(
                    route.bucket,
                    route.key,
                    metadata,
                    staged,
                    writePrecondition(head),
                    encryption = encryption,
                    owner = ownerFor(route.bucket, verification.accessKeyId),
                    acl = statedAcl(head),
                    storageClass = storageClass,
                )
            staged = null
            // `x-amz-object-lock-*` on the upload itself: the object arrives already protected,
            // which is the only way to close the window between a write and the retention that was
            // meant to cover it.
            lockOnUpload(head)?.let { stated ->
                stated.retention?.let { store.setRetention(route.bucket, route.key, stored.versionId, it) }
                stated.legalHold?.let { store.setLegalHold(route.bucket, route.key, stored.versionId, it) }
                stored = store.get(route.bucket, route.key, stored.versionId) ?: stored
            }
            HttpResponse(
                200,
                "OK",
                headers =
                    checksumHeaders(metadata.checksum) + ("ETag" to stored.eTag) + versionHeader(stored) +
                        expirationHeader(route.bucket, route.key, stored) + sseHeaders(stored),
            )
        } catch (e: AwsChunkedDecoder.MalformedBody) {
            error(head, e.error, detail = e.message)
        } catch (e: ObjectStore.CeilingExceeded) {
            error(head, S3Error.INSUFFICIENT_STORAGE, detail = e.message, key = route.key, bucket = route.bucket)
        } catch (e: ObjectStore.BucketGone) {
            // The bucket went while the body was arriving. `NoSuchBucket` is what the client would
            // have been told had it asked a second earlier, and it is what the suite expects
            // (`test_atomic_write_bucket_gone`).
            error(head, S3Error.NO_SUCH_BUCKET, detail = e.message, key = route.key, bucket = route.bucket)
        } catch (e: ObjectStore.Locked) {
            error(head, S3Error.ACCESS_DENIED, detail = e.message, key = route.key, bucket = route.bucket)
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
     * The storage class a write asked for, checked against what this server can honour (M-301).
     *
     * Absent means `STANDARD`, which is what the object is. The classes accepted are the ones that
     * differ from `STANDARD` in durability or price rather than in **access**: every one of them
     * is readable the moment it is written, which is the only promise this store can keep. The two
     * that are refused, `GLACIER` and `DEEP_ARCHIVE`, require a restore before a read, and this
     * server has no restore — accepting them would be a promise made in a header, which is the one
     * thing this repository refuses to do: accept what it does not enforce.
     *
     * Found by `mint`: the header was read by nothing at all, so every class was silently
     * `STANDARD` and every listing said so.
     */
    private fun storageClassOf(head: HttpRequestParser.Head): String? {
        val asked = head.header("x-amz-storage-class") ?: return ObjectStore.STANDARD_STORAGE_CLASS
        return asked.takeIf { it in STORABLE_CLASSES }
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
        verification: Caller,
        body: HttpHandler.RequestBody,
        checksums: PayloadChecksums,
        sse: SseC? = null,
        encryption: ObjectStore.Encryption? = null,
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

        // The same cipher as the whole-body path, and it has to be here rather than only there:
        // `aws-chunked` is what `aws s3 cp` sends by default (§1.1), so an SSE-C upload from the
        // most ordinary client in the world comes through this function. Encrypting in one of the
        // two would store the plaintext while the index says the object is encrypted — the shape
        // of failure this repository refuses everywhere else: accepted and not carried out.
        val cipher = if (sse != null && encryption != null) sse.cipherAt(encryption.iv, 0) else null
        val eTagMac = if (cipher != null) sse!!.eTagMac() else null
        val staged =
            store.stage { out ->
                val sink =
                    AwsChunkedDecoder(
                        decodedLength = declared,
                        signing = verification.chunkSigning,
                        expectedTrailers = announced,
                    ) { bytes, offset, length ->
                        checksums.update(bytes, offset, length)
                        eTagMac?.update(bytes, offset, length)
                        if (cipher == null) {
                            out.write(bytes, offset, length)
                        } else {
                            val encrypted = ByteArray(length)
                            cipher.update(bytes, offset, length, encrypted, 0)
                            out.write(encrypted, 0, length)
                        }
                    }
                body.forEach { bytes, offset, length -> sink.feed(bytes, offset, length) }
                sink.finish()
                // After the last byte, and only here: this is where the client's own checksum arrives
                // when it travels in the trailer (M-219). The decoder has already verified it; what it
                // does not do is tell anybody what it was.
                checksums.statedInTrailer(sink.trailers)
            }
        return eTagMac?.let { staged.copy(eTag = hexETag(it.doFinal())) } ?: staged
    }

    private suspend fun stageWhole(
        body: HttpHandler.RequestBody,
        checksums: PayloadChecksums,
        signedHash: MessageDigest?,
        sse: SseC? = null,
        encryption: ObjectStore.Encryption? = null,
    ): ObjectStore.Staged {
        // The checksums and the signed hash see the **plaintext**, and that is not a shortcut: a
        // checksum describes the object, and the signature describes what the client put on the
        // wire. Taken after the cipher, both would be true statements about the wrong bytes.
        val cipher = if (sse != null && encryption != null) sse.cipherAt(encryption.iv, 0) else null
        // And the ETag of an encrypted object, for a third reason again: the store computes it from
        // what it stores, which is the ciphertext, and a fresh IV per upload makes that a different
        // answer every time for the same bytes (M-190а).
        val eTagMac = if (cipher != null) sse!!.eTagMac() else null
        val staged =
            store.stage { out ->
                body.forEach { bytes, offset, length ->
                    checksums.update(bytes, offset, length)
                    signedHash?.update(bytes, offset, length)
                    eTagMac?.update(bytes, offset, length)
                    if (cipher == null) {
                        out.write(bytes, offset, length)
                    } else {
                        val encrypted = ByteArray(length)
                        cipher.update(bytes, offset, length, encrypted, 0)
                        out.write(encrypted, 0, length)
                    }
                }
            }
        return eTagMac?.let { staged.copy(eTag = hexETag(it.doFinal())) } ?: staged
    }

    /** The quote an `ETag` wears on the wire, named so [hexETag] stays readable. */
    private val quote = "\""

    /**
     * Decoded policies, one per bucket, valid while the stored bytes are the same instance.
     *
     * See [policyDecision]: the alternative is a JSON parse on every request to a bucket that has
     * a policy, beside an access decision measured in nanoseconds.
     */
    private val decodedPolicies = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, BucketPolicy.Policy>>()

    /** The same arrangement for the four public-access switches; see [publicAccessBlockOf]. */
    private val decodedBlocks =
        java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, PublicAccessBlock.Configuration>>()

    /** An `ETag` as it travels: thirty-two hex characters in quotes. */
    private fun hexETag(bytes: ByteArray) =
        bytes.joinToString(separator = "", prefix = quote, postfix = quote) { "%02x".format(it) }

    /** The same header the read path adds, for the answers that are about one version (M-305). */
    private fun HttpResponse.withVersion(stored: ObjectStore.Stored): HttpResponse =
        copy(headers = headers + versionHeader(stored))

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
        // The key the client brought, checked against what this version was written with, before a
        // single byte is read. Three outcomes and they are three different answers (M-187).
        val presented =
            try {
                SseC.of { name -> head.header(name) }
            } catch (refused: SseC.Refused) {
                return error(head, refused.error, detail = refused.detail, key = key, bucket = bucket)
            }
        // A part number this object does not have is answered **before** the key is demanded, and
        // that order is S3's rather than ours: `test_multipart_sse_c_get_part` asks for part five of
        // a four-part encrypted object **without** the key and expects `InvalidPart`. Demanding the
        // key first is not wrong so much as unhelpful — it tells the client to fix the one thing
        // that was not the mistake.
        if (partNumber != null && stored.parts.isNotEmpty() && sliceOfPart(stored, partNumber) == null) {
            return error(head, S3Error.INVALID_PART, key = key, bucket = bucket)
        }
        sseRefusal(head, bucket, key, stored, presented)?.let { return it }
        val headers =
            overridden(
                head,
                buildList {
                    add("ETag" to stored.eTag)
                    add("Last-Modified" to httpDate(stored.lastModified))
                    addAll(sseHeaders(stored))
                    addAll(versionHeader(stored))
                    // Absent for a STANDARD object rather than present and saying so, which is
                    // what S3 does: a client that reads this header to decide whether an object
                    // needs restoring would otherwise see one on everything (M-301).
                    if (stored.storageClass != ObjectStore.STANDARD_STORAGE_CLASS) {
                        add("x-amz-storage-class" to stored.storageClass)
                    }
                    addAll(expirationHeader(bucket, key, stored))
                    stored.retention?.let {
                        add("x-amz-object-lock-mode" to it.mode)
                        add(
                            "x-amz-object-lock-retain-until-date" to
                                java.time.Instant
                                    .ofEpochMilli(it.untilMillis)
                                    .toString(),
                        )
                    }
                    // Always, not only when it is on: in a bucket with object lock every object
                    // has a legal-hold status, and `OFF` is one of its two values. Emitting it
                    // only for `ON` turns "the hold was lost" into a missing key, and a client
                    // reading `response['ObjectLockLegalHoldStatus']` raises `KeyError` — which
                    // says nothing about what went wrong and, in the one case that matters, kills
                    // the test before it can clean up after itself.
                    if (store.objectLock(bucket) != null) {
                        add("x-amz-object-lock-legal-hold" to if (stored.legalHold) "ON" else "OFF")
                    }
                    add("Accept-Ranges" to "bytes")
                    addAll(ObjectHeaders.write(stored.metadata))
                    // How many tags rather than which: `?tagging` hands out the list, and here the
                    // client only needs to know whether it is worth asking. S3 sends the header
                    // only when there are tags.
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
                file =
                    if (withBody) {
                        HttpResponse.FileSlice(
                            path,
                            slice.first,
                            slice.second,
                            decrypting(stored, presented, slice.first),
                        )
                    } else {
                        null
                    },
                contentLength = slice.second,
            )
        }

        // Handing the file to the terminator in front, when the deployment says to. Only a `GET`
        // of the whole object: a `HEAD` has no body to hand over, and `partNumber` names a slice
        // that no header can express — `X-Accel-Redirect` says which file, never which part of it.
        // A part request cannot reach here at all; the branch above answers it and returns.
        // A `Range` **is** handed over, because nginx applies the client's own `Range` to the
        // internal file and answers the `206` itself.
        if (accelRedirect != null && withBody) {
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
                    file = HttpResponse.FileSlice(path, 0, stored.size, decrypting(stored, presented, 0)),
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
                    file =
                        HttpResponse.FileSlice(
                            path,
                            range.start,
                            range.length,
                            decrypting(stored, presented, range.start),
                        ),
                    contentLength = range.length,
                )
            }
        }.let { if (withBody) it else it.copy(file = null) }
    }

    /**
     * Whether the key the client brought fits the object it is asking for, and what to say if not.
     *
     * **All three are `400`, and the third was written as `403` first.** The reasoning for `403`
     * was that a key which does not open an object is a refusal of access — and the suite
     * disagreed: `test_encryption_sse_c_other_key` asserts `400`, with no `fails_on_aws` beside it.
     * The model says the key "must match the one used when storing the data" and says nothing about
     * a status, so the suite is the oracle and the reasoning was only reasoning.
     *
     * Which is right on a second look: authorization here is the **signature**, and this request is
     * signed correctly. The key is a parameter, and a parameter that cannot do its job makes a bad
     * request — the same answer a wrong checksum gets. `403` would send a client off to re-sign a
     * request whose signature was never the problem.
     *
     * What differs between the three is the message, and that is not decoration: "this object was
     * not encrypted with a customer key" and "that is not the key it was written with" send a
     * person to two different places.
     */
    private fun sseRefusal(
        head: HttpRequestParser.Head,
        bucket: String,
        key: ObjectKey,
        stored: ObjectStore.Stored,
        presented: SseC?,
    ): HttpResponse? {
        val encryption = stored.encryption
        if (encryption == null) {
            if (presented == null) return null
            return error(
                head,
                S3Error.INVALID_ARGUMENT,
                detail = "this object was not encrypted with a customer key",
                key = key,
                bucket = bucket,
            )
        }
        if (presented == null) {
            return error(
                head,
                S3Error.INVALID_ARGUMENT,
                detail = "this object was encrypted with a customer key and the request carries none",
                key = key,
                bucket = bucket,
            )
        }
        if (presented.keyMd5 != encryption.keyMd5) {
            return error(
                head,
                S3Error.INVALID_ARGUMENT,
                detail = "that is not the key this object was written with",
                key = key,
                bucket = bucket,
            )
        }
        return null
    }

    /**
     * What a written or read object says about its encryption: two headers of the three.
     *
     * `PutObjectOutput` and `GetObjectOutput` both name the algorithm and the key's MD5 and neither
     * names the key (`s3-service-2.json:6385`). That absence is the whole difference between
     * encrypting with the client's key and encrypting with the server's.
     */
    private fun sseHeaders(stored: ObjectStore.Stored): List<Pair<String, String>> =
        stored.encryption?.let {
            listOf(SseC.ALGORITHM_HEADER to it.algorithm, SseC.KEY_MD5_HEADER to it.keyMd5)
        } ?: emptyList()

    /**
     * The filter that turns the stored bytes back into the object, or null when there is nothing to
     * undo.
     *
     * Two shapes, because an assembled object is not one stream of ciphertext but several laid end
     * to end. A simple `PUT` has one IV and one counter running from the start of the object. A
     * multipart object has an IV per part (M-189), so the filter has to know where it is in the
     * object and start a new cipher at every seam — including when a `Range` drops it into the
     * middle of one.
     */
    private fun decrypting(
        stored: ObjectStore.Stored,
        presented: SseC?,
        offset: Long,
    ): HttpResponse.Filter? {
        val encryption = stored.encryption ?: return null
        val key = presented ?: return null
        if (stored.parts.isEmpty()) {
            val cipher = key.cipherAt(encryption.iv, offset)
            return HttpResponse.Filter { buffer, from, length ->
                // In place: counter mode writes exactly as many bytes as it reads, and the same
                // array goes on to the socket.
                cipher.update(buffer, from, length, buffer, from)
            }
        }
        return partwiseDecrypting(stored, key, offset)
    }

    /**
     * The same, for an object made of parts that were each encrypted on their own.
     *
     * Stateful, and it has to be: a chunk read off the disk can span a seam, and the two halves
     * belong to two different ciphers. The position is absolute — where in the object the next
     * byte sits — because that is the only thing both the part list and a `Range` agree on.
     */
    private fun partwiseDecrypting(
        stored: ObjectStore.Stored,
        key: SseC,
        offset: Long,
    ): HttpResponse.Filter {
        val bounds = ArrayList<Triple<Long, Long, ByteArray>>(stored.parts.size)
        var start = 0L
        for (part in stored.parts) {
            bounds += Triple(start, start + part.size, part.iv ?: ByteArray(16))
            start += part.size
        }
        var position = offset
        var cipher: javax.crypto.Cipher? = null
        var currentEnd = -1L
        return HttpResponse.Filter { buffer, from, length ->
            var done = 0
            while (done < length) {
                if (cipher == null || position >= currentEnd) {
                    val part =
                        bounds.firstOrNull { position >= it.first && position < it.second }
                            ?: bounds.last()
                    cipher = key.cipherAt(part.third, position - part.first)
                    currentEnd = part.second
                }
                val room = minOf((currentEnd - position).toInt(), length - done)
                cipher.update(buffer, from + done, room, buffer, from + done)
                done += room
                position += room
            }
        }
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
        // A named version, or the current one — the same rule every other read follows (M-164).
        val named = route.versionId
        val stored =
            (if (named != null) store.get(route.bucket, route.key, named) else store.get(route.bucket, route.key))
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

        // The same key check a `GET` and a `HEAD` make, and for the same reason: this answer says
        // how long the object is and what its parts are, which is a statement about the object
        // rather than about the ciphertext. `GetObjectAttributes` takes the SSE-C headers in the
        // model exactly because of that (M-189).
        val presented =
            try {
                SseC.of { name -> head.header(name) }
            } catch (refused: SseC.Refused) {
                return error(head, refused.error, detail = refused.detail, key = route.key, bucket = route.bucket)
            }
        sseRefusal(head, route.bucket, route.key, stored, presented)?.let { return it }

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

        // `GetObjectAttributesOutput` carries members as **headers** as well as in the document:
        // `LastModified`, which was here, and `VersionId`, which was not. Taking `?versionId` on
        // the request and never saying which version answered is half an operation, and the client
        // finds out by `KeyError` on `response['VersionId']` — the shape of failure that says
        // nothing about its cause and that this repository has now paid for three times.
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
        ).copy(
            headers =
                listOf("Content-Type" to "application/xml", "Last-Modified" to httpDate(stored.lastModified)) +
                    versionHeader(stored),
        )
    }

    /**
     * `?object-lock` — a bucket sub-resource with a precondition none of the others have.
     *
     * Every other configuration can be put on any bucket. This one cannot: object lock is a
     * property of creation (`x-amz-bucket-object-lock-enabled` on `CreateBucket`), and a bucket
     * made without it answers `409 InvalidBucketState` to a `PUT` and
     * `404 ObjectLockConfigurationNotFoundError` to a `GET`. Two different codes for one absence,
     * because the client fixes two different things: one recreates the bucket, the other stops
     * asking.
     */
    private suspend fun bucketObjectLock(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val lock = store.objectLock(route.bucket)
        return when (route.method) {
            "GET" -> {
                if (lock == null) {
                    error(head, S3Error.OBJECT_LOCK_CONFIGURATION_NOT_FOUND, bucket = route.bucket)
                } else {
                    xml(S3Documents.objectLockResult(lock))
                }
            }

            "PUT" -> {
                // Creation is not the only door after all: a bucket that already versions may take
                // object lock later (`test_object_lock_put_obj_lock_enable_after_create:13341`
                // refuses only because its bucket is **not versioned**). What lock protects is a
                // version, so versioning is the real precondition and creation was a proxy for it.
                if (lock == null && store.versioning(route.bucket) != ObjectStore.Versioning.ENABLED) {
                    return error(head, S3Error.INVALID_BUCKET_STATE, bucket = route.bucket)
                }
                val collected = ByteArrayOutputStream()
                body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
                try {
                    store.setObjectLock(route.bucket, S3Requests.parseObjectLock(collected.toByteArray()))
                    HttpResponse(200, "OK")
                } catch (e: XmlReader.MalformedXmlException) {
                    error(head, S3Error.MALFORMED_XML, detail = e.message, bucket = route.bucket)
                } catch (e: S3Requests.InvalidRetentionPeriod) {
                    error(head, S3Error.INVALID_RETENTION_PERIOD, detail = e.message, bucket = route.bucket)
                }
            }

            else -> {
                error(head, S3Error.NOT_IMPLEMENTED, detail = "${route.method} ?object-lock", bucket = route.bucket)
            }
        }
    }

    /**
     * `?retention` and `?legal-hold` on a version.
     *
     * Both refuse on a bucket without object lock, and both answer an object that has no rule with
     * an empty document rather than a refusal — the object is there, and what is absent is a rule
     * about it.
     */
    private suspend fun objectLockSubresource(
        head: HttpRequestParser.Head,
        route: S3Router.Route.ObjectLockSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        // `400 InvalidRequest` here and `409 InvalidBucketState` on the bucket's own sub-resource,
        // and the suite pins both. It is the same absence seen from two places: asking a bucket to
        // configure a lock it cannot have is about the bucket, asking an **object** about a lock
        // that cannot exist is a request that was never valid.
        if (store.objectLock(route.bucket) == null) {
            return error(head, S3Error.INVALID_REQUEST, key = route.key, bucket = route.bucket)
        }
        val named = route.versionId
        val stored =
            (
                if (named !=
                    null
                ) {
                    store.get(route.bucket, route.key, named)
                } else {
                    store.currentVersion(route.bucket, route.key)
                }
            ) ?: return error(head, S3Error.NO_SUCH_KEY, key = route.key, bucket = route.bucket)

        if (route.method == "GET") {
            return if (route.name == "retention") {
                xml(S3Documents.retentionResult(stored.retention))
            } else {
                xml(S3Documents.legalHoldResult(stored.legalHold))
            }
        }

        val collected = ByteArrayOutputStream()
        body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
        return try {
            if (route.name == "retention") {
                // `x-amz-bypass-governance-retention` is the caller saying out loud that it means
                // to step over a `GOVERNANCE` lock. `COMPLIANCE` does not read it.
                store.setRetention(
                    route.bucket,
                    route.key,
                    route.versionId,
                    S3Requests.parseRetention(collected.toByteArray()),
                    bypass = head.header("x-amz-bypass-governance-retention")?.equals("true", true) == true,
                )
            } else {
                store.setLegalHold(
                    route.bucket,
                    route.key,
                    route.versionId,
                    S3Requests.parseLegalHold(collected.toByteArray()),
                )
            }
            HttpResponse(200, "OK")
        } catch (e: XmlReader.MalformedXmlException) {
            error(head, S3Error.MALFORMED_XML, detail = e.message, key = route.key, bucket = route.bucket)
        } catch (e: ObjectStore.Locked) {
            error(head, S3Error.ACCESS_DENIED, detail = e.message, key = route.key, bucket = route.bucket)
        }
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
                    val wanted = S3Requests.parseVersioning(collected.toByteArray())
                    // A locked bucket cannot stop versioning: suspending it would let the next
                    // write replace a version somebody is holding under retention
                    // (`test_object_lock_suspend_versioning:13462`).
                    if (wanted == ObjectStore.Versioning.SUSPENDED && store.objectLock(route.bucket) != null) {
                        return error(head, S3Error.INVALID_BUCKET_STATE, bucket = route.bucket)
                    }
                    store.setVersioning(route.bucket, wanted)
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
     * `?lifecycle` — the rules the server has to **carry out** rather than merely store.
     *
     * Parsed before the write and stored re-rendered, like tags and CORS: what cannot be parsed
     * must not reach the journal and come back to a client as a setting. Re-rendering does not
     * canonicalise the document — a rule leaves in the shape it arrived in (see [S3Documents]).
     *
     * Three refusals and three different codes. `MalformedXML` — the document is not a document;
     * `InvalidArgument` — the document parsed and cannot be carried out (zero days, a repeated
     * `ID`, a transition between storage classes); `404 NoSuchLifecycleConfiguration` — there are
     * simply no rules. `DELETE` answers `204` even when there is nothing to remove:
     * `test_lifecycle_delete:8462` pins that twice, before and after the rules are put in place.
     */
    private suspend fun bucketLifecycle(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse =
        when (route.method) {
            "GET" -> {
                val stored =
                    store.bucketSubresource(route.bucket, Lifecycles.NAME)
                        ?: return error(head, S3Error.NO_SUCH_LIFECYCLE_CONFIGURATION, bucket = route.bucket)
                xml(stored)
            }

            "DELETE" -> {
                store.putBucketSubresource(route.bucket, Lifecycles.NAME, null)
                HttpResponse(204, "No Content")
            }

            else -> {
                val collected = ByteArrayOutputStream()
                body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
                try {
                    val parsed = S3Requests.parseLifecycle(collected.toByteArray())
                    store.putBucketSubresource(route.bucket, Lifecycles.NAME, S3Documents.lifecycleResult(parsed))
                    HttpResponse(200, "OK")
                } catch (e: XmlReader.MalformedXmlException) {
                    error(head, S3Error.MALFORMED_XML, detail = e.message, bucket = route.bucket)
                } catch (e: S3Requests.InvalidArgument) {
                    error(head, S3Error.INVALID_ARGUMENT, detail = e.message, bucket = route.bucket)
                }
            }
        }

    /**
     * `x-amz-expiration: expiry-date="…", rule-id="…"` — when the object is under a rule with a
     * term.
     *
     * And **absent** when it is not: `test_lifecycle_expiration_header_tags_head:9192` puts a rule
     * on one tag, reads the header, changes the rule to a different tag, and demands the header be
     * gone. The half that is easy to leave undone is the second one.
     *
     * The term is computed from the same rules the sweep will later delete on, which is why the
     * unit of a "day" comes here from the configuration rather than being taken as twenty-four
     * hours: a header promising one thing and a sweep doing another is worse than neither.
     */
    private fun expirationHeader(
        bucket: String,
        key: ObjectKey,
        stored: ObjectStore.Stored,
    ): List<Pair<String, String>> {
        if (stored.deleteMarker) return emptyList()
        val lifecycle = lifecycles.of(bucket) ?: return emptyList()
        val (at, rule) =
            lifecycle.expiryOf(key, stored.size, stored.metadata.tags, stored.lastModified, lifecycleDay)
                ?: return emptyList()
        return listOf(
            "x-amz-expiration" to "expiry-date=\"${httpDate(at)}\", rule-id=\"${rule.id}\"",
        )
    }

    /**
     * `?tagging` and `?cors` on a bucket: put, read, remove.
     *
     * The document is parsed **before** the write and stored re-rendered rather than as it came:
     * what cannot be parsed must not reach the journal and come back to a client as a setting, and
     * re-rendering settles the question of what to do with somebody else's formatting.
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
        if (route.name == "object-lock") return bucketObjectLock(head, route, body)
        if (route.name == Lifecycles.NAME) return bucketLifecycle(head, route, body)

        when (route.name) {
            "policy" -> return bucketPolicy(head, route, body)
            "policyStatus" -> return bucketPolicyStatus(route)
            "acl" -> return bucketAcl(head, route, body)
            BucketLogging.NAME -> return bucketLogging(head, route, body)
            PublicAccessBlock.NAME -> return bucketPublicAccessBlock(head, route, body)
        }

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
                            val tags = S3Requests.parseTagging(collected.toByteArray())
                            TagRules.check(tags)?.let {
                                return error(head, S3Error.INVALID_TAG, detail = it.message, bucket = route.bucket)
                            }
                            S3Documents.taggingResult(tags)
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
     * `?logging` on a bucket: where its access log goes, if anywhere (M-202).
     *
     * There is no `DeleteBucketLogging` in S3, and none here: `PutBucketLogging` with an empty
     * `BucketLoggingStatus` is how logging is switched off, which is why `DELETE` is not a method
     * this sub-resource answers.
     *
     * **The configuration is stored; nothing is delivered yet.** That is the milestone's line and
     * it was drawn by the suite's own markers: 33 of the 39 failing cases in this family are
     * `fails_on_aws`, pinning RGW's journal and its roll timing rather than S3's behaviour. The
     * six that are not are exactly this operation and who may call it.
     *
     * Three refusals, and each names a different thing. A target bucket that does not exist is
     * **`NoSuchKey`** — not `NoSuchBucket`, which is what the missing **source** answers, and the
     * asymmetry is the suite's (`test_put_bucket_logging_errors:16526`). A target that is itself
     * logging somewhere is `InvalidArgument`: a chain of logs writes log records about writing log
     * records. And a target whose policy does not let the logging service write there is
     * `AccessDenied`, which is the first thing in this codebase to **use** a `{"Service": …}`
     * principal rather than refuse one.
     */
    private suspend fun bucketLogging(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        if (route.method == "GET") {
            val stored = store.bucketSubresource(route.bucket, BucketLogging.NAME)
            return xml(stored ?: BucketLogging.encode(null))
        }
        if (route.method == "DELETE") {
            return error(
                head,
                S3Error.NOT_IMPLEMENTED,
                detail = "logging is switched off with an empty BucketLoggingStatus, not with DELETE",
                bucket = route.bucket,
            )
        }

        val collected = ByteArrayOutputStream()
        body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
        val enabled =
            try {
                BucketLogging.decode(collected.toByteArray())
            } catch (e: BucketLogging.Refused) {
                return error(head, e.error, detail = e.message, bucket = route.bucket)
            } catch (e: XmlReader.MalformedXmlException) {
                return error(head, S3Error.MALFORMED_XML, detail = e.message, bucket = route.bucket)
            }

        if (enabled == null) {
            store.putBucketSubresource(route.bucket, BucketLogging.NAME, null)
            return HttpResponse(200, "OK")
        }
        if (!store.hasBucket(enabled.targetBucket)) {
            return error(head, S3Error.NO_SUCH_KEY, detail = "no such target bucket", bucket = enabled.targetBucket)
        }
        if (store.bucketSubresource(enabled.targetBucket, BucketLogging.NAME) != null) {
            return error(
                head,
                S3Error.INVALID_ARGUMENT,
                detail = "the target bucket logs somewhere itself, and a chain of logs logs its own writing",
                bucket = enabled.targetBucket,
            )
        }
        loggingDeliveryRefusal(head, route, enabled)?.let { return it }

        store.putBucketSubresource(route.bucket, BucketLogging.NAME, BucketLogging.encode(enabled))
        return HttpResponse(200, "OK")
    }

    /**
     * Whether the target bucket's policy lets this server write the log there (M-202).
     *
     * The question is asked as the **logging service** rather than as the caller, because that is
     * who would be writing: `s3:PutObject` on `<target>/<prefix>`, with `aws:SourceArn` naming the
     * bucket being logged and `aws:SourceAccount` its owner. A target bucket with no policy at all
     * refuses, and that is the point of `test_put_bucket_logging_permissions:16692` — the
     * configuration is not accepted until the place it names has agreed to receive.
     */
    private fun loggingDeliveryRefusal(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        enabled: BucketLogging.Enabled,
    ): HttpResponse? {
        val owner = store.bucketOwner(route.bucket)
        val decision =
            policyDecisionFor(
                head,
                route,
                enabled.targetBucket,
                accessKeyId = BucketPolicy.LOGGING_SERVICE,
                action = "s3:PutObject",
                resource = BucketPolicy.ARN_PREFIX + enabled.targetBucket + "/" + enabled.targetPrefix,
                keys = { name ->
                    when (name) {
                        "aws:SourceArn" -> BucketPolicy.ARN_PREFIX + route.bucket
                        "aws:SourceAccount" -> owner
                        else -> null
                    }
                },
            )
        if (decision == BucketPolicy.Decision.ALLOW) return null
        return error(
            head,
            S3Error.ACCESS_DENIED,
            detail = "the policy of ${enabled.targetBucket} does not let the logging service write there",
            bucket = enabled.targetBucket,
        )
    }

    /**
     * `?policy` on a bucket: store one, read it back, remove it (M-201а).
     *
     * **The one sub-resource stored exactly as it arrived.** Everywhere else the document is
     * parsed and redrawn, which settles what to do with somebody else's formatting; here redrawing
     * is the defect. `test_set_get_del_bucket_policy` compares the string it sent with the string
     * it got back, so an equivalent document with different spacing fails a test about storage
     * while the policy itself is identical. The parse still happens — it decides whether the bytes
     * may be stored at all — but what is kept for answering is the client's own text.
     *
     * Refusing is the point of the parse. A policy accepted and not enforced reads stricter than
     * it is, and its author finds out through a leak rather than through an error (M-133), so an
     * action, a principal or a condition this server cannot decide is `MalformedPolicy` naming the
     * text that caused it.
     *
     * `DELETE` answers **204** whether or not a policy was there (`s3-service-2.json:277`), which
     * is the same shape as deleting an object that does not exist: the request asks for a state,
     * not for a change.
     */
    private suspend fun bucketPolicy(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse =
        when (route.method) {
            "GET" -> {
                val stored =
                    store.bucketSubresource(route.bucket, "policy")
                        ?: return error(head, S3Error.NO_SUCH_BUCKET_POLICY, bucket = route.bucket)
                HttpResponse(200, "OK", headers = listOf("Content-Type" to "application/json"), body = stored)
            }

            "DELETE" -> {
                store.putBucketSubresource(route.bucket, "policy", null)
                HttpResponse(204, "No Content")
            }

            else -> {
                // Read first, refuse after: answering while the client is still sending leaves both
                // sides waiting, and the suite reports that as a timeout rather than as a refusal.
                val collected = ByteArrayOutputStream()
                body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
                val document = collected.toByteArray()
                val decoded =
                    try {
                        BucketPolicy.decode(String(document))
                    } catch (e: BucketPolicy.Refused) {
                        return error(head, e.error, detail = e.message, bucket = route.bucket)
                    }
                // `BlockPublicPolicy` (M-227), and it refuses rather than stores: the setting says
                // "reject calls to PUT Bucket policy if the specified bucket policy allows public
                // access", and a document accepted here would be enforced — the flag is not a
                // filter over what a stored policy grants, that is `RestrictPublicBuckets`. `403`,
                // which is what `test_block_public_policy:14340` reads through `check_access_denied`;
                // `test_block_public_policy_with_principal:14357` is the other half and requires the
                // same bucket to take a policy naming one account.
                if (publicAccessBlockOf(route.bucket)?.blockPublicPolicy == true &&
                    BucketPolicy.isPublic(decoded)
                ) {
                    return error(
                        head,
                        S3Error.ACCESS_DENIED,
                        detail = "BlockPublicPolicy is on for this bucket, and this policy allows a principal of *",
                        bucket = route.bucket,
                    )
                }
                store.putBucketSubresource(route.bucket, "policy", document)
                // 204, not 200, and this came from the suite rather than from the model: the
                // service description gives `PutBucketPolicy` no `responseCode`, which reads as
                // 200, while every case that checks the status asserts 204 — including
                // `_set_log_bucket_policy_tenant:15380`, which stands in front of all 31 runnable
                // bucket-logging cases. The first version here answered 200 because 200 is what I
                // wrote, and the test agreed with the code rather than with a source.
                HttpResponse(204, "No Content")
            }
        }

    /**
     * `?publicAccessBlock` on a bucket: set the four switches, read them, remove them (M-227).
     *
     * Three status codes and none of them guessed. `PutPublicAccessBlock` has no `responseCode` in
     * the model and answers **200**, which `test_block_public_restrict_public_buckets:14404`
     * asserts; `DeletePublicAccessBlock` has `"responseCode": 204` and answers 204 **whether or not
     * anything was there** — `test_get_undefined_public_block:14225` deletes from a bucket that
     * never had one and requires 204, exactly like `DeleteBucketPolicy`; and a `GET` with nothing
     * stored is `404 NoSuchPublicAccessBlockConfiguration`, a code the model does not carry and
     * the suite names twice.
     *
     * The document is parsed and **redrawn**, unlike the bucket policy next door: nothing compares
     * these bytes with what it sent, and redrawing is what lets the answer carry all four members
     * when the request named one of them (see [PublicAccessBlock.encode]).
     */
    private suspend fun bucketPublicAccessBlock(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse =
        when (route.method) {
            "GET" -> {
                val stored =
                    store.bucketSubresource(route.bucket, PublicAccessBlock.NAME)
                        ?: return error(
                            head,
                            S3Error.NO_SUCH_PUBLIC_ACCESS_BLOCK_CONFIGURATION,
                            bucket = route.bucket,
                        )
                xml(stored)
            }

            "DELETE" -> {
                store.putBucketSubresource(route.bucket, PublicAccessBlock.NAME, null)
                HttpResponse(204, "No Content")
            }

            else -> {
                // Read first, refuse after, like every other body-carrying sub-resource: answering
                // while the client is still sending leaves both sides waiting (M26).
                val collected = ByteArrayOutputStream()
                body.forEach { bytes, offset, length -> collected.write(bytes, offset, length) }
                val configuration =
                    try {
                        PublicAccessBlock.decode(collected.toByteArray())
                    } catch (e: PublicAccessBlock.Refused) {
                        return error(head, e.error, detail = e.message, bucket = route.bucket)
                    } catch (e: XmlReader.MalformedXmlException) {
                        return error(head, S3Error.MALFORMED_XML, detail = e.message, bucket = route.bucket)
                    }
                store.putBucketSubresource(
                    route.bucket,
                    PublicAccessBlock.NAME,
                    PublicAccessBlock.encode(configuration),
                )
                HttpResponse(200, "OK")
            }
        }

    /**
     * `?policyStatus` on a bucket: whether it is public (M-228).
     *
     * The definition of public is [PolicyStatus] and nothing here; this is the plumbing that hands
     * it the two facts it needs. Read-only, because S3 has no operation that writes a policy
     * status — the router sends `PUT` and `DELETE` on this name to `NotImplemented` instead of
     * here (`S3Router.READ_ONLY_SUBRESOURCES`).
     *
     * A stored document that will not decode counts as **no policy**, the same reading
     * [policyDecisionFor] takes and for the same reason: it cannot arrive through
     * `PutBucketPolicy`, which decodes before it stores, and a bucket that answered `500` to this
     * because of a document written by a newer version of this server would be worse than one that
     * reports on its ACL alone.
     */
    private fun bucketPolicyStatus(route: S3Router.Route.BucketSubresource): HttpResponse {
        val policy = decodedPolicyOf(route.bucket)
        return xml(S3Documents.policyStatusResult(PolicyStatus.isPublic(store.bucketAcl(route.bucket), policy)))
    }

    /**
     * `?acl` on a bucket: read the truth, or set a canned name (M-193, M-194).
     *
     * The read side answers the owner and the grants the canned name implies; the accepting side
     * takes `x-amz-acl` and nothing else. A request whose ACL lives in the body is a list of
     * grants to named users, and this server refuses it by name rather than storing it — the rule
     * it already applies to a bucket policy, for the same reason: an unenforced permission is
     * found out as a leak.
     *
     * The body is read before the refusal, not after. It is a few hundred bytes here, but the
     * order is the one M26 paid for: answering while a client is still sending leaves both sides
     * waiting, and the suite reports it as a timeout rather than as a refusal.
     */
    private suspend fun bucketAcl(
        head: HttpRequestParser.Head,
        route: S3Router.Route.BucketSubresource,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val owner = store.bucketOwner(route.bucket) ?: OWNER
        if (route.method == "GET") {
            return xml(
                S3Documents.accessControlPolicy(owner, owner, store.bucketAcl(route.bucket), bucketOwnerId = owner),
            )
        }
        body.forEach { _, _, _ -> }
        if (route.method != "PUT") {
            return error(head, S3Error.NOT_IMPLEMENTED, detail = "not implemented: ${route.method} ?acl")
        }
        val stated =
            AccessControl.Canned.of(head.header("x-amz-acl"))
                ?: return error(
                    head,
                    S3Error.NOT_IMPLEMENTED,
                    detail = "not implemented: an access control policy of grants; this server takes x-amz-acl",
                    bucket = route.bucket,
                )
        store.setBucketAcl(route.bucket, stated.wireName)
        return HttpResponse(200, "OK")
    }

    /**
     * `?acl` on an object, and the same two halves as the bucket's.
     *
     * The owner reported is the version's own, which is not always the bucket's: a key written by
     * one access key into another's `public-read-write` bucket belongs to whoever wrote it. That
     * is the difference the `bucket-owner-*` canned names exist for, and reporting the bucket's
     * owner here would make them describe nothing.
     */
    private suspend fun objectAcl(
        head: HttpRequestParser.Head,
        route: S3Router.Route.ObjectAcl,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        val named = route.versionId
        val stored =
            (if (named == null) store.get(route.bucket, route.key) else store.get(route.bucket, route.key, named))
                ?: return error(head, S3Error.NO_SUCH_KEY, key = route.key, bucket = route.bucket)
        val owner = stored.owner ?: store.bucketOwner(route.bucket) ?: OWNER
        if (route.method == "GET") {
            return xml(
                S3Documents.accessControlPolicy(
                    owner,
                    owner,
                    stored.acl,
                    bucketOwnerId = store.bucketOwner(route.bucket),
                ),
            )
        }
        body.forEach { _, _, _ -> }
        val stated =
            AccessControl.Canned.of(head.header("x-amz-acl"))
                ?: return error(
                    head,
                    S3Error.NOT_IMPLEMENTED,
                    detail = "not implemented: an access control policy of grants; this server takes x-amz-acl",
                    key = route.key,
                    bucket = route.bucket,
                )
        store.setObjectAcl(route.bucket, route.key, stated.wireName)
        return HttpResponse(200, "OK")
    }

    /**
     * `?tagging` on an object — and the answer to "there are none" is **different** here than on a
     * bucket.
     *
     * A bucket with no set answers `404 NoSuchTagSet`; an object with no tags answers `200` with an
     * empty `TagSet`, because the object itself exists and a `404` would tell an untruth about it.
     * One operation name, two different correct answers.
     */
    private suspend fun objectTagging(
        head: HttpRequestParser.Head,
        route: S3Router.Route.ObjectTagging,
        body: HttpHandler.RequestBody,
    ): HttpResponse {
        // The version the client named, or the newest when it named none. Named and missing is a
        // `404` rather than a fall back to the newest: answering about another version is answering
        // about another object, and the answer looks entirely valid (M-305).
        val named = route.versionId
        val stored =
            if (named == null) {
                store.get(route.bucket, route.key)
            } else {
                store.get(route.bucket, route.key, named)?.takeIf { !it.deleteMarker }
            } ?: return error(head, S3Error.NO_SUCH_KEY, key = route.key, bucket = route.bucket)

        return when (route.method) {
            "GET" -> {
                xml(S3Documents.taggingResult(stored.metadata.tags)).withVersion(stored)
            }

            "DELETE" -> {
                store.setTags(route.bucket, route.key, emptyMap(), versionId = route.versionId)
                HttpResponse(204, "No Content").withVersion(stored)
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
                // Checked **before** the write, and that is half the case:
                // `test_put_excess_tags:12072` first expects a refusal and then reads the object's
                // tags and demands there be none. A refusal that leaves a set behind is not a
                // refusal.
                TagRules.check(tags)?.let {
                    return error(
                        head,
                        S3Error.INVALID_TAG,
                        detail = it.message,
                        key = route.key,
                        bucket = route.bucket,
                    )
                }
                store.setTags(route.bucket, route.key, tags, versionId = route.versionId)
                HttpResponse(200, "OK").withVersion(stored)
            }
        }
    }

    /**
     * `OPTIONS` — the preflight, the one unsigned answer this server gives.
     *
     * A refusal here is `403` rather than a `200` without the access headers. A browser reads the
     * two the same way; somebody debugging CORS does not: "no rule matched" against "a rule matched
     * and allowed nothing".
     */
    private fun preflight(
        head: HttpRequestParser.Head,
        route: S3Router.Route.Preflight,
    ): HttpResponse {
        // Both headers are what **make** this a preflight, and their absence is a malformed
        // request rather than a forbidden one (M-226). `403` reads as "you may not ask", which
        // sends the caller to look at credentials that were never the problem — and seven cases of
        // the suite sat misclassified under anonymous access for exactly that misreading.
        //
        // The method mattered more than the origin did. It used to default to `GET`, so a bare
        // `OPTIONS` carrying an `Origin` was answered **200, GET allowed**: the server agreeing to
        // something nobody had asked about.
        val origin =
            head.header("origin")
                ?: return error(
                    head,
                    S3Error.INVALID_REQUEST,
                    detail = "an OPTIONS without an Origin is not a preflight",
                    bucket = route.bucket,
                )
        val method =
            head.header("access-control-request-method")
                ?: return error(
                    head,
                    S3Error.INVALID_REQUEST,
                    detail = "an OPTIONS without Access-Control-Request-Method is not a preflight",
                    bucket = route.bucket,
                )
        // A list rather than a string: the browser enumerates everything it intends to send,
        // comma-separated, and every one of them has to be allowed.
        val asked =
            head
                .header("access-control-request-headers")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val document =
            store.bucketSubresource(route.bucket, "cors")
                ?: return error(head, S3Error.ACCESS_DENIED, bucket = route.bucket)
        val rule =
            S3Requests
                .parseCors(document)
                .matching(origin, method, asked)
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
            val named = target.versionId
            try {
                // A named version is removed for good; an unnamed one goes through the ordinary
                // delete, which in a versioning bucket lays a tombstone. The batch form carries
                // both, and treating them alike is how a versioned bucket becomes impossible to
                // empty — every entry answers `204` while the versions stay.
                val marker =
                    if (named != null) {
                        store.deleteVersion(bucket, target.key, named, bypass = bypassGovernance(head))
                        null
                    } else {
                        store.delete(bucket, target.key, precondition).marker
                    }
                // Deleting what is not there is a success in S3, so every key that got this far is
                // reported deleted — and it is reported with what actually happened: a tombstone
                // laid down is undoable, and this is the only place a batch names it (M-161).
                deleted +=
                    S3Documents.DeletedEntry(
                        key = target.key,
                        versionId = named,
                        deleteMarker = marker != null,
                        deleteMarkerVersionId = marker?.versionId,
                    )
            } catch (e: ObjectStore.PreconditionFailed) {
                errors += S3Documents.DeleteError(target.key, S3Error.PRECONDITION_FAILED.code, e.message)
            } catch (e: ObjectStore.Locked) {
                // Per key, like every other refusal in a batch: the other 999 go on being deleted,
                // and the client is told which one is held. `nuke_bucket` reads exactly this to
                // decide whether to wait out a retention period.
                errors += S3Documents.DeleteError(target.key, S3Error.ACCESS_DENIED.code, e.message, named)
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
                    store.deleteVersion(bucket, key, versionId, bypass = bypassGovernance(head))
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
        } catch (e: ObjectStore.Locked) {
            error(head, S3Error.ACCESS_DENIED, detail = e.message, key = key, bucket = bucket)
        } catch (e: MalformedCondition) {
            error(head, S3Error.INVALID_ARGUMENT, detail = e.message, key = key, bucket = bucket)
        }

    /**
     * What the upload **said** about locks, with "said nothing" kept distinct from "said none".
     *
     * Both fields are null when the header was absent, and that distinction is the whole type. An
     * upload that mentions no retention must not remove the retention already on the object — a
     * default rule may have put it there, and a write is not the place to take protection off.
     */
    private class StatedLock(
        val retention: ObjectStore.Retention?,
        val legalHold: Boolean?,
    )

    /**
     * Retention and legal hold stated as headers of the upload, when they are.
     *
     * `null` when the request says nothing about locks, which is the common case and must not
     * become "no retention" — that would be a write quietly stripping the protection a default
     * rule put on the object.
     */
    private fun lockOnUpload(head: HttpRequestParser.Head): StatedLock? {
        fun stated(name: String) = head.header(name)?.trim()?.takeIf { it.isNotEmpty() }

        val mode = stated("x-amz-object-lock-mode")
        val until = stated("x-amz-object-lock-retain-until-date")
        // `OFF` is a statement and `absent` is not, which is where this went wrong: an upload
        // carrying only `ObjectLockLegalHoldStatus: OFF` used to arrive here as "no retention
        // either" and strip one that was already in force.
        // `x-amz-object-lock-legal-hold`, and the name is the whole of a day's confusion: the
        // **field** is `ObjectLockLegalHoldStatus` in every SDK, and the **header** has no
        // `-status` on it — on the request and on the response alike. Emitting the field's name
        // means a client reads its own key and finds nothing, which surfaces as `KeyError` rather
        // than as a mismatch and says nothing about the cause.
        val legalHold = stated("x-amz-object-lock-legal-hold")?.equals("ON", ignoreCase = true)
        val retention =
            if (mode != null && until != null) {
                runCatching {
                    java.time.OffsetDateTime
                        .parse(until)
                        .toInstant()
                }.recoverCatching { java.time.Instant.parse(until) }
                    .getOrNull()
                    ?.let { ObjectStore.Retention(mode, it.toEpochMilli()) }
            } else {
                null
            }
        if (retention == null && legalHold == null) return null
        return StatedLock(retention, legalHold)
    }

    /** The caller saying out loud that it means to step over a `GOVERNANCE` lock. */
    private fun bypassGovernance(head: HttpRequestParser.Head): Boolean =
        head.header("x-amz-bypass-governance-retention")?.equals("true", ignoreCase = true) == true

    /**
     * The `Access-Control-*` headers an ordinary answer owes a browser (M-226).
     *
     * **Preflight was only ever half of CORS here.** `OPTIONS` has been answered since M14, and the
     * request that follows it carried no `Access-Control-*` at all — so a browser was told "yes,
     * you may ask" and then refused the body of the answer, because nothing in it said who may
     * read it. Nothing in this repository could see that: the tests are not browsers, and every
     * other client ignores these headers entirely.
     *
     * Applied at all three exits — [screen], [handle] and [failed] — because a browser needs to
     * read failures too: `test_cors_origin_response:6916` checks a `404` and two `403`s. One exit
     * wrapped and the others not is the shape this codebase has already paid for once (M-155).
     *
     * **The whole cost for a client that is not a browser is one header lookup.** `Origin` is sent
     * by browsers and by nothing else, so `aws-cli`, `boto3`, `rclone` and `mc` leave here on the
     * first line, before anything is routed or read.
     *
     * The match is the same one preflight uses, including its odd part: an
     * `Access-Control-Request-Method` on an ordinary request decides which rule applies, ahead of
     * the method the request actually is. That is what the suite pins, and it falls out of asking
     * one matching function rather than two.
     */
    private fun withCors(
        head: HttpRequestParser.Head,
        response: HttpResponse?,
    ): HttpResponse? {
        if (response == null) return null
        val origin = head.header("origin") ?: return response

        val route = route(head)
        // **Preflight answers for itself, in both directions**, and this used to test the wrong
        // thing: it skipped a response that already carried `Access-Control-Allow-Origin`, which
        // is true of an allowed preflight and false of a refused one — so a refusal was decorated,
        // and a `403` went out saying the request was permitted after all
        // (`test_cors_header_option:7016`, found by the closing run of M29). The condition is the
        // route, not what the answer happens to hold: a preflight that says no has said no.
        if (route is S3Router.Route.Preflight) return response

        val bucket = bucketOf(route) ?: return response
        val document = store.bucketSubresource(bucket, "cors") ?: return response
        val method = head.header("access-control-request-method") ?: head.method
        val rule =
            try {
                S3Requests.parseCors(document).matching(origin, method)
            } catch (e: XmlReader.MalformedXmlException) {
                // A configuration that will not parse cannot say who may read this, and saying
                // nothing is the answer a browser understands: it refuses, which is what an
                // unreadable CORS document should produce.
                return response
            } ?: return response

        // `*` is answered as `*` rather than echoed back (`test_cors_origin_wildcard`): the two are
        // different promises, and a browser may cache the first for any origin at all.
        val allowed = if (rule.allowedOrigins.any { it == "*" }) "*" else origin
        return response.copy(
            headers =
                response.headers +
                    buildList {
                        add("Access-Control-Allow-Origin" to allowed)
                        add("Access-Control-Allow-Methods" to rule.allowedMethods.joinToString(", "))
                        if (rule.exposeHeaders.isNotEmpty()) {
                            add("Access-Control-Expose-Headers" to rule.exposeHeaders.joinToString(", "))
                        }
                    },
        )
    }

    private fun route(head: HttpRequestParser.Head): S3Router.Route =
        router.route(
            head.method,
            head.header("host") ?: "",
            head.path,
            head.query,
            head.header("x-amz-copy-source"),
        )

    /**
     * What each operation needs from the key that signed it — the whole table, in one place.
     *
     * A table and not a condition at each call site, because permissions are read far more often
     * than they are written and the question asked of them is always "what may this key do", never
     * "what does this line do". Fifty conditions scattered through a handler answer the second
     * question and cannot be made to answer the first.
     *
     * `READ` is `GET`, `HEAD` and the listings. Everything else is `WRITE`, including operations
     * that only look like reads — `POST ?delete` is a delete, a multipart completion is a write,
     * and a pre-flight is neither, which is why it is absent (it carries no signature at all).
     */
    private fun needOf(route: S3Router.Route): KeyScope.Need =
        when (route) {
            is S3Router.Route.ListBuckets,
            is S3Router.Route.HeadBucket,
            is S3Router.Route.GetBucketLocation,
            is S3Router.Route.ListObjectsV2,
            is S3Router.Route.ListObjects,
            is S3Router.Route.ListObjectVersions,
            is S3Router.Route.ListMultipartUploads,
            is S3Router.Route.ListParts,
            is S3Router.Route.GetObject,
            is S3Router.Route.HeadObject,
            is S3Router.Route.GetObjectAttributes,
            -> {
                KeyScope.Need.READ
            }

            // A sub-resource is a read or a write by its method, not by its name: the same route
            // carries `GET ?tagging` and `PUT ?tagging`.
            is S3Router.Route.BucketSubresource -> {
                if (route.method == "GET") KeyScope.Need.READ else KeyScope.Need.WRITE
            }

            is S3Router.Route.ObjectTagging -> {
                if (route.method == "GET") KeyScope.Need.READ else KeyScope.Need.WRITE
            }

            is S3Router.Route.ObjectAcl -> {
                if (route.method == "GET") KeyScope.Need.READ else KeyScope.Need.WRITE
            }

            is S3Router.Route.ObjectLockSubresource -> {
                if (route.method == "GET") KeyScope.Need.READ else KeyScope.Need.WRITE
            }

            else -> {
                KeyScope.Need.WRITE
            }
        }

    /**
     * Refuses what the signing key may not do — **from the head of the request** (§1.2).
     *
     * Before the body, deliberately: an upload into a bucket the key cannot see must not cost five
     * gigabytes to refuse, and this is the one check that can always be made from the head because
     * it depends on nothing the body carries.
     *
     * Invisibility comes before refusal. A key outside a bucket's scope is told the bucket does
     * not exist rather than that it may not have it — `NoSuchBucket`, not `AccessDenied` — because
     * a refusal is itself an answer: it confirms the name. `ListBuckets` follows the same rule by
     * filtering rather than refusing (see [listBuckets]).
     */
    private fun scopeRefusal(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        accessKeyId: String,
    ): HttpResponse? {
        val scope = verifier.credentials.scopeFor(accessKeyId)
        val bucket = bucketOf(route)
        if (bucket != null && !scope.sees(bucket)) {
            return error(head, S3Error.NO_SUCH_BUCKET, bucket = bucket)
        }
        if (!scope.allows(needOf(route))) {
            return error(
                head,
                S3Error.ACCESS_DENIED,
                detail = "this key is read-only",
                bucket = bucket,
            )
        }
        return null
    }

    /** The canned ACL the request names, if it names one this server enforces. */
    private fun statedAcl(head: HttpRequestParser.Head): String? =
        AccessControl.Canned.of(head.header("x-amz-acl"))?.wireName

    /**
     * Refuses an ACL this server would have to pretend about (M-194).
     *
     * Two shapes of pretence, and both are refused rather than stored: a canned name nobody here
     * enforces, and an explicit grant. A grant names a **user** — `x-amz-grant-read: id="..."` —
     * and this server has access keys and no user table, so accepting one means writing down a
     * permission for somebody who does not exist and never applying it. That is the rule the whole
     * milestone is built on: a permission accepted and not enforced is found out as a leak rather
     * than as an error.
     *
     * In `screen` because `PUT` carries a body: a refusal decided from the head must be answered
     * before the client starts sending one.
     */
    private fun statedAclRefusal(head: HttpRequestParser.Head): HttpResponse? {
        head.headers.firstOrNull { (name, _) -> name.startsWith("x-amz-grant-", ignoreCase = true) }?.let { (name, _) ->
            return error(
                head,
                S3Error.NOT_IMPLEMENTED,
                detail = "not implemented: $name; this server takes canned ACLs, not grants to named users",
            )
        }
        val stated = head.header("x-amz-acl") ?: return null
        if (AccessControl.Canned.of(stated) != null) return null
        return error(
            head,
            S3Error.INVALID_ARGUMENT,
            detail =
                "'$stated' is not an ACL this server enforces; it takes " +
                    AccessControl.Canned.entries.joinToString(", ") { it.wireName },
        )
    }

    /**
     * Refuses what the owner and the canned ACL do not allow — after the key scope and never
     * before it (M-196, M27).
     *
     * The order is the whole of the answer to "two models of permissions, which one wins": the
     * scope narrows first and this decides inside what it left. Both are refusals, neither is a
     * grant, so no arrangement of ACLs can hand back what configuration took away.
     *
     * From the head, like [scopeRefusal] and for the same reason: an overwrite refused after five
     * gigabytes have arrived costs five gigabytes, and — worse — a refusal written after the body
     * has begun leaves the client pushing bytes at a server that has already answered, which the
     * suite reports as a timeout rather than as a refusal (M26).
     *
     * A bucket with no recorded owner returns early and unrestricted. That is the upgrade rule,
     * and it is not a special case bolted on: the unit of this model is the bucket, so a bucket
     * created before the model existed has no model.
     */
    private fun aclRefusal(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        accessKeyId: String?,
    ): HttpResponse? {
        // `x-amz-expected-bucket-owner` is a permission the caller states about the **bucket**,
        // and it is checked before anything else here because it does not depend on who is asking:
        // "answer me only if this bucket is still owned by X". Accepted and not enforced it is the
        // shape this repository refuses everywhere — and it was exactly that until M28 opened the
        // door far enough for a stranger to reach a `public-read-write` bucket while asking it
        // (`ceph/s3-tests`: `test_expected_bucket_owner`).
        expectedBucketOwnerRefusal(head, route)?.let { return it }

        // Three places below decide **without** asking the access model, and each of them means
        // "allowed" (M28). For a request that named nobody they would each be a hole, so each says
        // so here rather than being caught by a rule further down that never runs.
        //
        // The first: a route with no bucket has no ACL to consult. `ListBuckets` is the whole of
        // that set, and it is answered elsewhere by an owner filter — which for nobody would list
        // every bucket nobody owns.
        val bucketName =
            bucketOf(route)
                ?: return if (accessKeyId == null) refuseNobody(head, route, null) else null
        val bucket =
            underPublicAccessBlock(
                bucketName,
                AccessControl.Resource(store.bucketOwner(bucketName), store.bucketAcl(bucketName)),
            )

        // Layer three (M-201б), and the two halves of it sit on either side of the ACL because
        // they are not the same kind of answer. A `Deny` is stronger than everything, including a
        // bucket that has no access model at all, so it is asked first. An `Allow` is weaker than
        // a refusal already made — the key's own scope has had its say further up — so it is read
        // after, where it can turn an ACL's "no" into a "yes" and nothing else.
        val policy = policyDecision(head, route, bucketName, accessKeyId)
        if (policy == BucketPolicy.Decision.DENY && !ownerKeepsThePolicyHandle(route, accessKeyId, bucket)) {
            return error(
                head,
                S3Error.ACCESS_DENIED,
                detail = "a bucket policy denies it",
                key = keyOf(route),
                bucket = bucketName,
            )
        }

        // The second: a bucket older than the model has no model. Among keys — see AccessControl.
        if (bucket.unrestricted) {
            return if (accessKeyId == null) refuseNobody(head, route, bucketName) else null
        }

        if (policy == BucketPolicy.Decision.ALLOW) return null

        val allowed =
            when (route) {
                // Creating a bucket that exists is not an access question — it is answered with
                // `BucketAlreadyExists` by the handler, which is what a client needs to hear.
                // The third: creating a bucket is not an access question — it is answered with
                // `BucketAlreadyExists` by the handler, which is what a client needs to hear. For
                // nobody it is a different question with an obvious answer.
                is S3Router.Route.CreateBucket -> {
                    accessKeyId != null
                }

                // Deleting a bucket is not something a canned ACL can grant: `public-read-write`
                // opens the objects in a bucket, not the bucket's own existence.
                is S3Router.Route.DeleteBucket -> {
                    accessKeyId == bucket.owner
                }

                is S3Router.Route.ListObjects,
                is S3Router.Route.ListObjectsV2,
                is S3Router.Route.ListObjectVersions,
                is S3Router.Route.ListMultipartUploads,
                is S3Router.Route.HeadBucket,
                is S3Router.Route.GetBucketLocation,
                -> {
                    AccessControl.allows(bucket, accessKeyId, AccessControl.Permission.READ, bucket.owner)
                }

                // The bucket's own settings — its ACL, its tags, its versioning — belong to whoever
                // owns it. A canned name says who may read the data, and never who may re-configure
                // the thing holding it.
                is S3Router.Route.BucketSubresource -> {
                    val permission =
                        if (route.method == "GET") {
                            AccessControl.Permission.READ_ACP
                        } else {
                            AccessControl.Permission.WRITE_ACP
                        }
                    AccessControl.allows(bucket, accessKeyId, permission, bucket.owner)
                }

                is S3Router.Route.GetObject -> {
                    objectRead(head, route, bucketName, route.key, route.versionId, accessKeyId, bucket)
                }

                is S3Router.Route.HeadObject -> {
                    objectRead(head, route, bucketName, route.key, route.versionId, accessKeyId, bucket)
                }

                is S3Router.Route.GetObjectAttributes -> {
                    objectRead(head, route, bucketName, route.key, route.versionId, accessKeyId, bucket)
                }

                is S3Router.Route.ObjectTagging -> {
                    if (route.method == "GET") {
                        objectRead(head, route, bucketName, route.key, null, accessKeyId, bucket)
                    } else {
                        AccessControl.allowsObjectWrite(bucket, accessKeyId)
                    }
                }

                is S3Router.Route.ObjectAcl -> {
                    val obj = objectResource(bucketName, route.key, route.versionId) ?: return null
                    val permission =
                        if (route.method == "GET") {
                            AccessControl.Permission.READ_ACP
                        } else {
                            AccessControl.Permission.WRITE_ACP
                        }
                    AccessControl.allows(obj, accessKeyId, permission, bucket.owner)
                }

                // Everything else writes objects, and writing an object asks the bucket. That is
                // not a simplification: the suite's matrix has a `public-read-write` bucket accept
                // an overwrite of a **private** object, so the object's own ACL is not consulted
                // on the way in (`test_access_bucket_publicreadwrite_object_private`).
                else -> {
                    AccessControl.allowsObjectWrite(bucket, accessKeyId)
                }
            }

        if (allowed) return null
        return error(
            head,
            S3Error.ACCESS_DENIED,
            detail = "the acl of this ${if (keyOf(route) == null) "bucket" else "object"} does not allow it",
            key = keyOf(route),
            bucket = bucketName,
        )
    }

    /**
     * The one door a policy cannot lock: the owner's own `?policy`.
     *
     * S3 says it plainly (`s3-service-2.json:1257`, and again for the `GET` and `DELETE`): the
     * bucket owner can call `GetBucketPolicy`, `PutBucketPolicy` and `DeleteBucketPolicy` even
     * when the policy explicitly denies them. Without it the first typo in a `Deny` statement
     * bricks the bucket for ever — there would be no request left that could remove the document.
     */
    private fun ownerKeepsThePolicyHandle(
        route: S3Router.Route,
        accessKeyId: String?,
        bucket: AccessControl.Resource,
    ): Boolean =
        route is S3Router.Route.BucketSubresource &&
            route.name == "policy" &&
            accessKeyId != null &&
            accessKeyId == bucket.owner

    /**
     * The action a route asks for, in the names a bucket policy uses (M-201б).
     *
     * `null` means "no policy can speak about this": the health handle, a preflight, a route the
     * server does not implement, and `ListBuckets` — which names no bucket, so there is no bucket
     * whose policy could be consulted. Everything else has a name, and
     * `BucketPolicyReachTest` walks the router's own sealed hierarchy to prove it, the way
     * `AnonymousReachTest` does for the anonymous decision.
     *
     * A `versionId` changes the action rather than the resource: S3 spells reading a named version
     * `s3:GetObjectVersion`, and a policy that grants `s3:GetObject` alone does not hand out the
     * history.
     */
    private fun policyActionOf(route: S3Router.Route): String? =
        when (route) {
            is S3Router.Route.ListObjects, is S3Router.Route.ListObjectsV2, is S3Router.Route.HeadBucket -> {
                "s3:ListBucket"
            }

            is S3Router.Route.ListObjectVersions -> {
                "s3:ListBucketVersions"
            }

            is S3Router.Route.ListMultipartUploads -> {
                "s3:ListBucketMultipartUploads"
            }

            is S3Router.Route.GetBucketLocation -> {
                "s3:GetBucketLocation"
            }

            is S3Router.Route.CreateBucket -> {
                "s3:CreateBucket"
            }

            is S3Router.Route.DeleteBucket -> {
                "s3:DeleteBucket"
            }

            is S3Router.Route.BucketSubresource -> {
                val write = route.method != "GET"
                when (route.name) {
                    "tagging" -> {
                        if (write) "s3:PutBucketTagging" else "s3:GetBucketTagging"
                    }

                    "cors" -> {
                        if (write) "s3:PutBucketCORS" else "s3:GetBucketCORS"
                    }

                    "versioning" -> {
                        if (write) "s3:PutBucketVersioning" else "s3:GetBucketVersioning"
                    }

                    "object-lock" -> {
                        if (write) "s3:PutBucketObjectLockConfiguration" else "s3:GetBucketObjectLockConfiguration"
                    }

                    "lifecycle" -> {
                        if (write) "s3:PutLifecycleConfiguration" else "s3:GetLifecycleConfiguration"
                    }

                    "acl" -> {
                        if (write) "s3:PutBucketAcl" else "s3:GetBucketAcl"
                    }

                    BucketLogging.NAME -> {
                        if (write) "s3:PutBucketLogging" else "s3:GetBucketLogging"
                    }

                    // Removing the configuration takes the **Put** permission, not a delete one:
                    // `DeletePublicAccessBlock` in the model says "to use this operation, you must
                    // have the s3:PutBucketPublicAccessBlock permission", and there is no
                    // `s3:DeleteBucketPublicAccessBlock` to have instead.
                    PublicAccessBlock.NAME -> {
                        if (write) "s3:PutBucketPublicAccessBlock" else "s3:GetBucketPublicAccessBlock"
                    }

                    "policy" -> {
                        when (route.method) {
                            "GET" -> "s3:GetBucketPolicy"
                            "DELETE" -> "s3:DeleteBucketPolicy"
                            else -> "s3:PutBucketPolicy"
                        }
                    }

                    // Read-only, so there is no write name to choose between: the router produces
                    // this route for `GET` alone. And unlike `?policy` above, a `Deny` on it is
                    // allowed to bite — refusing to say whether a bucket is public bricks nothing,
                    // because the document that says so can still be removed.
                    "policyStatus" -> {
                        "s3:GetBucketPolicyStatus"
                    }

                    else -> {
                        null
                    }
                }
            }

            is S3Router.Route.GetObject -> {
                if (route.versionId == null) "s3:GetObject" else "s3:GetObjectVersion"
            }

            is S3Router.Route.HeadObject -> {
                if (route.versionId == null) "s3:GetObject" else "s3:GetObjectVersion"
            }

            is S3Router.Route.GetObjectAttributes -> {
                if (route.versionId == null) "s3:GetObjectAttributes" else "s3:GetObjectVersionAttributes"
            }

            is S3Router.Route.DeleteObject -> {
                if (route.versionId ==
                    null
                ) {
                    "s3:DeleteObject"
                } else {
                    "s3:DeleteObjectVersion"
                }
            }

            is S3Router.Route.DeleteObjects -> {
                "s3:DeleteObject"
            }

            is S3Router.Route.PutObject, is S3Router.Route.PostObject -> {
                "s3:PutObject"
            }

            is S3Router.Route.CopyObject, is S3Router.Route.UploadPartCopy -> {
                "s3:PutObject"
            }

            is S3Router.Route.CreateMultipartUpload, is S3Router.Route.UploadPart -> {
                "s3:PutObject"
            }

            is S3Router.Route.CompleteMultipartUpload -> {
                "s3:PutObject"
            }

            is S3Router.Route.AbortMultipartUpload -> {
                "s3:AbortMultipartUpload"
            }

            is S3Router.Route.ListParts -> {
                "s3:ListMultipartUploadParts"
            }

            // No `versionId` on this route: `?tagging` on an object always means the current
            // version here, which is why the version-flavoured tagging actions never appear.
            is S3Router.Route.ObjectTagging -> {
                when (route.method) {
                    "GET" -> "s3:GetObjectTagging"
                    "DELETE" -> "s3:DeleteObjectTagging"
                    else -> "s3:PutObjectTagging"
                }
            }

            is S3Router.Route.ObjectAcl -> {
                if (route.method == "GET") {
                    if (route.versionId == null) "s3:GetObjectAcl" else "s3:GetObjectVersionAcl"
                } else {
                    if (route.versionId == null) "s3:PutObjectAcl" else "s3:PutObjectVersionAcl"
                }
            }

            is S3Router.Route.ObjectLockSubresource -> {
                when {
                    route.name == "retention" && route.method == "GET" -> "s3:GetObjectRetention"
                    route.name == "retention" -> "s3:PutObjectRetention"
                    route.method == "GET" -> "s3:GetObjectLegalHold"
                    else -> "s3:PutObjectLegalHold"
                }
            }

            // No bucket, so no bucket policy: `ListBuckets` is filtered by owner instead (M27),
            // and the rest are not requests about stored things at all.
            is S3Router.Route.ListBuckets -> {
                null
            }

            is S3Router.Route.Preflight -> {
                null
            }

            is S3Router.Route.Health -> {
                null
            }

            is S3Router.Route.NotImplemented -> {
                null
            }
        }

    /**
     * The ARN a route names: the bucket, or one of its objects.
     *
     * The key goes in raw. An S3 resource ARN is not URL-encoded — a policy author writes
     * `arn:aws:s3:::photos/holiday photo.jpg`, spaces and all — and encoding it here would make
     * every pattern miss on exactly the keys that need one.
     */
    private fun policyResourceOf(
        route: S3Router.Route,
        bucket: String,
    ): String {
        val key = keyOf(route) ?: return BucketPolicy.ARN_PREFIX + bucket
        return BucketPolicy.ARN_PREFIX + bucket + "/" + key
    }

    /**
     * What the bucket's policy says, or [BucketPolicy.Decision.NEUTRAL] when there is none.
     *
     * The document is decoded once per version of itself rather than once per request. `store`
     * hands back the same array until somebody replaces it, so identity is the whole validity
     * check — and without this a bucket with a policy would pay a JSON parse on every read, next
     * to an ACL decision measured at 12–14 ns (M-209).
     *
     * A stored document that will not decode is treated as **no policy at all** rather than as a
     * refusal. It cannot happen through `PutBucketPolicy`, which decodes before it stores; it can
     * happen to a journal written by a newer version of this server, and a bucket that answers
     * `500` to every request because of a document it cannot read would be worse than one that
     * falls back to its ACL.
     */
    private fun policyDecision(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        bucket: String,
        accessKeyId: String?,
    ): BucketPolicy.Decision {
        val action = policyActionOf(route) ?: return BucketPolicy.Decision.NEUTRAL
        return policyDecisionFor(head, route, bucket, accessKeyId, action, policyResourceOf(route, bucket))
    }

    /**
     * [policyDecision] with the action and the resource spelled out, which a copy's source needs:
     * the route says "write the destination", and the question here is about reading the source.
     */
    private fun policyDecisionFor(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        bucket: String,
        accessKeyId: String?,
        action: String,
        resource: String,
        keys: ((String) -> String?)? = null,
    ): BucketPolicy.Decision {
        val policy = decodedPolicyOf(bucket) ?: return BucketPolicy.Decision.NEUTRAL
        val decision =
            BucketPolicy.evaluate(
                policy,
                accessKeyId,
                action,
                resource,
                keys ?: policyKeys(head, route, bucket),
            )
        // `RestrictPublicBuckets` lives here rather than at any one call site, and that is the
        // whole reason it cannot be forgotten by one of them: an `Allow` from a public policy has
        // to stop being an `Allow` everywhere it is read — the screen, the copy's source, and the
        // question of whether a stranger may be told a key is missing. A `Deny` and a `NEUTRAL`
        // pass through untouched: the flag restricts what a public policy grants, and a policy
        // that grants nothing here is not what it is about.
        if (decision != BucketPolicy.Decision.ALLOW) return decision
        if (restrictsPublicPolicy(bucket, accessKeyId, policy)) return BucketPolicy.Decision.NEUTRAL
        return decision
    }

    /** The bucket's policy, decoded once per version of itself, or `null` when there is none. */
    private fun decodedPolicyOf(bucket: String): BucketPolicy.Policy? {
        val stored = store.bucketSubresource(bucket, "policy") ?: return null
        val cached = decodedPolicies[bucket]
        if (cached != null && cached.first === stored) return cached.second
        val decoded =
            try {
                BucketPolicy.decode(String(stored))
            } catch (e: BucketPolicy.Refused) {
                return null
            }
        decodedPolicies[bucket] = stored to decoded
        return decoded
    }

    /**
     * Whether `RestrictPublicBuckets` takes this `Allow` away again (M-227).
     *
     * The setting reads, in the model's words, "restricts access to this bucket to only Amazon Web
     * Services service principals and authorized users within this account if the bucket has a
     * public policy" — so three parties keep what the document gave them and everybody else loses
     * it: the owner, whose account this is, and the logging service acting on a bucket's behalf
     * (M-202), which is the one service principal here. Anyone else — another key, and a request
     * carrying no credentials at all — falls back to the ACL, which is where
     * `test_block_public_restrict_public_buckets:14375` requires an anonymous reader to end up
     * while the owner still reads the same object.
     *
     * Note what is **not** taken away: an explicit `Deny`. The flag exists to stop a policy handing
     * things out, and a policy that refuses is not handing anything out.
     */
    private fun restrictsPublicPolicy(
        bucket: String,
        accessKeyId: String?,
        policy: BucketPolicy.Policy,
    ): Boolean {
        if (publicAccessBlockOf(bucket)?.restrictPublicBuckets != true) return false
        if (accessKeyId != null && accessKeyId.startsWith(BucketPolicy.SERVICE_PREFIX)) return false
        if (accessKeyId != null && accessKeyId == store.bucketOwner(bucket)) return false
        return BucketPolicy.isPublic(policy)
    }

    /**
     * The bucket's `PublicAccessBlock`, decoded once per version of itself (M-227).
     *
     * Cached the way the policy beside it is, and for the same reason: this is read on the access
     * path, and an XML parse per request next to an ACL decision measured at 12–14 ns (M-209) would
     * be the expensive half of every answer. A document that will not decode reads as **no
     * configuration** rather than as a refusal — it cannot arrive through the operation, which
     * decodes before it stores, and a bucket answering `500` to everything because of a journal
     * written by a newer server would be worse than one whose flags are not applied.
     */
    private fun publicAccessBlockOf(bucket: String): PublicAccessBlock.Configuration? {
        val stored = store.bucketSubresource(bucket, PublicAccessBlock.NAME) ?: return null
        val cached = decodedBlocks[bucket]
        if (cached != null && cached.first === stored) return cached.second
        val decoded =
            try {
                PublicAccessBlock.decode(stored)
            } catch (e: PublicAccessBlock.Refused) {
                return null
            } catch (e: XmlReader.MalformedXmlException) {
                return null
            }
        decodedBlocks[bucket] = stored to decoded
        return decoded
    }

    /**
     * The resource as the access model must see it once `IgnorePublicAcls` is on (M-227).
     *
     * A public canned name stops granting and **stays stored**: "enabling this setting doesn't
     * affect the persistence of any existing ACLs", so `GetBucketAcl` and `GetObjectAcl` keep
     * answering `public-read` while nothing acts on it. `test_ignore_public_acls:14415` walks
     * exactly that pair — it sets the ACL again *after* switching the flag on, gets its `200`, and
     * then requires the second key to be refused anyway.
     *
     * The cheap test comes first on purpose. Every request that reaches the access model passes
     * through here, and all but the public ones are answered by an enum comparison rather than by
     * two lookups in the store.
     */
    private fun underPublicAccessBlock(
        bucket: String,
        resource: AccessControl.Resource,
    ): AccessControl.Resource {
        if (!resource.canned.public) return resource
        if (publicAccessBlockOf(bucket)?.ignorePublicAcls != true) return resource
        return resource.copy(acl = AccessControl.Canned.PRIVATE.wireName)
    }

    /**
     * Refuses a public canned ACL while `BlockPublicAcls` is on (M-227).
     *
     * Decided from the head, beside [statedAclRefusal] and for the same reason: `x-amz-acl` travels
     * as a header on every route that can carry one — `PutObject`, `CreateBucket`, a copy, the
     * start of a multipart upload, `PUT ?acl` on a bucket and on an object — so one question here
     * covers all of them, and covers them **before** a body is read. The suite asks it twice from
     * the two ends: `test_block_public_put_bucket_acls:14283` on the bucket's own ACL and
     * `test_block_public_object_canned_acls:14312` on an object arriving with one, which also
     * requires `private` to still go through.
     *
     * A bucket that does not exist yet has no configuration, so `CreateBucket` with a public ACL is
     * not refused here — nor should it be: the block that would refuse it is the one the bucket is
     * about to be created without.
     */
    private fun publicAclRefusal(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
    ): HttpResponse? {
        val stated = AccessControl.Canned.of(head.header("x-amz-acl")) ?: return null
        if (!stated.public) return null
        val bucket = bucketOf(route) ?: return null
        if (publicAccessBlockOf(bucket)?.blockPublicAcls != true) return null
        return error(
            head,
            S3Error.ACCESS_DENIED,
            detail = "BlockPublicAcls is on for this bucket, and '${stated.wireName}' is a public acl",
            key = keyOf(route),
            bucket = bucket,
        )
    }

    /**
     * The value of a condition key for this request, or `null` when the request does not carry one
     * (M-201в).
     *
     * `null` is not "no": an absent key fails a positive test and passes a negated one, and
     * `BucketPolicy.holds` is where that rule lives. What matters here is only that a key nobody
     * sent stays absent rather than becoming an empty string, which would satisfy
     * `StringEquals: ""` and, worse, fail `StringNotEquals: ""`.
     *
     * Tags are read **lazily** and only for the key the policy asks about: reading them costs a
     * lookup in the index, and most statements never mention a tag. Everything else is already in
     * the head.
     */
    private fun policyKeys(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        bucket: String,
    ): (String) -> String? {
        // Raw bytes, then decoded here: a prefix is a piece of a key and a key need not be valid
        // UTF-8 (Р3). A condition compares text, so the comparison happens on the decoded form —
        // and a prefix that does not decode simply will not equal anything a policy spells.
        val params = rawQueryParams(head.query).mapValues { String(it.value) }
        return fun(name: String): String? =
            when {
                name.startsWith("s3:ExistingObjectTag/") -> {
                    val key = keyOf(route) ?: return null
                    val stored = store.currentVersion(bucket, key) ?: return null
                    stored.metadata.tags[name.removePrefix("s3:ExistingObjectTag/")]
                }

                name.startsWith("s3:RequestObjectTag/") -> {
                    taggingHeader(head)[name.removePrefix("s3:RequestObjectTag/")]
                }

                name == "s3:prefix" -> {
                    params["prefix"]
                }

                name == "s3:delimiter" -> {
                    params["delimiter"]
                }

                name == "s3:max-keys" -> {
                    params["max-keys"]
                }

                name == "s3:VersionId" -> {
                    params["versionId"]
                }

                // The rest are headers under their own names, minus the `s3:` the policy language
                // puts in front of them.
                name.startsWith("s3:x-amz-") -> {
                    head.header(name.removePrefix("s3:"))
                }

                name == "aws:Referer" -> {
                    head.header("referer")
                }

                name == "aws:UserAgent" -> {
                    head.header("user-agent")
                }

                else -> {
                    null
                }
            }
    }

    /**
     * `x-amz-tagging` as a map: the header is a query string, `a=1&b=2`, percent-encoded.
     *
     * Read here rather than reused from the upload path because this runs **before** the request
     * is handled — the access decision happens on the head alone (research §1.2.2), and by the
     * time the upload parses its own tags the answer is already needed.
     */
    private fun taggingHeader(head: HttpRequestParser.Head): Map<String, String> {
        val raw = head.header("x-amz-tagging") ?: return emptyMap()
        return raw
            .split("&")
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                String(UriCodec.decode(name, plusIsSpace = true)) to String(UriCodec.decode(value, plusIsSpace = true))
            }
    }

    /**
     * The object as the access model sees it, or `null` when there is no such object.
     *
     * `null` means "do not refuse here": a key that is not there is a `404`, and answering `403`
     * for it would tell a stranger which keys exist — the same reasoning that makes a bucket
     * outside a key's scope answer `NoSuchBucket`.
     */
    private fun objectResource(
        bucket: String,
        key: ObjectKey,
        versionId: String?,
    ): AccessControl.Resource? {
        val stored =
            (if (versionId == null) store.currentVersion(bucket, key) else store.get(bucket, key, versionId))
                ?: return null
        return underPublicAccessBlock(bucket, AccessControl.Resource(stored.owner, stored.acl))
    }

    /**
     * `x-amz-expected-bucket-owner`, which is a condition rather than a credential.
     *
     * A bucket whose owner was never recorded can satisfy no expectation at all, and answering as
     * though it did would be the opposite of what the header is for: it exists so that a client
     * writing to a bucket name that may have changed hands finds out instead of writing.
     */
    private fun expectedBucketOwnerRefusal(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
    ): HttpResponse? {
        val expected = head.header("x-amz-expected-bucket-owner")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bucketName = bucketOf(route) ?: return null
        if (store.bucketOwner(bucketName) == expected) return null
        return error(
            head,
            S3Error.ACCESS_DENIED,
            detail = "this bucket is not owned by $expected",
            key = keyOf(route),
            bucket = bucketName,
        )
    }

    /** The refusal a request that named nobody gets where there is no ACL to ask. */
    private fun refuseNobody(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        bucket: String?,
    ): HttpResponse =
        error(
            head,
            S3Error.ACCESS_DENIED,
            detail = "this asks for something no acl can grant to a request without credentials",
            key = keyOf(route),
            bucket = bucket,
        )

    /**
     * Whether this caller may **read the source** of a copy, and the refusal if not (found in M29).
     *
     * A copy is two requests wearing one. The screen ahead of the handler sees the route it
     * arrived on — a write to the destination — and screens that; the read of the source it cannot
     * see, because the source travels in a header rather than in the path. Until this existed
     * nothing asked at all: a key able to write into a bucket it owns could copy **any** object in
     * the store into it, and got that object's `ETag` back in the answer.
     *
     * Asked in the handler rather than in the screen on purpose: the source has to be resolved
     * before it can be judged — which version, whose object — and resolving it upstream as well
     * would double the index lookup on every copy.
     */
    private fun copySourceRefusal(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        sourceBucket: String,
        sourceKey: ObjectKey,
        sourceVersionId: String?,
        accessKeyId: String?,
    ): HttpResponse? {
        val bucketResource =
            underPublicAccessBlock(
                sourceBucket,
                AccessControl.Resource(store.bucketOwner(sourceBucket), store.bucketAcl(sourceBucket)),
            )
        val action = if (sourceVersionId == null) "s3:GetObject" else "s3:GetObjectVersion"
        val resource = BucketPolicy.ARN_PREFIX + sourceBucket + "/" + sourceKey
        val decision = policyDecisionFor(head, route, sourceBucket, accessKeyId, action, resource)
        if (decision == BucketPolicy.Decision.DENY) {
            return error(
                head,
                S3Error.ACCESS_DENIED,
                detail = "a bucket policy denies reading the source of this copy",
                key = sourceKey,
                bucket = sourceBucket,
            )
        }
        if (bucketResource.unrestricted || decision == BucketPolicy.Decision.ALLOW) return null
        if (objectRead(head, route, sourceBucket, sourceKey, sourceVersionId, accessKeyId, bucketResource)) return null
        return error(
            head,
            S3Error.ACCESS_DENIED,
            detail = "the acl of the source of this copy does not allow reading it",
            key = sourceKey,
            bucket = sourceBucket,
        )
    }

    private fun objectRead(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        bucket: String,
        key: ObjectKey,
        versionId: String?,
        accessKeyId: String?,
        bucketResource: AccessControl.Resource,
    ): Boolean {
        val obj =
            objectResource(bucket, key, versionId)
                ?: return mayLearnTheKeyIsMissing(head, route, bucket, key, accessKeyId, bucketResource)
        return AccessControl.allowsObjectRead(obj, accessKeyId, bucketResource.owner)
    }

    /**
     * Whether this caller may be told that a key is **not there** (M-201г).
     *
     * `404` and `403` answer different questions, and which one a missing key deserves is decided
     * by permission to **list**, not by permission to read: whoever may enumerate the bucket could
     * have discovered the key's absence anyway, and whoever may not would be learning something
     * from the difference. `test_head_object_404_with_policy_prefix:20384` pins exactly that — the
     * same bucket answers `404` for a key under the prefix its policy names and `403` for one
     * outside it.
     *
     * So the question asked here is `s3:ListBucket` on the bucket, with `s3:prefix` set to the key
     * itself. That substitution is the part that reads oddly and is what S3 does: for a request
     * about one key, the key **is** the prefix being listed.
     */
    private fun mayLearnTheKeyIsMissing(
        head: HttpRequestParser.Head,
        route: S3Router.Route,
        bucket: String,
        key: ObjectKey,
        accessKeyId: String?,
        bucketResource: AccessControl.Resource,
    ): Boolean {
        val decision =
            policyDecisionFor(
                head,
                route,
                bucket,
                accessKeyId,
                action = "s3:ListBucket",
                resource = BucketPolicy.ARN_PREFIX + bucket,
                keys = { name -> if (name == "s3:prefix") key.toString() else policyKeys(head, route, bucket)(name) },
            )
        if (decision == BucketPolicy.Decision.DENY) return false
        if (decision == BucketPolicy.Decision.ALLOW) return true
        return AccessControl.allows(bucketResource, accessKeyId, AccessControl.Permission.READ, bucketResource.owner)
    }

    /**
     * The bucket a route names, when it names one.
     *
     * `PostObject` was missing from here until M-225, and nothing had noticed because nothing had
     * asked: the form's own handler carried the name in a local of its own. What it cost was
     * quiet — the bucket was absent from every refusal a form got and from the CORS headers on
     * those refusals — and it would have cost more the moment the access model started being
     * consulted here, since a bucket policy asked about `null` is a policy that speaks about
     * nothing.
     */
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
            is S3Router.Route.PostObject -> route.bucket
            is S3Router.Route.GetObject -> route.bucket
            is S3Router.Route.HeadObject -> route.bucket
            is S3Router.Route.DeleteObject -> route.bucket
            is S3Router.Route.CopyObject -> route.bucket
            is S3Router.Route.GetObjectAttributes -> route.bucket
            is S3Router.Route.BucketSubresource -> route.bucket
            is S3Router.Route.ObjectTagging -> route.bucket
            is S3Router.Route.ObjectAcl -> route.bucket
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
            is S3Router.Route.ObjectAcl -> route.key
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
        /**
         * Classes an object may be stored under here.
         *
         * All of them are readable immediately; the list is deliberately not "every name S3 has".
         * `GLACIER` and `DEEP_ARCHIVE` are missing on purpose and `EXPRESS_ONEZONE` because it
         * belongs to directory buckets, which this server does not have.
         */
        private val STORABLE_CLASSES =
            setOf(
                ObjectStore.STANDARD_STORAGE_CLASS,
                "REDUCED_REDUNDANCY",
                "STANDARD_IA",
                "ONEZONE_IA",
                "INTELLIGENT_TIERING",
                "GLACIER_IR",
            )

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
