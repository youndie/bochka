package io.github.youndie.bochka.app

import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A percent escape that is not one gets `400 InvalidURI`, not `500 InternalError` (M-258).
 *
 * Found by fuzzing `S3Router.route`, in 54 inputs. The router throws nothing anywhere in its own
 * source, which is what made the property worth asserting — and `parseQuery` reaches
 * `UriCodec.decode`, which answers a truncated escape with `IllegalArgumentException`. Every other
 * caller of that codec is in the same position: the path, the object key, the copy source, and the
 * components the signature is rebuilt from.
 *
 * The connection no longer drops — `screen` has been wrapped since the milestone where a malformed
 * `x-amz-tagging` closed the socket with no bytes in it — so what was left is the status. `500`
 * says "this server is broken" about a request that will never succeed, and both `aws-cli` and
 * `boto3` retry a 5xx: a client with one bad byte in a URI spends five requests learning nothing.
 * `InvalidURI` is 400 in the error table and is already the answer for a key that fails its rules.
 *
 * **The requests are written to a socket by hand**, because no HTTP client will send them:
 * `URI.create` rejects a bare `%` before it reaches the wire. That is the point — nothing
 * well-behaved sends this, which is exactly why nothing had asked what happens when something does.
 */
class MalformedUriTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private fun raw(requestLine: String): Pair<Int, String> =
        Socket("127.0.0.1", s3.port).use { socket ->
            socket.getOutputStream().write(
                "$requestLine HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(
                    Charsets.ISO_8859_1,
                ),
            )
            socket.getOutputStream().flush()
            val answer = String(socket.getInputStream().readBytes(), Charsets.ISO_8859_1)
            val status = answer.substringAfter(' ').substringBefore(' ').toInt()
            status to answer
        }

    @Test
    fun `a truncated percent escape in the query is a bad request`() {
        val (status, answer) = raw("GET /?%")

        assertEquals(400, status, "a malformed URI is the client's mistake, and a 5xx invites a retry")
        assertContains(answer, "InvalidURI")
    }

    @Test
    fun `a percent escape that is not hex is a bad request`() {
        // The other half of the codec: the length is there, the digits are not. `hexDigit` and the
        // length check are separate refusals, and a test standing on only one of them leaves the
        // other answering 500.
        val (status, answer) = raw("GET /bucket?prefix=%zz")

        assertEquals(400, status)
        assertContains(answer, "InvalidURI")
    }

    @Test
    fun `a truncated percent escape in the path is a bad request`() {
        // Not the query. The path goes through the same codec by a different call, so a fix that
        // only wrapped `parseQuery` would leave this one at 500.
        val (status, answer) = raw("GET /bucket/key%")

        assertEquals(400, status)
        assertContains(answer, "InvalidURI")
    }

    @Test
    fun `a well-formed escape reaches the signature check instead`() {
        // The positive control, and it does more than show the refusal is not blanket: these
        // requests are all unsigned, so a well-formed one is refused at `403` by signature checking
        // — one step *after* the URI is read. Getting `400` here and `403` there is what says the
        // new refusal comes from parsing the URI rather than from refusing everything shaped oddly.
        val (status, _) = raw("GET /bucket/a%2Fb")

        assertEquals(403, status, "a readable URI gets as far as the signature, which is the next refusal")
    }
}
