package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.Credentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The signature of a POST form, both versions (M-102).
 *
 * The expectations were computed by an **independent implementation** — Python's `hmac`/`hashlib` —
 * rather than by this code: a signature compared against itself agrees whatever is wrong inside.
 * The string each was derived from is in the comment beside every constant.
 *
 * The second version is the form from `test_post_object_authenticated_request:1962`; the fourth is
 * what recent clients send.
 */
class PostSignatureTest {
    private val key = "AKIAIOSFODNN7EXAMPLE"
    private val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val credentials = Credentials(mapOf(key to secret))

    /** base64 of `{"expiration":"2026-08-18T13:00:00Z","conditions":[{"bucket":"photos"}]}`. */
    private val policy =
        "eyJleHBpcmF0aW9uIjoiMjAyNi0wOC0xOFQxMzowMDowMFoiLCJjb25kaXRpb25zIjpbeyJidWNrZXQiOiJwaG90b3MifV19"

    /** `base64(HMAC-SHA1(secret, policy))`. */
    private val v2 = "SHwUufBKiSrDbw4RRR9tn2I9n3c="

    /** `hex(HMAC-SHA256(signingKey(secret, 20260818, us-east-1, s3), policy))`. */
    private val v4 = "7d18ac78e4d9557cc95fb2db31f92640b5dbaeebd3eded31782083e93baaf7ed"

    private fun v2Fields(signature: String = v2) = mapOf("awsaccesskeyid" to key, "signature" to signature)

    private fun v4Fields(signature: String = v4) =
        mapOf(
            "x-amz-algorithm" to "AWS4-HMAC-SHA256",
            "x-amz-credential" to "$key/20260818/us-east-1/s3/aws4_request",
            "x-amz-date" to "20260818T120000Z",
            "x-amz-signature" to signature,
        )

    @Test
    fun `the v2 form signature verifies`() {
        assertEquals(key, PostSignature.verify(v2Fields(), policy, credentials, "us-east-1"))
    }

    @Test
    fun `the v4 form signature verifies`() {
        assertEquals(key, PostSignature.verify(v4Fields(), policy, credentials, "us-east-1"))
    }

    @Test
    fun `a v2 signature over a different policy is refused`() {
        val refused =
            assertFailsWith<PostSignature.Refused> {
                PostSignature.verify(v2Fields(), policy + "Xg==", credentials, "us-east-1")
            }
        assertEquals("SignatureDoesNotMatch", refused.error.code)
    }

    @Test
    fun `a v4 signature over a different policy is refused`() {
        assertFailsWith<PostSignature.Refused> {
            PostSignature.verify(v4Fields(), policy + "Xg==", credentials, "us-east-1")
        }
    }

    @Test
    fun `both forms present means the v4 one decides`() {
        // A form carrying both is a client that upgraded halfway. Taking the weaker one would let
        // anybody downgrade the check by adding a field.
        val refused =
            assertFailsWith<PostSignature.Refused> {
                PostSignature.verify(v2Fields() + v4Fields("00" * 32), policy, credentials, "us-east-1")
            }
        assertEquals("SignatureDoesNotMatch", refused.error.code)
    }

    @Test
    fun `an unknown access key is named as such, not as a bad signature`() {
        val refused =
            assertFailsWith<PostSignature.Refused> {
                PostSignature.verify(v2Fields().plus("awsaccesskeyid" to "NOBODY"), policy, credentials, "us-east-1")
            }
        assertEquals("InvalidAccessKeyId", refused.error.code)
    }

    @Test
    fun `a credential scope for another region is refused`() {
        assertFailsWith<PostSignature.Refused> {
            PostSignature.verify(v4Fields(), policy, credentials, "eu-central-1")
        }
    }

    @Test
    fun `a policy with no signature is malformed, not denied`() {
        // `test_post_object_missing_signature:2486`. Nothing was refused: the form declared a
        // policy and left out the part that vouches for it. The anonymous form — no policy either —
        // never reaches this code, and that one is `403`.
        val refused =
            assertFailsWith<PostSignature.Refused> {
                PostSignature.verify(mapOf("key" to "a.txt"), policy, credentials, "us-east-1")
            }
        assertEquals(400, refused.error.status)
        assertEquals("MalformedPOSTRequest", refused.error.code)
    }

    private operator fun String.times(count: Int) = repeat(count)
}
