package io.github.youndie.bochka.app

import io.github.youndie.bochka.s3.UriCodec
import io.github.youndie.bochka.s3.sigv4.CanonicalRequest
import io.github.youndie.bochka.s3.sigv4.Sigv4
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A presigned `PUT` that signs its payload hash in a header, and what happens when the body does
 * not match it (M-300).
 *
 * Found by `mint`: the AWS Go SDK presigns this way, and this server answered
 * `SignatureDoesNotMatch` to every such request — the signature was rebuilt with
 * `UNSIGNED-PAYLOAD` while the client had signed the header's hash. Two things were wrong with
 * that answer. It refused a request that was correctly signed, and when the body genuinely did not
 * match it named the credentials instead of the body, which sends whoever reads the message
 * looking in the wrong place.
 *
 * The requests are built by hand because no client here presigns: `S3Fixture` signs headers, and
 * the whole point of this case is the other way round.
 */
class PresignedPayloadHashTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private val client: HttpClient = HttpClient.newBuilder().build()

    private fun presignedPut(
        key: String,
        declaredHash: String,
        body: ByteArray,
    ): HttpResponse<String> {
        val timestamp = S3Fixture.signingTimestamp()
        val scope = "${timestamp.take(8)}/$REGION/s3/aws4_request"
        val signedHeaders = "host;x-amz-content-sha256"
        val host = "127.0.0.1:${s3.port}"
        val query =
            "X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Credential=" +
                UriCodec.encodeQueryComponent("${S3Fixture.ACCESS_KEY}/$scope".toByteArray()) +
                "&X-Amz-Date=$timestamp" +
                "&X-Amz-Expires=3600" +
                "&X-Amz-SignedHeaders=" + UriCodec.encodeQueryComponent(signedHeaders.toByteArray())
        val headers = listOf("host" to host, "x-amz-content-sha256" to declaredHash)
        val canonical =
            CanonicalRequest.build(
                CanonicalRequest.Request("PUT", "/photos/$key", query, headers),
                signedHeaders.split(";"),
                declaredHash,
                CanonicalRequest.PathMode.VERBATIM,
            )
        val signature =
            Sigv4.signature(
                Sigv4.signingKey(S3Fixture.SECRET, timestamp.take(8), REGION, "s3"),
                Sigv4.stringToSign(timestamp, scope, canonical),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("http://$host/photos/$key?$query&X-Amz-Signature=$signature"))
                .header("x-amz-content-sha256", declaredHash)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `a presigned put whose body matches its signed hash is accepted`() {
        s3.createBucket("photos")
        val body = "the report".toByteArray()

        val answer = presignedPut("report.txt", Sigv4.sha256Hex(body), body)

        assertEquals(200, answer.statusCode(), "a correctly signed presigned PUT was refused: ${answer.body()}")
        assertEquals(
            body.size.toLong(),
            s3.store
                .get(
                    "photos",
                    io.github.youndie.bochka.core.ObjectKey
                        .of("report.txt"),
                )?.size,
        )
    }

    @Test
    fun `a presigned put whose body does not match its signed hash names the body`() {
        s3.createBucket("photos")

        val answer =
            presignedPut("wrong.txt", Sigv4.sha256Hex("the report".toByteArray()), "something else".toByteArray())

        assertEquals(400, answer.statusCode(), "expected a refusal about the body: ${answer.body()}")
        assertContains(
            answer.body(),
            "XAmzContentSHA256Mismatch",
            message = "the refusal names something other than the body: ${answer.body()}",
        )
    }

    private companion object {
        const val REGION = "us-east-1"
    }
}
