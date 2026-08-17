package io.github.youndie.bochka.s3.sigv4

/**
 * The errors the authentication path can answer with.
 *
 * Codes, messages and statuses are taken verbatim from the reference server
 * (`minio/minio`, `cmd/api-errors.go`, lines noted per entry) rather than invented, because a
 * client matches on the code and some of them retry on it. Getting `RequestTimeTooSkewed` wrong by
 * a word costs nothing; getting the code wrong turns a retryable failure into a fatal one.
 *
 * Note what is **not** here: a code for "your signature is wrong in this particular way". An
 * unauthenticated caller gets one answer for a malformed header regardless of which part was
 * malformed — the reason goes to the log, not on the wire.
 */
enum class S3Error(
    val code: String,
    val message: String,
    val status: Int,
) {
    /** `cmd/api-errors.go:594`. */
    INVALID_ACCESS_KEY_ID(
        "InvalidAccessKeyId",
        "The Access Key Id you provided does not exist in our records.",
        403,
    ),

    /** `:714`. */
    REQUEST_TIME_TOO_SKEWED(
        "RequestTimeTooSkewed",
        "The difference between the request time and the server's time is too large.",
        403,
    ),

    /** `:719`. */
    SIGNATURE_DOES_NOT_MATCH(
        "SignatureDoesNotMatch",
        "The request signature we calculated does not match the signature you provided. " +
            "Check your key and signing method.",
        403,
    ),

    /** `:749`. */
    AUTHORIZATION_HEADER_MALFORMED(
        "AuthorizationHeaderMalformed",
        "The authorization header is malformed.",
        400,
    ),

    /** `:799`. */
    AUTHORIZATION_QUERY_PARAMETERS_ERROR(
        "AuthorizationQueryParametersError",
        "Error parsing the X-Amz-Credential parameter; the Credential is mal-formed; " +
            "expecting \"<YOUR-AKID>/YYYYMMDD/REGION/SERVICE/aws4_request\".",
        400,
    ),

    /** `:804`. */
    MALFORMED_DATE(
        "MalformedDate",
        "Invalid date format header, expected to be in ISO8601, RFC1123 or RFC1123Z time format.",
        400,
    ),

    /** `:864` — and note the code is `AccessDenied`, not something about dates. */
    MISSING_DATE_HEADER(
        "AccessDenied",
        "AWS authentication requires a valid Date or x-amz-date header",
        400,
    ),

    /** `:874`. */
    EXPIRED_PRESIGN_REQUEST(
        "AccessDenied",
        "Request has expired",
        403,
    ),

    /** `:849`, `:854`, `:1588` — one code, three reasons; the reason travels in the message. */
    MALFORMED_EXPIRES(
        "AuthorizationQueryParametersError",
        "X-Amz-Expires must be a number, non-negative, and less than 604800 seconds",
        400,
    ),

    /** `:1288`. */
    CONTENT_SHA256_MISMATCH(
        "XAmzContentSHA256Mismatch",
        "The provided 'x-amz-content-sha256' header does not match what was computed.",
        400,
    ),

    /** `:844`. */
    MISSING_SIGNED_HEADERS(
        "InvalidArgument",
        "Signature header missing SignedHeaders field.",
        400,
    ),

    /** `:583` — what a torn or misframed body answers with. */
    INCOMPLETE_BODY(
        "IncompleteBody",
        "You did not provide the number of bytes specified by the Content-Length HTTP header.",
        400,
    ),

    /** `:549` — the checksum the client sent does not describe the bytes it sent. */
    BAD_DIGEST(
        "BadDigest",
        "The Content-Md5 you specified did not match what we received.",
        400,
    ),

    /** `:544` — the checksum header itself is unreadable, as opposed to disagreeing with the bytes. */
    INVALID_DIGEST(
        "InvalidDigest",
        "The Content-Md5 you specified is not valid.",
        400,
    ),

    /**
     * `:1293` — and the operation it exists for is `DeleteObjects`.
     *
     * A batch delete without a checksum of its own body is refused rather than executed: the body
     * is a list of things to destroy, and a bit flipped in transit deletes a different object with
     * no way to notice afterwards. Every S3 SDK sends one.
     */
    MISSING_CONTENT_MD5(
        "MissingContentMD5",
        "Missing required header for this request: Content-Md5.",
        400,
    ),

    /** `:664`. */
    INVALID_BUCKET_NAME(
        "InvalidBucketName",
        "The specified bucket is not valid.",
        400,
    ),

    /** `:539` — `416`, and the only place in the server that answers with that status. */
    INVALID_RANGE(
        "InvalidRange",
        "The requested range is not satisfiable",
        416,
    ),

    /** `:604` — a header that is present, understood, and holds something impossible. */
    INVALID_ARGUMENT(
        "InvalidArgument",
        "Invalid argument",
        400,
    ),

    /** `:1283` — the request contradicts itself, as opposed to naming something unknown. */
    INVALID_REQUEST(
        "InvalidRequest",
        "Invalid Request",
        400,
    ),

    /** `:889`. */
    METADATA_TOO_LARGE(
        "MetadataTooLarge",
        "Your metadata headers exceed the maximum allowed metadata size.",
        400,
    ),

    /** `:689`. */
    NO_SUCH_UPLOAD(
        "NoSuchUpload",
        "The specified multipart upload does not exist. The upload ID may be invalid, " +
            "or the upload may have been aborted or completed.",
        404,
    ),

    /** `:774`. */
    ENTITY_TOO_SMALL(
        "EntityTooSmall",
        "Your proposed upload is smaller than the minimum allowed object size.",
        400,
    ),

    /** `:784`. */
    INVALID_PART(
        "InvalidPart",
        "One or more of the specified parts could not be found. The part may not have been " +
            "uploaded, or the specified entity tag may not match the part's entity tag.",
        400,
    ),

    /** `:789`. */
    INVALID_PART_ORDER(
        "InvalidPartOrder",
        "The list of parts was not in ascending order. Parts list must be specified in " +
            "order by part number.",
        400,
    ),

    /** `:824` — a body this server could not read at all, as opposed to one it disagrees with. */
    MALFORMED_XML(
        "MalformedXML",
        "The XML you provided was not well-formed or did not validate against our published schema.",
        400,
    ),

    /** `:654`. */
    NO_SUCH_BUCKET(
        "NoSuchBucket",
        "The specified bucket does not exist",
        404,
    ),

    /** `:684`. */
    NO_SUCH_KEY(
        "NoSuchKey",
        "The specified key does not exist.",
        404,
    ),

    /** `:769`. */
    BUCKET_NOT_EMPTY(
        "BucketNotEmpty",
        "The bucket you tried to delete is not empty",
        409,
    ),

    /**
     * `:704`, and the code that keeps bochka honest about its own scope: a request for something
     * it does not have is refused by name rather than answered with an empty result, which would
     * be a lie shaped exactly like an answer.
     */
    NOT_IMPLEMENTED(
        "NotImplemented",
        "A header you provided implies functionality that is not implemented",
        501,
    ),

    /** `:908` — the code the reference server answers a key it cannot accept with. */
    KEY_TOO_LONG(
        "KeyTooLongError",
        "Your key is too long",
        400,
    ),

    /**
     * `411 Length Required`, and the code and text come from the model's own error table rather
     * than the reference server: "You must provide the Content-Length HTTP header."
     *
     * A `PUT` whose length is stated nowhere — no `Content-Length`, and no
     * `X-Amz-Decoded-Content-Length` for a streaming body — is refused rather than accepted at
     * whatever length happens to arrive. Found by pointing an independent client at the server:
     * its test for this expected 411 and got 200, which means a truncated upload would have been
     * stored as a complete object.
     */
    MISSING_CONTENT_LENGTH(
        "MissingContentLength",
        "You must provide the Content-Length HTTP header.",
        411,
    ),

    INVALID_URI(
        "InvalidURI",
        "Couldn't parse the specified URI.",
        400,
    ),

    /**
     * `507`, and the one entry here that is **not** from anybody's error table.
     *
     * The S3 model has no code for "this store will not manage more objects", because the service
     * it describes does not have that limit. bochka does and publishes it (Р1), so it needs an
     * answer, and the honest one is the HTTP status that means exactly this. `503` was the
     * alternative and is worse: it tells the client to retry a condition that will not change.
     */
    INSUFFICIENT_STORAGE(
        "InsufficientStorage",
        "This store is at its published ceiling on the number of objects it manages.",
        507,
    ),
}
