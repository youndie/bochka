package io.github.youndie.bochka.app

import io.github.youndie.bochka.s3.sigv4.CanonicalRequest
import io.github.youndie.bochka.s3.sigv4.Sigv4
import java.net.Socket
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A `PUT` whose body is framed by nothing at all is `411 MissingContentLength` (M3).
 *
 * The defect this is about was found by somebody else's client and fixed a long time ago: a body
 * arriving without `Content-Length`, without `Transfer-Encoding: chunked` and without
 * `x-amz-decoded-content-length` was **stored**, so a truncated upload became a whole object with
 * nobody the wiser. What was missing is a test of our own: removing the refusal from `S3Handler`
 * leaves the entire gate green, and the only thing that notices is `ci/s3kn.sh` - a harness that
 * is not one of the nine checks a pull request runs.
 *
 * Written to a socket by hand because no HTTP client will send it: the JDK's picks a framing from
 * the publisher and there is no way to ask it for neither. That is the same reason
 * [MalformedUriTest] is written this way, and the same argument - nothing well-behaved sends this,
 * which is exactly why nothing had asked what happens when something does.
 *
 * Both halves are here on purpose. A refusal on its own would also be produced by a server that
 * refuses every `PUT`, so the accepted twin - the same request with a length - stands beside it.
 */
class MissingLengthTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    /**
     * Signs and writes a `PUT` whose body is framed the way [statedLength] says, or not framed.
     *
     * `UNSIGNED-PAYLOAD` keeps the signature independent of the body, which is what lets the same
     * signed head carry a body that no header describes.
     */
    private fun put(
        path: String,
        body: ByteArray,
        statedLength: Boolean,
    ): Pair<Int, String> {
        val timestamp = S3Fixture.signingTimestamp()
        val host = "127.0.0.1:${s3.port}"
        val hash = "UNSIGNED-PAYLOAD"
        val signed =
            listOf(
                "host" to host,
                "x-amz-content-sha256" to hash,
                "x-amz-date" to timestamp,
            )
        val names = signed.map { it.first }.sorted()
        val canonical =
            CanonicalRequest.build(
                CanonicalRequest.Request("PUT", path, "", signed),
                names,
                hash,
                CanonicalRequest.PathMode.VERBATIM,
            )
        val scope = "${timestamp.take(8)}/${S3Fixture.REGION}/s3/aws4_request"
        val signature =
            Sigv4.signature(
                Sigv4.signingKey(S3Fixture.SECRET, timestamp.take(8), S3Fixture.REGION, "s3"),
                Sigv4.stringToSign(timestamp, scope, canonical),
            )

        val head =
            buildString {
                append("PUT ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("x-amz-content-sha256: ").append(hash).append("\r\n")
                append("x-amz-date: ").append(timestamp).append("\r\n")
                append("Authorization: ").append(Sigv4.ALGORITHM)
                append(" Credential=").append(S3Fixture.ACCESS_KEY).append('/').append(scope)
                append(", SignedHeaders=").append(names.joinToString(";"))
                append(", Signature=").append(signature).append("\r\n")
                if (statedLength) append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }

        return Socket("127.0.0.1", s3.port).use { socket ->
            socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
            val answer = String(socket.getInputStream().readBytes(), Charsets.ISO_8859_1)
            answer.substringAfter(' ').substringBefore(' ').toInt() to answer
        }
    }

    @Test
    fun `a put whose body is framed by nothing is refused`() {
        s3.createBucket("photos")

        val (status, answer) = put("/photos/unframed.txt", "hello".toByteArray(), statedLength = false)

        assertEquals(411, status, answer)
        assertContains(answer, "MissingContentLength")
        // And nothing was stored under that key: a refusal that saved the body would be the very
        // defect, wearing the right status code.
        assertEquals(404, s3.send("HEAD", "/photos/unframed.txt").status)
    }

    @Test
    fun `the same put with a length is stored`() {
        s3.createBucket("photos")

        val (status, answer) = put("/photos/framed.txt", "hello".toByteArray(), statedLength = true)

        assertEquals(200, status, answer)
        assertEquals("hello", s3.get("photos", "framed.txt").text)
    }
}
