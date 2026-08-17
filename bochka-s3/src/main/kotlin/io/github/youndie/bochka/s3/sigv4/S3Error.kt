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
}
