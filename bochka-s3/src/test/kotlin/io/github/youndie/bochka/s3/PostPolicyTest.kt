package io.github.youndie.bochka.s3

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The policy of a POST form (M-101), in the shape from
 * `test_post_object_authenticated_request:1970`.
 *
 * The document is written by hand here exactly as the suite writes it — same whitespace, same
 * order. Building it with a serialiser would check that two of our own representations agree,
 * instead of checking agreement with what arrives from a client.
 */
class PostPolicyTest {
    private fun encode(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())

    private val now = Instant.parse("2026-08-18T12:00:00Z")

    private val document =
        """
        {"expiration": "2026-08-18T13:00:00Z",
         "conditions": [
          {"bucket": "photos"},
          ["starts-with", "${'$'}key", "foo"],
          {"acl": "private"},
          ["starts-with", "${'$'}Content-Type", "text/plain"],
          ["content-length-range", 0, 1024]
         ]
        }
        """.trimIndent()

    private fun fields(vararg extra: Pair<String, String>) =
        mapOf(
            "bucket" to "photos",
            "key" to "foo.txt",
            "acl" to "private",
            "content-type" to "text/plain",
        ) + extra

    @Test
    fun `conditions of all four forms parse`() {
        val policy = PostPolicy.decode(encode(document))

        assertEquals(Instant.parse("2026-08-18T13:00:00Z"), policy.expiration)
        assertEquals(5, policy.conditions.size)
        assertEquals(PostPolicy.Condition.Exact("bucket", "photos"), policy.conditions[0])
        assertEquals(PostPolicy.Condition.StartsWith("key", "foo"), policy.conditions[1])
        assertEquals(PostPolicy.Condition.LengthRange(0, 1024), policy.conditions[4])
    }

    @Test
    fun `a form matching every condition passes`() {
        val policy = PostPolicy.decode(encode(document))

        PostPolicy.check(policy, fields(), fileLength = 3, now = now)
    }

    @Test
    fun `an expired policy is refused before anything else`() {
        val policy = PostPolicy.decode(encode(document))

        val refused =
            assertFailsWith<PostPolicy.Refused> {
                PostPolicy.check(policy, fields(), 3, Instant.parse("2026-08-18T14:00:00Z"))
            }
        assertEquals(403, refused.error.status)
    }

    @Test
    fun `a key that does not start with the allowed prefix is refused`() {
        val policy = PostPolicy.decode(encode(document))

        assertFailsWith<PostPolicy.Refused> {
            PostPolicy.check(policy, fields("key" to "bar.txt"), 3, now)
        }
    }

    @Test
    fun `a file outside the range is EntityTooLarge rather than a refusal of access`() {
        // Different codes because the client fixes different things: exceeding the length is about
        // its file, a refusal of access is about its signature.
        val policy = PostPolicy.decode(encode(document))

        val refused = assertFailsWith<PostPolicy.Refused> { PostPolicy.check(policy, fields(), 2000, now) }
        assertEquals("EntityTooLarge", refused.error.code)
    }

    @Test
    fun `a field the policy did not cover is refused`() {
        // The important assertion in this file. The signer allowed a particular set; a field beyond
        // it is something they did not allow, and letting it through means letting an uploader
        // attach anything at all to somebody else's signature.
        val policy = PostPolicy.decode(encode(document))

        assertFailsWith<PostPolicy.Refused> {
            PostPolicy.check(policy, fields("x-amz-meta-secret" to "подставлено"), 3, now)
        }
    }

    @Test
    fun `the signature and its companions need no condition`() {
        // Demanding a condition on `signature` would mean demanding that the client sign its own
        // signature.
        val policy = PostPolicy.decode(encode(document))

        PostPolicy.check(
            policy,
            fields("policy" to "…", "signature" to "…", "awsaccesskeyid" to "k", "x-ignore-extra" to "z"),
            3,
            now,
        )
    }

    @Test
    fun `a policy that is not base64 is refused as malformed rather than as denied`() {
        val refused = assertFailsWith<PostPolicy.Refused> { PostPolicy.decode("не base64!!") }
        assertEquals("MalformedPolicyDocument", refused.error.code)
    }

    @Test
    fun `a policy without an expiration is refused`() {
        // `test_post_object_missing_expires_condition:2814` expects a 400. A policy with no expiry
        // is an unlimited pass, and it must not be handed out silently to somebody who forgot to
        // set one.
        val refused =
            assertFailsWith<PostPolicy.Refused> {
                PostPolicy.decode(encode("""{"conditions": [{"bucket": "photos"}]}"""))
            }
        assertEquals("MalformedPolicyDocument", refused.error.code)
    }

    @Test
    fun `expiration is read case-sensitively`() {
        // `test_post_object_expires_is_case_sensitive:2654`. `EXPIRATION` is not an expiry but a
        // typo, and quietly turning it into "no expiry" means lifting the limit.
        assertFailsWith<PostPolicy.Refused> {
            PostPolicy.decode(encode("""{"EXPIRATION": "2099-01-01T00:00:00Z", "conditions": []}"""))
        }
    }

    @Test
    fun `a policy without conditions is refused`() {
        // `test_post_object_missing_conditions_list:2814`. An empty list of conditions would allow
        // anything to be put anywhere — that is not "a policy without limits", it is not a policy.
        assertFailsWith<PostPolicy.Refused> {
            PostPolicy.decode(encode("""{"expiration": "2099-01-01T00:00:00Z"}"""))
        }
    }

    @Test
    fun `JSON escaping is removed before comparison`() {
        // `test_post_object_escaped_field_values:2257`: the condition is signed on a prefix made of
        // a backslash and a dollar, and in the document the backslash is doubled. A byte-for-byte
        // comparison would demand an extra backslash from the client and refuse the very form the
        // policy allows.
        //
        // The strings here are assembled from characters rather than written as literals: the
        // dollar and the backslash are escaped in both Kotlin and JSON, and a literal that reads
        // correctly usually means something else.
        val prefix = "" + '\\' + '$' + "foo"
        val json =
            """{"expiration": "2099-01-01T00:00:00Z", "conditions": """ +
                """[["starts-with", "${'$'}key", "\\${'$'}foo"]]}"""

        val policy = PostPolicy.decode(encode(json))

        assertEquals(PostPolicy.Condition.StartsWith("key", prefix), policy.conditions[0])
        PostPolicy.check(policy, mapOf("key" to "$prefix.txt"), fileLength = 3, now = now)
    }
}
