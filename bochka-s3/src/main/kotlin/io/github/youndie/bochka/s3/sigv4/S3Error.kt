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

    /**
     * `:599` — a request with no credentials at all, which is a different thing from bad ones.
     *
     * Anonymous access to a private bucket is `403 AccessDenied`, not `400`: the request is
     * well-formed, it is just nobody's. Answering `400` told a client its request was broken and
     * that retrying with credentials would not help.
     */
    ACCESS_DENIED(
        "AccessDenied",
        "Access Denied.",
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

    /**
     * `:539` — `416`, the only status of its kind here.
     *
     * Answered from two places, and the second one is not a read: a `x-amz-copy-source-range` past
     * the end of its source is a well-formed range the object does not have, which is this and not
     * `InvalidArgument`. The difference is where it sends the client to look.
     */
    INVALID_RANGE(
        "InvalidRange",
        "The requested range is not satisfiable",
        416,
    ),

    /**
     * Набора тегов у бакета нет — и это **не** пустой набор.
     *
     * Клиент читает «нет настройки» и «настройка пустая» по-разному, и `test_set_bucket_tagging`
     * проверяет именно код. У объекта, к слову, ответ обратный: там пустой `TagSet` и `200`,
     * потому что объект-то есть.
     */
    NO_SUCH_TAG_SET(
        "NoSuchTagSet",
        "The TagSet does not exist",
        404,
    ),

    /** То же для CORS: конфигурации нет — это ответ, а не отсутствие ответа. */
    NO_SUCH_CORS_CONFIGURATION(
        "NoSuchCORSConfiguration",
        "The CORS configuration does not exist",
        404,
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

    /** Политика формы, которую нельзя разобрать: не base64, не JSON, неизвестное условие. */
    MALFORMED_POLICY_DOCUMENT(
        "MalformedPolicyDocument",
        "The policy document is not well-formed",
        400,
    ),

    /** Файл формы вышел за `content-length-range` политики. */
    ENTITY_TOO_LARGE(
        "EntityTooLarge",
        "Your proposed upload exceeds the maximum allowed object size",
        400,
    ),

    /**
     * `:824` — форма `multipart/form-data`, которую нельзя разобрать.
     *
     * Отдельно от `MalformedXML`, потому что это другой формат и клиент чинит другое место:
     * у формы разбирается тело браузерного запроса, а не документ, который клиент собрал сам.
     */
    MALFORMED_POST_REQUEST(
        "MalformedPOSTRequest",
        "The body of the POST request is not well-formed multipart/form-data",
        400,
    ),

    /**
     * Reading a delete marker by its version id.
     *
     * `405` and not `404`, because the version is there — it just holds no bytes. A `404` would
     * tell the client the version does not exist, and it would stop trying to delete the one thing
     * standing between it and its object.
     */
    METHOD_NOT_ALLOWED(
        "MethodNotAllowed",
        "The specified method is not allowed against this resource.",
        405,
    ),

    /**
     * The bucket is not in a state where this makes sense.
     *
     * Object lock is a property of creation: asking a bucket that was made without it to answer
     * about locks is not a malformed request, it is a request to a bucket that cannot have one
     * (`test_object_lock_put_obj_lock_invalid_bucket:13312`). `409`, because what is wrong is the
     * bucket rather than the document.
     */
    INVALID_BUCKET_STATE(
        "InvalidBucketState",
        "The request is not valid with the current state of the bucket.",
        409,
    ),

    /**
     * A bucket with no policy, and that is an **answer** rather than a missing feature.
     *
     * `NotImplemented` reads to a client as "this server is broken" and it leaves; `404` reads as
     * "there is no policy here", which is true and is what S3 says. The distinction has already
     * been paid for once — refusing `?versions` as unimplemented cost 837 cases of 838 in a
     * cleanup fixture (M3).
     */
    NO_SUCH_BUCKET_POLICY(
        "NoSuchBucketPolicy",
        "The bucket policy does not exist",
        404,
    ),

    /** Same shape, same reason: a bucket with no lifecycle rules has a defined answer. */
    NO_SUCH_LIFECYCLE_CONFIGURATION(
        "NoSuchLifecycleConfiguration",
        "The lifecycle configuration does not exist",
        404,
    ),

    /** A lock-enabled bucket that has no configuration yet — absent, not unimplemented. */
    OBJECT_LOCK_CONFIGURATION_NOT_FOUND(
        "ObjectLockConfigurationNotFoundError",
        "Object Lock configuration does not exist for this bucket",
        404,
    ),

    /** A retention period that parses and cannot be meant: zero days, negative years. */
    INVALID_RETENTION_PERIOD(
        "InvalidRetentionPeriod",
        "The retention period specified is invalid.",
        400,
    ),

    MALFORMED_XML(
        "MalformedXML",
        "The XML you provided was not well-formed or did not validate against our published schema.",
        400,
    ),

    /**
     * `:764` — a conditional read whose condition did not hold.
     *
     * It carries a body like every other error, and that is the part worth writing down: a `412`
     * answered with headers and nothing else made botocore fail inside its own response parser
     * rather than raise the `ClientError` the caller was waiting for. An error status without an
     * error document is not a smaller error, it is a different failure.
     */
    PRECONDITION_FAILED(
        "PreconditionFailed",
        "At least one of the preconditions you specified did not hold.",
        412,
    ),

    /** `:579` — this server has a bug, said in a way a client can act on. */
    INTERNAL_ERROR(
        "InternalError",
        "We encountered an internal error. Please try again.",
        500,
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
