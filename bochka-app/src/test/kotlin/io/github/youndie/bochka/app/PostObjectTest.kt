package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /<bucket>` — upload by an HTML form, end to end over a real socket (M-100…M-102).
 *
 * The shape of the request comes from `test_post_object_authenticated_request:1962`: fields
 * `key`, `acl`, `policy`, `signature`, `AWSAccessKeyId`, then `file` last. The signature is the
 * second version, which is what that suite sends and what a browser form has always sent.
 *
 * This is the only operation whose authorisation is decided after its body arrives, so the tests
 * that matter here are the refusals: an unsigned form, a form signed over a different policy, and
 * a form asking for something the server does not do.
 */
class PostObjectTest {
    private fun policy(
        expiration: String = "2099-01-01T00:00:00Z",
        conditions: String = """{"bucket": "photos"}, ["starts-with", "${'$'}key", ""], {"acl": "private"}""",
    ) = """{"expiration": "$expiration", "conditions": [$conditions]}"""

    @Test
    fun `a signed form stores the object and answers 204`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    s3.signedPolicy(policy()) + listOf("key" to "report.txt", "acl" to "private"),
                    "содержимое".toByteArray(),
                )

            // 204 and not 200: the browser that posted has nothing to render, and S3 says so.
            assertEquals(204, answer.status, answer.text)
            assertEquals("содержимое", s3.get("photos", "report.txt").text)
        }
    }

    @Test
    fun `success_action_status 201 answers with a document naming the object`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    s3.signedPolicy(
                        policy(
                            conditions =
                                """{"bucket": "photos"}, ["starts-with", "${'$'}key", ""], """ +
                                    """{"success_action_status": "201"}""",
                        ),
                    ) + listOf("key" to "report.txt", "success_action_status" to "201"),
                    "тело".toByteArray(),
                )

            assertEquals(201, answer.status, answer.text)
            assertTrue("<Key>report.txt</Key>" in answer.text, answer.text)
            assertTrue("<Bucket>photos</Bucket>" in answer.text, answer.text)
        }
    }

    @Test
    fun `success_action_redirect sends the browser on with 303`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    s3.signedPolicy(
                        policy(
                            conditions =
                                """{"bucket": "photos"}, ["starts-with", "${'$'}key", ""], """ +
                                    """["starts-with", "${'$'}success_action_redirect", "http://"]""",
                        ),
                    ) + listOf("key" to "report.txt", "success_action_redirect" to "http://example.test/done"),
                    "тело".toByteArray(),
                )

            assertEquals(303, answer.status, answer.text)
            val location = answer.header("Location")
            assertTrue(location!!.startsWith("http://example.test/done?"), location)
            assertTrue("key=report.txt" in location, location)
        }
    }

    @Test
    fun `the filename placeholder in the key is replaced by the uploaded name`() {
        // One signed policy, any file the user picks — which is the only reason `${'$'}{filename}`
        // exists.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    // `acl` is in the default policy, so the form has to carry it: a condition on a
                    // field the form omits is a refusal, not a condition that trivially holds.
                    s3.signedPolicy(policy()) + listOf("key" to "uploads/\${filename}", "acl" to "private"),
                    "тело".toByteArray(),
                    fileName = "снимок.txt",
                )
            assertEquals(204, answer.status, answer.text)

            assertEquals("тело", s3.get("photos", "uploads/%D1%81%D0%BD%D0%B8%D0%BC%D0%BE%D0%BA.txt").text)
        }
    }

    @Test
    fun `content-type from the form is stored, and read back on GET`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            s3.postForm(
                "photos",
                s3.signedPolicy(
                    policy(
                        conditions =
                            """{"bucket": "photos"}, ["starts-with", "${'$'}key", ""], """ +
                                """["starts-with", "${'$'}Content-Type", ""]""",
                    ),
                ) + listOf("key" to "page.html", "Content-Type" to "text/html"),
                "<b>привет</b>".toByteArray(),
            )

            assertEquals("text/html", s3.get("photos", "page.html").header("Content-Type"))
        }
    }

    @Test
    fun `a form with no signature is refused, and stores nothing`() {
        // `test_post_object_anonymous_request:1948` lands here. It wants a bucket open to everyone,
        // and this server has no such bucket — so the refusal is the honest answer, not a gap.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer = s3.postForm("photos", listOf("key" to "report.txt"), "тело".toByteArray())

            assertEquals(403, answer.status, answer.text)
            assertEquals(404, s3.get("photos", "report.txt").status)
        }
    }

    @Test
    fun `a signature over another policy is refused`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val signed = s3.signedPolicy(policy()).toMap().toMutableMap()
            // The policy is swapped after signing — which is exactly what an attacker who has seen
            // one form does.
            signed["policy"] =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(policy(conditions = """{"bucket": "elsewhere"}""").toByteArray())

            val answer =
                s3.postForm("photos", signed.toList() + listOf("key" to "report.txt"), "тело".toByteArray())

            assertEquals(403, answer.status, answer.text)
            assertTrue("SignatureDoesNotMatch" in answer.text, answer.text)
        }
    }

    @Test
    fun `a key the policy does not cover is refused`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    s3.signedPolicy(
                        policy(conditions = """{"bucket": "photos"}, ["starts-with", "${'$'}key", "uploads/"]"""),
                    ) + listOf("key" to "elsewhere/report.txt"),
                    "тело".toByteArray(),
                )

            assertEquals(403, answer.status, answer.text)
            assertEquals(404, s3.get("photos", "elsewhere/report.txt").status)
        }
    }

    @Test
    fun `a file outside content-length-range is EntityTooLarge`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    s3.signedPolicy(
                        policy(
                            conditions =
                                """{"bucket": "photos"}, ["starts-with", "${'$'}key", ""], """ +
                                    """["content-length-range", 0, 4]""",
                        ),
                    ) + listOf("key" to "report.txt"),
                    ByteArray(64) { 'a'.code.toByte() },
                )

            assertEquals(400, answer.status, answer.text)
            assertTrue("EntityTooLarge" in answer.text, answer.text)
        }
    }

    @Test
    fun `an acl this server does not honour is refused rather than accepted and ignored`() {
        // The rule that refuses `PutBucketAcl`: a client whose `public-read-write` was accepted
        // finds out it was not applied by a leak, not by an error.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    s3.signedPolicy(
                        policy(
                            conditions =
                                """{"bucket": "photos"}, ["starts-with", "${'$'}key", ""], """ +
                                    """{"acl": "public-read-write"}""",
                        ),
                    ) + listOf("key" to "report.txt", "acl" to "public-read-write"),
                    "тело".toByteArray(),
                )

            assertEquals(403, answer.status, answer.text)
            assertEquals(404, s3.get("photos", "report.txt").status)
        }
    }

    @Test
    fun `a body that is not multipart at all is a malformed POST, not a crash`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer = s3.send("POST", "/photos", body = "не форма".toByteArray())

            assertEquals(400, answer.status, answer.text)
            assertTrue("MalformedPOSTRequest" in answer.text, answer.text)
            assertNull(answer.header("x-amz-nothing"))
        }
    }

    @Test
    fun `a form with no key at all is malformed, not denied`() {
        // `test_post_object_no_key_specified:2448`. Nothing was refused — the form left out a part
        // it needs. This once answered `403`, because the key was injected into the fields before
        // the policy saw them and the policy then refused a field nobody had sent.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    s3.signedPolicy(policy(conditions = """{"bucket": "photos"}, {"acl": "private"}""")) +
                        listOf("acl" to "private"),
                    "тело".toByteArray(),
                )

            assertEquals(400, answer.status, answer.text)
            assertTrue("MalformedPOSTRequest" in answer.text, answer.text)
        }
    }

    @Test
    fun `the redirect keeps the quotes of the ETag`() {
        // `test_post_object_success_redirect_action:2321` compares the landing URL against
        // `etag=%22…%22`. The quotes are part of an S3 ETag, not punctuation around it.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.postForm(
                    "photos",
                    s3.signedPolicy(
                        policy(
                            conditions =
                                """{"bucket": "photos"}, ["starts-with", "${'$'}key", ""], """ +
                                    """["starts-with", "${'$'}success_action_redirect", "http://"]""",
                        ),
                    ) + listOf("key" to "report.txt", "success_action_redirect" to "http://example.test/done"),
                    "тело".toByteArray(),
                )

            val location = answer.header("Location")!!
            assertTrue("etag=%22" in location && location.endsWith("%22"), location)
        }
    }
}
