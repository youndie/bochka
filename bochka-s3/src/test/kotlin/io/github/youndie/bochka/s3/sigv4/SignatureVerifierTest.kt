package io.github.youndie.bochka.s3.sigv4

import io.github.youndie.bochka.s3.UriCodec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verification end to end, on requests botocore actually signed.
 *
 * The signatures come from `docs/spec/s3-signing-vectors/`, so an accepting test cannot be passing
 * because both sides make the same mistake. The refusing tests are the other half and the more
 * important one: a verifier that computes a signature and forgets to compare it passes every
 * vector suite there is.
 */
class SignatureVerifierTest {
    private val specDir: Path =
        Path.of(
            System.getProperty("bochka.specDir")
                ?: error("bochka.specDir is not set; the build has to point tests at docs/spec"),
        )

    private val signedAt: Instant = Instant.parse("2015-08-30T12:36:00Z")
    private val credentials = Credentials.of(AKID to SECRET, "AKIDOTHER" to "another-secret")

    private fun verifierAt(instant: Instant = signedAt) =
        SignatureVerifier(credentials, clock = Clock.fixed(instant, ZoneOffset.UTC))

    /** `put-with-body`: a header-signed PUT with a real payload hash. */
    private fun headerSignedRequest(): CanonicalRequest.Request {
        val case = specDir.resolve("s3-signing-vectors/header/put-with-body")
        val input =
            Files.readAllLines(case.resolve("input")).filter { it.isNotBlank() }.associate {
                val eq = it.indexOf('=')
                it.substring(0, eq) to it.substring(eq + 1)
            }
        val payloadHash = Files.readString(case.resolve("sha256")).trim()
        val authorization = Files.readString(case.resolve("authz")).trim()

        return CanonicalRequest.Request(
            method = input.getValue("method"),
            path = "/" + UriCodec.encodePath(input.getValue("key").toByteArray()),
            query = input.getValue("query"),
            headers =
                listOf(
                    "Host" to "${input.getValue("bucket")}.s3.us-east-1.amazonaws.com",
                    "X-Amz-Content-Sha256" to payloadHash,
                    "X-Amz-Date" to TIMESTAMP,
                    "Authorization" to authorization,
                ),
        )
    }

    private fun presignedRequest(case: String): CanonicalRequest.Request {
        val url = Files.readString(specDir.resolve("s3-signing-vectors/presign/$case/url")).trim()
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/')
        val target = "/" + afterScheme.substringAfter('/')
        return CanonicalRequest.Request(
            method = if (case.startsWith("put")) "PUT" else "GET",
            path = target.substringBefore('?'),
            query = target.substringAfter('?', ""),
            headers = listOf("Host" to host),
        )
    }

    @Test
    fun `a request signed by a known key is accepted`() {
        val result = verifierAt().verify(headerSignedRequest())

        val ok = assertIs<SignatureVerifier.Result.Ok>(result, "expected acceptance, got $result")
        assertEquals(AKID, ok.accessKeyId)
        // The payload hash travels on: the body path needs it to know which framing to expect.
        assertEquals(64, ok.payloadHash.length)
    }

