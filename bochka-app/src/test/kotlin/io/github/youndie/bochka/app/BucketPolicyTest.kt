package io.github.youndie.bochka.app

import io.github.youndie.bochka.s3.sigv4.KeyScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bucket policies: the document, and what the server refuses to store (M-201а).
 *
 * Shapes are `docs/spec/s3-service-2.json`: `PutBucketPolicy` (`:1257`), `GetBucketPolicy`
 * (`:626`), `DeleteBucketPolicy` (`:277`, and its `responseCode` is **204**, not 200). The
 * document's own grammar comes from the suite that has to read it back —
 * `s3tests/functional/policy.py` builds `{"Version", "Statement":[{"Action","Principal",
 * "Effect","Resource"}]}`, which is the whole of what a policy has to be here.
 *
 * **The document is stored verbatim, and that is a requirement rather than an economy.**
 * `test_set_get_del_bucket_policy` compares the string it sent with the string it got back:
 * a re-serialised document fails that comparison while being the same policy. So the bytes are
 * kept for answering and the parse is kept for deciding, and the two are never swapped.
 */
class BucketPolicyTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    /** What `make_json_policy` produces, whitespace and all. */
    private fun policyFor(
        bucket: String,
        action: String = "s3:ListBucket",
        effect: String = "Allow",
    ) = """{"Version": "2012-10-17", "Statement": [{"Action": "$action", "Principal": {"AWS": "*"}, """ +
        """"Effect": "$effect", "Resource": ["arn:aws:s3:::$bucket", "arn:aws:s3:::$bucket/*"]}]}"""

    @Test
    fun `a bucket with no policy still answers NoSuchBucketPolicy`() {
        s3.createBucket("photos")

        val answer = s3.send("GET", "/photos", query = "policy")

        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchBucketPolicy")
    }

    @Test
    fun `the document comes back byte for byte as it was sent`() {
        s3.createBucket("photos")
        val document = policyFor("photos")

        assertEquals(200, s3.send("PUT", "/photos", query = "policy", body = document.toByteArray()).status)

        val answer = s3.send("GET", "/photos", query = "policy")
        assertEquals(200, answer.status, answer.text)
        assertEquals(document, answer.text)
    }

    @Test
    fun `deleting a policy answers 204 and leaves the bucket without one`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())

        assertEquals(204, s3.send("DELETE", "/photos", query = "policy").status)

        val answer = s3.send("GET", "/photos", query = "policy")
        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchBucketPolicy")
    }

    @Test
    fun `only the owner sets a policy`() {
        s3.createBucket("photos")

        val answer =
            s3.send(
                "PUT",
                "/photos",
                query = "policy",
                body = policyFor("photos").toByteArray(),
                asOther = true,
            )

        assertEquals(403, answer.status, answer.text)
        assertContains(answer.text, "AccessDenied")
    }

    @Test
    fun `a document that is not JSON is refused rather than stored`() {
        s3.createBucket("photos")

        val answer = s3.send("PUT", "/photos", query = "policy", body = "not a policy".toByteArray())

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "MalformedPolicy")
    }

    // --- what the document then decides (M-201б) --------------------------------------------

    @Test
    fun `a policy lets a stranger list a bucket the acl keeps private`() {
        // `test_bucket_policy`: the whole point of layer three. Nothing in the ACL changed — the
        // bucket is private and owned by the first key — and the second key lists it anyway,
        // because a policy **grants**, which no layer before this one could do.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "body")
        assertEquals(403, s3.send("GET", "/photos", query = "list-type=2", asOther = true).status)

        s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())

        assertEquals(200, s3.send("GET", "/photos", query = "list-type=2", asOther = true).status)
    }

    @Test
    fun `granting one action does not grant its neighbours`() {
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "body")
        s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())

        // s3:ListBucket was granted; reading an object was not.
        assertEquals(403, s3.send("GET", "/photos/a.txt", asOther = true).status)
    }

    @Test
    fun `an explicit deny outranks the acl that would have allowed it`() {
        // The ACL that matters here is the **object's**: reading an object is decided by the
        // object, and a `public-read` bucket holding a private object still refuses (M27).
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "body", headers = listOf("x-amz-acl" to "public-read"))
        assertEquals(200, s3.send("GET", "/photos/a.txt", asOther = true).status)

        val deny =
            """{"Version": "2012-10-17", "Statement": [{"Effect": "Deny", "Principal": "*", """ +
                """"Action": "s3:GetObject", "Resource": "arn:aws:s3:::photos/*"}]}"""
        s3.send("PUT", "/photos", query = "policy", body = deny.toByteArray())

        val answer = s3.send("GET", "/photos/a.txt", asOther = true)
        assertEquals(403, answer.status, answer.text)
        assertContains(answer.text, "AccessDenied")
    }

    /**
     * Without this the first typo in a `Deny` bricks the bucket: there would be no request left
     * that could take the document away. S3 says the owner keeps these three handles whatever the
     * policy says (`s3-service-2.json:1257`).
     */
    @Test
    fun `the owner can still remove a policy that denies everything`() {
        s3.createBucket("photos")
        val denyAll =
            """{"Version": "2012-10-17", "Statement": [{"Effect": "Deny", "Principal": "*", """ +
                """"Action": "s3:*", "Resource": ["arn:aws:s3:::photos", "arn:aws:s3:::photos/*"]}]}"""
        s3.send("PUT", "/photos", query = "policy", body = denyAll.toByteArray())

        // Everything else is shut, including for the owner.
        assertEquals(403, s3.send("GET", "/photos", query = "list-type=2").status)

        assertEquals(200, s3.send("GET", "/photos", query = "policy").status)
        assertEquals(204, s3.send("DELETE", "/photos", query = "policy").status)
        assertEquals(200, s3.send("GET", "/photos", query = "list-type=2").status)
    }

    @Test
    fun `a policy does not widen a key past its own scope`() {
        // The order M27 wrote down survives layer three: the scope is a ceiling, and a document
        // works under it. A read-only key handed `s3:*` is still a read-only key.
        val open =
            """{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": "*", """ +
                """"Action": "s3:*", "Resource": ["arn:aws:s3:::photos", "arn:aws:s3:::photos/*"]}]}"""

        S3Fixture(scope = KeyScope(KeyScope.Mode.RO)).use { readOnly ->
            // Both go in behind the key's back, because this key can do neither — which is the
            // premise of the test rather than a shortcut.
            readOnly.store.createBucket("photos")
            readOnly.store.putBucketSubresource("photos", "policy", open.toByteArray())

            val answer = readOnly.send("PUT", "/photos/new.txt", body = "body".toByteArray())

            assertTrue(answer.status == 403, "a read-only key wrote: ${answer.status} ${answer.text}")
        }
    }

    /**
     * The rule this milestone is most likely to break: a policy accepted and not enforced reads
     * stricter than it is, and the client finds out by leak rather than by error (M-133).
     */
    @Test
    fun `an action this server does not enforce is refused by name`() {
        s3.createBucket("photos")

        val answer =
            s3.send(
                "PUT",
                "/photos",
                query = "policy",
                body = policyFor("photos", action = "s3:Nonsense").toByteArray(),
            )

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "MalformedPolicy")
        assertContains(answer.text, "s3:Nonsense")
    }
}
