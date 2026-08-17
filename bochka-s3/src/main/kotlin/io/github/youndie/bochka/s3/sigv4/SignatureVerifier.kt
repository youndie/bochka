package io.github.youndie.bochka.s3.sigv4

import io.github.youndie.bochka.s3.UriCodec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Decides whether a request is signed by somebody we know, before its body is read.
 *
 * "Before its body is read" is the whole shape of this class and not an optimisation: a client
 * sends `Expect: 100-continue` on any file-like body and waits a second for an answer
 * (`botocore/handlers.py:395`), so a refusal has to be possible from the headers alone. A verifier
 * that needed the payload would make every `403` cost the upload it is refusing.
 *
 * Two ways in, both handled here because they differ in four small places and nowhere else: the
 * `Authorization` header, and a presigned URL where the same fields travel in the query.
 */
class SignatureVerifier(
    private val credentials: Credentials,
    private val region: String = "us-east-1",
    private val clock: Clock = Clock.systemUTC(),
    private val skew: Duration = MAX_SKEW,
) {
    sealed interface Result {
        /**
         * [payloadHash] is what the canonical request was built with — the literal value of
         * `x-amz-content-sha256`. The body path reads it to learn which of the four framings to
         * expect (research, §1.1), so it travels on rather than being thrown away.
         */
        data class Ok(
            val accessKeyId: String,
            val payloadHash: String,
        ) : Result

        /**
         * [canonicalRequest] and [stringToSign] are filled in only for
         * [S3Error.SIGNATURE_DOES_NOT_MATCH], and they go into the response body on purpose.
         *
         * S3 does the same, and it is the difference between a client fixing a mismatch in a
         * minute and guessing for a day: with both canonical requests side by side the diff is one
         * line. There is nothing secret in them — every byte came from the caller's own request.
         */
        data class Failure(
            val error: S3Error,
            val detail: String,
            val canonicalRequest: String? = null,
            val stringToSign: String? = null,
        ) : Result
    }

    fun verify(request: CanonicalRequest.Request): Result {
        val query = QueryParams(request.query)
        return if (query["X-Amz-Algorithm"] != null) {
            verifyPresigned(request, query)
        } else {
            verifyHeader(request)
        }
    }

    private fun verifyHeader(request: CanonicalRequest.Request): Result {
        val header =
            request.header("authorization")
                ?: return Result.Failure(S3Error.AUTHORIZATION_HEADER_MALFORMED, "no Authorization header")

        val authorization =
            try {
                Authorization.parse(header)
            } catch (e: Authorization.Malformed) {
                return Result.Failure(S3Error.AUTHORIZATION_HEADER_MALFORMED, e.message ?: "malformed")
            }

        val timestamp =
            request.header("x-amz-date")
                ?: request.header("date")
                ?: return Result.Failure(S3Error.MISSING_DATE_HEADER, "neither x-amz-date nor date")

        // Absent means the empty-body hash for a signed request, and UNSIGNED-PAYLOAD for a
        // presigned one (`minio/minio`, `cmd/signature-v4-utils.go:99-118`). Defaulting rather than
        // refusing is what keeps older clients working.
        val payloadHash = request.header("x-amz-content-sha256") ?: Sigv4.EMPTY_PAYLOAD_SHA256

        return check(
            request,
            authorization,
            timestamp,
            payloadHash,
            CanonicalRequest.PathMode.VERBATIM,
            freshness = Freshness.SKEW_WINDOW,
        ) {
            CanonicalRequest.canonicalQuery(request.query)
        }
    }

    private fun verifyPresigned(
        request: CanonicalRequest.Request,
        query: QueryParams,
    ): Result {
        val credential =
            query["X-Amz-Credential"]
                ?: return Result.Failure(S3Error.AUTHORIZATION_QUERY_PARAMETERS_ERROR, "no X-Amz-Credential")
        val signedHeaders =
            query["X-Amz-SignedHeaders"]
                ?: return Result.Failure(S3Error.MISSING_SIGNED_HEADERS, "no X-Amz-SignedHeaders")
        val signature =
            query["X-Amz-Signature"]
                ?: return Result.Failure(S3Error.AUTHORIZATION_QUERY_PARAMETERS_ERROR, "no X-Amz-Signature")
        val timestamp =
            query["X-Amz-Date"]
                ?: return Result.Failure(S3Error.MISSING_DATE_HEADER, "no X-Amz-Date")

        val authorization =
            try {
                // The query carries the same three components the header does, only apart, so they
                // are put back together and parsed once rather than twice.
                Authorization.parse(
                    "${Sigv4.ALGORITHM} Credential=$credential, " +
                        "SignedHeaders=$signedHeaders, Signature=$signature",
                )
            } catch (e: Authorization.Malformed) {
                return Result.Failure(S3Error.AUTHORIZATION_QUERY_PARAMETERS_ERROR, e.message ?: "malformed")
            }

        val expires =
            query["X-Amz-Expires"]?.toLongOrNull()
                ?: return Result.Failure(S3Error.MALFORMED_EXPIRES, "X-Amz-Expires is not a number")
        if (expires < 0 || expires > MAX_PRESIGN_TTL_SECONDS) {
            return Result.Failure(S3Error.MALFORMED_EXPIRES, "X-Amz-Expires is $expires")
        }

        val signedAt =
            parseTimestamp(timestamp)
                ?: return Result.Failure(S3Error.MALFORMED_DATE, "X-Amz-Date is '$timestamp'")
        if (clock.instant().isAfter(signedAt.plusSeconds(expires))) {
            return Result.Failure(S3Error.EXPIRED_PRESIGN_REQUEST, "expired at ${signedAt.plusSeconds(expires)}")
        }

        return check(
            request,
            authorization,
            timestamp,
            query["X-Amz-Content-Sha256"] ?: UNSIGNED_PAYLOAD,
            CanonicalRequest.PathMode.VERBATIM,
            freshness = Freshness.NOT_FROM_THE_FUTURE,
        ) {
            // The signature is the one field the signature cannot cover, so it comes out before the
            // canonical query is built (`docs/spec/reference/botocore-auth.py:787`).
            CanonicalRequest.canonicalQuery(query.without("X-Amz-Signature"))
        }
    }

    /**
     * How old a signature is allowed to be — and the two modes are genuinely different, which cost
     * a bug worth keeping written down.
     *
     * A header signature is replayable for as long as it verifies, so it is bounded by a window on
     * both sides. A presigned URL is *meant* to be used later — up to seven days later — so the
     * same window applied to it refuses every link older than fifteen minutes, and does it in a way
     * that looks to the user exactly like an expired link. The reference server checks only the
     * future side for presigned requests (`minio/minio`, `cmd/signature-v4.go:238-245`), and the
     * past side is what `X-Amz-Expires` is for.
     */
    private enum class Freshness {
        SKEW_WINDOW,
        NOT_FROM_THE_FUTURE,
    }

    private inline fun check(
        request: CanonicalRequest.Request,
        authorization: Authorization,
        timestamp: String,
        payloadHash: String,
        mode: CanonicalRequest.PathMode,
        freshness: Freshness,
        canonicalQuery: () -> String,
    ): Result {
        if (authorization.service != "s3") {
            return Result.Failure(S3Error.AUTHORIZATION_HEADER_MALFORMED, "service is '${authorization.service}'")
        }
        if (authorization.region != region) {
            return Result.Failure(S3Error.AUTHORIZATION_HEADER_MALFORMED, "region is '${authorization.region}'")
        }

        val signedAt =
            parseTimestamp(timestamp)
                ?: return Result.Failure(S3Error.MALFORMED_DATE, "date is '$timestamp'")
        if (!timestamp.startsWith(authorization.date)) {
            return Result.Failure(
                S3Error.AUTHORIZATION_HEADER_MALFORMED,
                "scope date ${authorization.date} does not match $timestamp",
            )
        }

        val drift = Duration.between(signedAt, clock.instant())
        val tooSkewed =
            when (freshness) {
                Freshness.SKEW_WINDOW -> drift.abs() > skew
                Freshness.NOT_FROM_THE_FUTURE -> drift.negated() > skew
            }
        if (tooSkewed) {
            return Result.Failure(S3Error.REQUEST_TIME_TOO_SKEWED, "off by ${drift.toSeconds()}s")
        }

        // Every header the client says it signed has to be here. Missing one silently drops a line
        // out of the canonical request, and the only symptom would be a mismatch that names
        // nothing — so it is named here instead.
        val present = request.headers.map { it.first.lowercase() }.toHashSet()
        val missing = authorization.signedHeaders.filterNot { it in present }
        if (missing.isNotEmpty()) {
            return Result.Failure(S3Error.AUTHORIZATION_HEADER_MALFORMED, "signed but absent: $missing")
        }

        val secret =
            credentials.secretFor(authorization.accessKeyId)
                ?: return Result.Failure(S3Error.INVALID_ACCESS_KEY_ID, authorization.accessKeyId)

        val canonical =
            buildString {
                append(request.method).append('\n')
                append(CanonicalRequest.canonicalUri(request.path, mode)).append('\n')
                append(canonicalQuery()).append('\n')
                append(CanonicalRequest.canonicalHeaders(request.headers, authorization.signedHeaders)).append('\n')
                append(authorization.signedHeaders.joinToString(";")).append('\n')
                append(payloadHash)
            }
        val stringToSign = Sigv4.stringToSign(timestamp, authorization.scope, canonical)
        val expected =
            Sigv4.signature(
                Sigv4.signingKey(secret, authorization.date, authorization.region, authorization.service),
                stringToSign,
            )

        return if (Sigv4.signaturesMatch(expected, authorization.signature)) {
            Result.Ok(authorization.accessKeyId, payloadHash)
        } else {
            Result.Failure(S3Error.SIGNATURE_DOES_NOT_MATCH, "computed $expected", canonical, stringToSign)
        }
    }

    private fun parseTimestamp(value: String): Instant? =
        try {
            TIMESTAMP.parse(value, Instant::from)
        } catch (_: DateTimeParseException) {
            null
        }

    /** The query, read once: values are percent-decoded, order and repeats are irrelevant here. */
    private class QueryParams(
        private val raw: String,
    ) {
        private val values: Map<String, String> =
            raw
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { token ->
                    val eq = token.indexOf('=')
                    val name = if (eq < 0) token else token.substring(0, eq)
                    val value = if (eq < 0) "" else token.substring(eq + 1)
                    decode(name).lowercase(Locale.ROOT) to decode(value)
                }

        operator fun get(name: String): String? = values[name.lowercase(Locale.ROOT)]

        fun without(name: String): String =
            raw
                .split('&')
                .filter { it.isNotEmpty() && !it.startsWith("$name=", ignoreCase = true) }
                .joinToString("&")

        private fun decode(component: String): String =
            String(UriCodec.decode(component, plusIsSpace = true), Charsets.UTF_8)
    }

    companion object {
        /**
         * 15 minutes, the same window the reference server allows (`cmd/globals.go:98`). Wider
         * makes a stolen request replayable for longer; narrower starts refusing honest clients
         * whose clock is merely bad.
         */
        val MAX_SKEW: Duration = Duration.ofMinutes(15)

        /** Seven days (`smithy-typescript`, `MAX_PRESIGNED_TTL`). */
        const val MAX_PRESIGN_TTL_SECONDS: Long = 604_800

        const val UNSIGNED_PAYLOAD: String = "UNSIGNED-PAYLOAD"

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private fun CanonicalRequest.Request.header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second?.trim()
    }
}