    @Test
    fun `an unknown access key is refused before anything is computed`() {
        val request = headerSignedRequest()
        val tampered =
            request.copy(
                headers =
                    request.headers.map { (name, value) ->
                        if (name == "Authorization") name to value.replace(AKID, "AKIDNOBODY") else name to value
                    },
            )

        val failure = assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(tampered))
        assertEquals(S3Error.INVALID_ACCESS_KEY_ID, failure.error)
        assertEquals(403, failure.error.status)
    }

    @Test
    fun `a changed path is refused and the answer carries our canonical request`() {
        val request = headerSignedRequest()
        val failure = assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(request.copy(path = "/other.txt")))

        assertEquals(S3Error.SIGNATURE_DOES_NOT_MATCH, failure.error)
        // M-19: without this a client has nothing to compare against and spends a day guessing.
        val canonical = assertNotNull(failure.canonicalRequest, "the canonical request must travel back")
        assertTrue(canonical.startsWith("PUT\n/other.txt\n"), canonical)
        val stringToSign = assertNotNull(failure.stringToSign)
        assertTrue(stringToSign.startsWith("AWS4-HMAC-SHA256\n$TIMESTAMP\n"), stringToSign)
    }

    @Test
    fun `a changed signed header is refused`() {
        val request = headerSignedRequest()
        val tampered =
            request.copy(
                headers = request.headers.map { if (it.first == "Host") "Host" to "elsewhere" else it },
            )

        val failure = assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(tampered))
        assertEquals(S3Error.SIGNATURE_DOES_NOT_MATCH, failure.error)
    }

    @Test
    fun `a header the client says it signed but did not send is named as such`() {
        // Not a signature mismatch: dropping the header would silently remove a line from the
        // canonical request, and "your signature is wrong" would be the least useful true answer.
        val request = headerSignedRequest()
        val tampered = request.copy(headers = request.headers.filterNot { it.first == "X-Amz-Content-Sha256" })

        val failure = assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(tampered))
        assertEquals(S3Error.AUTHORIZATION_HEADER_MALFORMED, failure.error)
        assertTrue(failure.detail.contains("x-amz-content-sha256"), failure.detail)
    }

    @Test
    fun `the clock window is fifteen minutes and it is symmetric`() {
        val request = headerSignedRequest()

        assertIs<SignatureVerifier.Result.Ok>(verifierAt(signedAt.plus(Duration.ofMinutes(14))).verify(request))
        assertIs<SignatureVerifier.Result.Ok>(verifierAt(signedAt.minus(Duration.ofMinutes(14))).verify(request))

        for (drift in listOf(Duration.ofMinutes(16), Duration.ofMinutes(-16))) {
            val failure = assertIs<SignatureVerifier.Result.Failure>(verifierAt(signedAt.plus(drift)).verify(request))
            assertEquals(S3Error.REQUEST_TIME_TOO_SKEWED, failure.error, "drift $drift")
        }
    }

    @Test
    fun `a missing or malformed date is refused before the signature is computed`() {
        val request = headerSignedRequest()

        val missing = request.copy(headers = request.headers.filterNot { it.first == "X-Amz-Date" })
        assertEquals(
            S3Error.MISSING_DATE_HEADER,
            assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(missing)).error,
        )

        val malformed =
            request.copy(
                headers =
                    request.headers.map {
                        if (it.first == "X-Amz-Date") it.first to "yesterday" else it
                    },
            )
        assertEquals(
            S3Error.MALFORMED_DATE,
            assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(malformed)).error,
        )
    }

    @Test
    fun `a scope naming another region or service is refused`() {
        val request = headerSignedRequest()

        val otherRegion =
            request.copy(
                headers =
                    request.headers.map { (n, v) ->
                        if (n == "Authorization") n to v.replace("/us-east-1/s3/", "/eu-west-1/s3/") else n to v
                    },
            )
        assertEquals(
            S3Error.AUTHORIZATION_HEADER_MALFORMED,
            assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(otherRegion)).error,
        )

        val otherService =
            request.copy(
                headers =
                    request.headers.map { (n, v) ->
                        if (n == "Authorization") n to v.replace("/us-east-1/s3/", "/us-east-1/sts/") else n to v
                    },
            )
        assertEquals(
            S3Error.AUTHORIZATION_HEADER_MALFORMED,
            assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(otherService)).error,
        )
    }

    @Test
    fun `a presigned url is accepted while it is fresh and refused once it is not`() {
        val request = presignedRequest("get-default-expiry")

        assertIs<SignatureVerifier.Result.Ok>(verifierAt().verify(request), "should be valid at signing time")
        assertIs<SignatureVerifier.Result.Ok>(
            verifierAt(signedAt.plusSeconds(3599)).verify(request),
            "should still be valid a second before it expires",
        )

        val expired = assertIs<SignatureVerifier.Result.Failure>(verifierAt(signedAt.plusSeconds(3601)).verify(request))
        assertEquals(S3Error.EXPIRED_PRESIGN_REQUEST, expired.error)
        assertEquals(403, expired.error.status)
    }

    /**
     * A presigned `PUT` that signs its payload hash in a **header**, which is what the AWS Go SDK
     * does and what mint's `PresignedPut` case exercises (M-300).
     *
     * Signed here rather than replayed from a vector because the vectors are all `GET`s: what
     * matters is that `x-amz-content-sha256` is named in `X-Amz-SignedHeaders`, so the value the
     * canonical request must use is the header's, not `UNSIGNED-PAYLOAD`.
     */
    private fun presignedWithSignedPayloadHash(payloadHash: String): CanonicalRequest.Request {
        val host = "photos.s3.us-east-1.amazonaws.com"
        val scope = "${TIMESTAMP.take(8)}/us-east-1/s3/aws4_request"
        val signedHeaders = "host;x-amz-content-sha256"
        val query =
            "X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Credential=" + UriCodec.encodeQueryComponent("$AKID/$scope".toByteArray()) +
                "&X-Amz-Date=$TIMESTAMP" +
                "&X-Amz-Expires=3600" +
                "&X-Amz-SignedHeaders=" + UriCodec.encodeQueryComponent(signedHeaders.toByteArray())
        val headers = listOf("Host" to host, "X-Amz-Content-Sha256" to payloadHash)
        val canonical =
            CanonicalRequest.build(
                CanonicalRequest.Request("PUT", "/report.txt", query, headers),
                signedHeaders.split(";"),
                payloadHash,
                CanonicalRequest.PathMode.VERBATIM,
            )
        val signature =
            Sigv4.signature(
                Sigv4.signingKey(SECRET, TIMESTAMP.take(8), "us-east-1", "s3"),
                Sigv4.stringToSign(TIMESTAMP, scope, canonical),
            )
        return CanonicalRequest.Request("PUT", "/report.txt", "$query&X-Amz-Signature=$signature", headers)
    }

    @Test
    fun `a presigned url that signs its payload hash in a header verifies`() {
        // Found by mint: the AWS Go SDK presigns a PUT this way, and this server answered
        // SignatureDoesNotMatch because it rebuilt the canonical request with UNSIGNED-PAYLOAD
        // while the client had signed the header's hash. The client is then told its credentials
        // are wrong, which sends whoever reads that message looking in the wrong place entirely.
        val payloadHash = Sigv4.sha256Hex("report".toByteArray())
        val result = verifierAt().verify(presignedWithSignedPayloadHash(payloadHash))

        val ok = assertIs<SignatureVerifier.Result.Ok>(result, "expected acceptance, got $result")
        // And the hash travels on, because the body path is what compares it to the bytes that
        // arrive — that comparison is the difference between the right error and the wrong one.
        assertEquals(payloadHash, ok.payloadHash)
    }

    @Test
    fun `a presigned url with an encoded key verifies`() {
        // The one that would break if the path were decoded and re-encoded anywhere on the way in.
        assertIs<SignatureVerifier.Result.Ok>(verifierAt().verify(presignedRequest("get-key-with-space")))
        assertIs<SignatureVerifier.Result.Ok>(verifierAt().verify(presignedRequest("get-key-with-non-ascii")))
        assertIs<SignatureVerifier.Result.Ok>(verifierAt().verify(presignedRequest("get-with-query")))
    }

    @Test
    fun `the seven day ceiling is enforced`() {
        // At the ceiling exactly it is a valid link (`put-max-expiry` is signed with 604800).
        assertIs<SignatureVerifier.Result.Ok>(verifierAt().verify(presignedRequest("put-max-expiry")))

        val tooLong =
            presignedRequest("put-max-expiry").let { request ->
                request.copy(query = request.query.replace("X-Amz-Expires=604800", "X-Amz-Expires=604801"))
            }
        val failure = assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(tooLong))
        assertEquals(S3Error.MALFORMED_EXPIRES, failure.error)
    }

    @Test
    fun `a presigned url with a changed signature is refused`() {
        val request = presignedRequest("get-default-expiry")
        val tampered =
            request.copy(query = request.query.dropLast(1) + if (request.query.last() == 'a') 'b' else 'a')

        val failure = assertIs<SignatureVerifier.Result.Failure>(verifierAt().verify(tampered))
        assertEquals(S3Error.SIGNATURE_DOES_NOT_MATCH, failure.error)
    }

    @Test
    fun `a signed header carrying a byte above 0x7f verifies`() {
        // M-77, and until now the fix had nothing holding it. The canonical request is a **byte**
        // string: a header value with a byte above 0x7F must be hashed as those bytes, and hashing
        // the same characters as UTF-8 doubles every one of them. The defect survived the 34
        // official vectors, both signing modes and four live clients for one reason - everything
        // they sign is ASCII, so both spellings agree.
        //
        // The expected signature is **not** computed by this repository. It comes from hmac and
        // hashlib in Python, run against the same canonical request:
        //
        //   canonical sha256 over the bytes: 3cf7d576fb8a3309b7c32d07937ea61e28055232f0c1f11741c4f884dad39cf1
        //   the same characters as UTF-8:    8a9fbc9871d26f72b3b959edf1a3a7bae8eeaa3b7b7f9ce151514c62a6a1d656
        //
        // Two different hashes, one signature; a verifier that picks the second one refuses this
        // request. The value is `café` in UTF-8, held here the way the parser hands it over -
        // one char per byte.
        val value = String("café".toByteArray(Charsets.UTF_8).map { (it.toInt() and 0xFF).toChar() }.toCharArray())
        val signature = "9455af9ceee4e610537744d574782a758fe0250225630d84faf2d61d524a9013"
        val scope = "20150830/us-east-1/s3/aws4_request"
        val request =
            CanonicalRequest.Request(
                method = "PUT",
                path = "/photos/a.txt",
                query = "",
                headers =
                    listOf(
                        "Host" to "example.com",
                        "X-Amz-Content-Sha256" to "UNSIGNED-PAYLOAD",
                        "X-Amz-Date" to TIMESTAMP,
                        "x-amz-meta-note" to value,
                        "Authorization" to
                            "AWS4-HMAC-SHA256 Credential=$AKID/$scope, " +
                            "SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-meta-note, " +
                            "Signature=$signature",
                    ),
            )

        assertIs<SignatureVerifier.Result.Ok>(verifierAt().verify(request))
    }

    private companion object {
        const val AKID = "AKIDEXAMPLE"
        const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
        const val TIMESTAMP = "20150830T123600Z"
    }
}
