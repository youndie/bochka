package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.s3.sigv4.S3Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3ErrorResponseTest {
    @Test
    fun `an error carries its code, its status and the request id in both places`() {
        val rendered =
            S3ErrorResponse.render(
                S3Error.SIGNATURE_DOES_NOT_MATCH,
                resource = "/photos/a.txt",
                requestId = "0123456789ABCDEF",
            )
        val body = String(rendered.body)

        assertEquals(403, rendered.status)
        assertTrue(body.contains("<Code>SignatureDoesNotMatch</Code>"), body)
        assertTrue(body.contains("<RequestId>0123456789ABCDEF</RequestId>"), body)
        assertEquals("0123456789ABCDEF", rendered.headers.first { it.first == "x-amz-request-id" }.second)
        assertEquals("application/xml", rendered.headers.first { it.first == "Content-Type" }.second)
    }

    @Test
    fun `the status comes from the code and they are not all 400`() {
        // The codes are copied from the reference server precisely because clients branch on them,
        // and some retry. A NoSuchKey answered 400 would be retried by nobody; a SlowDown answered
        // 404 would be retried by everybody exactly once.
        assertEquals(403, S3ErrorResponse.render(S3Error.INVALID_ACCESS_KEY_ID, "/").status)
        assertEquals(403, S3ErrorResponse.render(S3Error.REQUEST_TIME_TOO_SKEWED, "/").status)
        assertEquals(400, S3ErrorResponse.render(S3Error.MALFORMED_DATE, "/").status)
        assertEquals(400, S3ErrorResponse.render(S3Error.INCOMPLETE_BODY, "/").status)
    }

    @Test
    fun `a key in the error goes out as bytes`() {
        val rendered =
            S3ErrorResponse.render(
                S3Error.INCOMPLETE_BODY,
                resource = "/photos",
                requestId = "R",
                key = ObjectKey.of("файл.txt"),
            )

        assertTrue(String(rendered.body, Charsets.UTF_8).contains("<Key>файл.txt</Key>"))
    }

    @Test
    fun `the detail replaces the message but never explains the refusal`() {
        val stock = String(S3ErrorResponse.render(S3Error.SIGNATURE_DOES_NOT_MATCH, "/", "R").body)
        assertTrue(stock.contains("Check your key and signing method"), stock)

        val detailed =
            String(S3ErrorResponse.render(S3Error.INCOMPLETE_BODY, "/", "R", detail = "body ended early").body)
        assertTrue(detailed.contains("<Message>body ended early</Message>"), detailed)
    }

    @Test
    fun `request ids do not repeat and do not count anything`() {
        val ids = (1..1000).map { S3ErrorResponse.newRequestId() }

        assertEquals(1000, ids.toSet().size, "ids must not collide")
        assertTrue(ids.all { it.length == 16 && it.all { c -> c in "0123456789ABCDEF" } })
        // A counter would tell anybody who asks twice how many requests the server has served.
        assertFalse(ids.zipWithNext().all { (a, b) -> a < b }, "ids must not be a sequence")
    }
}
