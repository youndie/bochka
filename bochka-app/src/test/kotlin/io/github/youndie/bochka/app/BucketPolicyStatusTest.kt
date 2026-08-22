package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * `GET /<bucket>?policyStatus`: whether the bucket is public, and what public means (M-228).
 *
 * The operation is `s3-service-2.json:639`, its answer `GetBucketPolicyStatusOutput` (`:5537`)
 * whose payload is the `PolicyStatus` structure (`:10038`) holding one boolean member `IsPublic`
 * (`:7735`). Nothing in the model says what makes a bucket public — the documentation defers to a
 * page called "The Meaning of Public" — so the definition is taken from the six cases of the suite
 * that ask, and every test below names the one it comes from.
 *
 * **Two sources, not one.** The operation is named after the policy, and two of the six cases set
 * no policy at all: they put a canned ACL and require `true`. None of the six carries a marker, so
 * none of them describes RGW rather than S3 — the check that stopped a wrong change in M28.
 *
 * The one case that cannot pass here is `test_get_nonpublicpolicy_acl_bucket_policy_status:14135`,
 * and not because of this operation: its policy carries `IpAddress` over `aws:SourceIp`, both
 * refused by name since M-201в because this server cannot evaluate them (§3.8). Its **rule** —
 * a condition makes an otherwise public statement non-public — is enforced and tested here with a
 * condition the server does evaluate.
 */
class BucketPolicyStatusTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    /** `json.dumps` of what the suite builds, minus the parts each case varies. */
    private fun policyFor(
        bucket: String,
        principal: String = """{"AWS": "*"}""",
        effect: String = "Allow",
        condition: String = "",
    ) = """{"Version": "2012-10-17", "Statement": [{"Effect": "$effect", "Principal": $principal, """ +
        """"Action": "s3:ListBucket", "Resource": ["arn:aws:s3:::$bucket", """ +
        """"arn:aws:s3:::$bucket/*"]$condition}]}"""

    private fun statusOf(bucket: String): String {
        val answer = s3.send("GET", "/$bucket", query = "policyStatus")
        assertEquals(200, answer.status, answer.text)
        assertEquals("application/xml", answer.header("Content-Type"), answer.text)
        // The root element is `PolicyStatus`, not the operation name: the output shape
        // declares it as its own payload (`:5537`). It carries the namespace every document
        // here does.
        assertContains(
            answer.text,
            """<PolicyStatus xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""",
            message = "not a PolicyStatus document: ${answer.text}",
        )
        return answer.text
    }

    /**
     * Lowercase, and that is the whole of the assertion.
     *
     * `IsPublic` is a boolean shape, and botocore's XML parser reads a boolean as `text == 'true'`:
     * `TRUE` would reach the client as **false**, which is the answer a leaking bucket wants.
     */
    private fun assertPublic(
        bucket: String,
        expected: Boolean,
    ) = assertContains(statusOf(bucket), "<IsPublic>$expected</IsPublic>")

    @Test
    fun `a bucket nobody opened is not public`() {
        // test_get_bucket_policy_status:14086 — no ACL, no policy, and the answer is false rather
        // than a refusal: absence is an answer here.
        s3.createBucket("photos")

        assertPublic("photos", expected = false)
    }

    @Test
    fun `public-read is public, and it is an acl rather than a policy`() {
        // test_get_public_acl_bucket_policy_status:14092.
        s3.createBucket("photos")
        assertEquals(
            200,
            s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read")).status,
        )

        assertPublic("photos", expected = true)
    }

    @Test
    fun `authenticated-read is public too, though it names no anonymous caller`() {
        // test_get_authpublic_acl_bucket_policy_status:14099. It opens the data to every key in
        // the deployment and to nobody without one (§3.7) — and the suite still calls that public.
        s3.createBucket("photos")
        assertEquals(
            200,
            s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "authenticated-read")).status,
        )

        assertPublic("photos", expected = true)
    }

    @Test
    fun `public-read-write is public too, and no case says so`() {
        // Derived rather than pinned: it is `public-read` plus writing, so a rule that called it
        // private would say a wider door is a narrower one.
        s3.createBucket("photos")
        assertEquals(
            200,
            s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read-write")).status,
        )

        assertPublic("photos", expected = true)
    }

    @Test
    fun `private and the bucket-owner names are not public`() {
        // The other three canned names of M27. No case names them; they are here because a rule
        // that answers `true` for everything would pass the two above.
        for (canned in listOf("private", "bucket-owner-read", "bucket-owner-full-control")) {
            s3.createBucket("box-$canned")
            assertEquals(
                200,
                s3.send("PUT", "/box-$canned", query = "acl", headers = listOf("x-amz-acl" to canned)).status,
            )

            assertPublic("box-$canned", expected = false)
        }
    }

    @Test
    fun `a policy allowing a wildcard principal makes the bucket public`() {
        // test_get_publicpolicy_acl_bucket_policy_status:14107, including its first assertion:
        // the same bucket answers false before the document arrives.
        s3.createBucket("photos")
        assertPublic("photos", expected = false)

        assertEquals(
            204,
            s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray()).status,
        )

        assertPublic("photos", expected = true)
    }

    @Test
    fun `a policy naming one principal is not public`() {
        // test_get_nonpublicpolicy_principal_bucket_policy_status:14167. The suite writes
        // `arn:aws:iam::s3tenant1:root`, which this server reads as the access key `s3tenant1`
        // (BucketPolicy.principalOf) — a key, and a key is not everybody.
        s3.createBucket("photos")

        assertEquals(
            204,
            s3
                .send(
                    "PUT",
                    "/photos",
                    query = "policy",
                    body = policyFor("photos", principal = """{"AWS": "arn:aws:iam::s3tenant1:root"}""").toByteArray(),
                ).status,
        )

        assertPublic("photos", expected = false)
    }

    @Test
    fun `a condition makes an otherwise public statement not public`() {
        // The rule of test_get_nonpublicpolicy_acl_bucket_policy_status:14135, asked with a
        // condition this server can evaluate. The case's own is `IpAddress` over `aws:SourceIp`,
        // refused by name (§3.8), so the case stops at `put_bucket_policy` and never reaches here.
        s3.createBucket("photos")
        val narrowed = """, "Condition": {"StringEquals": {"s3:prefix": "public/"}}"""

        assertEquals(
            204,
            s3
                .send(
                    "PUT",
                    "/photos",
                    query = "policy",
                    body = policyFor("photos", condition = narrowed).toByteArray(),
                ).status,
        )

        assertPublic("photos", expected = false)
    }

    @Test
    fun `a Deny to everybody is not public`() {
        // **No case pins this one**, and that is said out loud rather than dressed up: it follows
        // from what the word means, and an error in it would be a false alarm rather than a hole.
        s3.createBucket("photos")

        assertEquals(
            204,
            s3
                .send("PUT", "/photos", query = "policy", body = policyFor("photos", effect = "Deny").toByteArray())
                .status,
        )

        assertPublic("photos", expected = false)
    }

    @Test
    fun `a statement that matches nothing cannot make a bucket public`() {
        // A `Statement` with no `Resource` is stored and inert (M-202, `test_bucket_logging_owner`),
        // and so is one whose `Action` list is empty: `BucketPolicy.evaluate` asks `any` of both,
        // and `any` of nothing is false. Inert means it grants nothing, so it cannot be what makes
        // a bucket public either — the two readings have to agree, or the report describes a
        // permission nobody has.
        //
        // Two shapes rather than one because a mutation run said so: with the check on `Action`
        // removed, every test here stayed green. A guard nothing breaks is a guard nobody tests.
        val inert =
            mapOf(
                "no-resource" to
                    """{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", """ +
                    """"Principal": {"AWS": "*"}, "Action": "s3:ListBucket"}]}""",
                "no-action" to
                    """{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", """ +
                    """"Principal": {"AWS": "*"}, "Action": [], "Resource": ["arn:aws:s3:::box-no-action"]}]}""",
            )

        for ((bucket, document) in inert) {
            s3.createBucket("box-$bucket")

            assertEquals(204, s3.send("PUT", "/box-$bucket", query = "policy", body = document.toByteArray()).status)

            assertPublic("box-$bucket", expected = false)
        }
    }

    @Test
    fun `removing the policy takes the publicity with it`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())
        assertPublic("photos", expected = true)

        assertEquals(204, s3.send("DELETE", "/photos", query = "policy").status)

        assertPublic("photos", expected = false)
    }

    @Test
    fun `there is no way to write a policy status`() {
        // S3 has `GetBucketPolicyStatus` and nothing else on `?policyStatus`, so a `PUT` there is
        // not a configuration this server declines to store — it is an operation that does not
        // exist. Before M-228 it was neither: `PUT /photos?policyStatus` created a bucket and
        // `GET` listed one, because the router did not know the name at all.
        s3.createBucket("photos")

        assertEquals(501, s3.send("PUT", "/photos", query = "policyStatus").status)
        assertEquals(501, s3.send("DELETE", "/photos", query = "policyStatus").status)
    }

    @Test
    fun `a second key is not told whether the bucket is public`() {
        // A bucket's own settings belong to whoever owns it (§3.6), and this one reports on the
        // settings. `public-read` opens the data and never the answer to "how open am I".
        s3.createBucket("photos", headers = listOf("x-amz-acl" to "public-read"))

        val answer = s3.send("GET", "/photos", query = "policyStatus", asOther = true)

        assertEquals(403, answer.status, answer.text)
        assertContains(answer.text, "AccessDenied")
    }
}
