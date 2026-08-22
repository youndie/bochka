package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PublicAccessBlock`: four switches, and every one of them **does** something (M-227).
 *
 * The operations are `docs/spec/s3-service-2.json` — `PutPublicAccessBlock` (no `responseCode`, so
 * 200), `GetPublicAccessBlock`, `DeletePublicAccessBlock` (`"responseCode": 204`) — and what each
 * flag means is that file's documentation of `PublicAccessBlockConfiguration`. The cases are the
 * suite's, named beside the tests that pin them.
 *
 * **The tests that matter here are the ones that ask for a refusal.** A flag stored and not applied
 * is the failure this repository refuses everywhere (§3.6, §3.8) and it is invisible to a
 * round-trip test: `PutPublicAccessBlock` followed by `GetPublicAccessBlock` passes just as well
 * for a server that writes the document into the journal and never reads it again. So each flag has
 * a test that switches it on and requires the thing it names to stop working — and, for the two
 * that must not touch what is already stored, a test that requires the stored document to still
 * read back as it was.
 */
class PublicAccessBlockTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    /** What botocore puts on the wire for `PublicAccessBlockConfiguration=…`. */
    private fun configuration(
        blockPublicAcls: Boolean = false,
        ignorePublicAcls: Boolean = false,
        blockPublicPolicy: Boolean = false,
        restrictPublicBuckets: Boolean = false,
    ) = (
        """<?xml version="1.0" encoding="UTF-8"?><PublicAccessBlockConfiguration>""" +
            "<BlockPublicAcls>$blockPublicAcls</BlockPublicAcls>" +
            "<IgnorePublicAcls>$ignorePublicAcls</IgnorePublicAcls>" +
            "<BlockPublicPolicy>$blockPublicPolicy</BlockPublicPolicy>" +
            "<RestrictPublicBuckets>$restrictPublicBuckets</RestrictPublicBuckets>" +
            "</PublicAccessBlockConfiguration>"
    ).toByteArray()

    /** What `make_json_policy` produces; `principal` defaults to the star the suite sends. */
    private fun policyFor(
        bucket: String,
        action: String = "s3:GetObject",
        principal: String = """{"AWS": "*"}""",
        effect: String = "Allow",
    ) = """{"Version": "2012-10-17", "Statement": [{"Action": "$action", "Principal": $principal, """ +
        """"Effect": "$effect", "Resource": ["arn:aws:s3:::$bucket", "arn:aws:s3:::$bucket/*"]}]}"""

    private fun putBlock(
        bucket: String,
        document: ByteArray,
    ) = s3.send("PUT", "/$bucket", query = "publicAccessBlock", body = document)

    // --- the three operations -------------------------------------------------------------------

    @Test
    fun `the four settings come back from the bucket they were put on`() {
        // `test_put_public_block:14264`, and the 200 is the model's silence about `responseCode`.
        s3.createBucket("photos")

        assertEquals(200, putBlock("photos", configuration(true, true, true, false)).status)

        val answer = s3.send("GET", "/photos", query = "publicAccessBlock")
        assertEquals(200, answer.status, answer.text)
        assertContains(answer.text, "<BlockPublicAcls>true</BlockPublicAcls>")
        assertContains(answer.text, "<IgnorePublicAcls>true</IgnorePublicAcls>")
        assertContains(answer.text, "<BlockPublicPolicy>true</BlockPublicPolicy>")
        assertContains(answer.text, "<RestrictPublicBuckets>false</RestrictPublicBuckets>")
    }

    @Test
    fun `removing the configuration answers 204 and leaves the bucket without one`() {
        // `test_put_get_delete_public_block:14441`.
        s3.createBucket("photos")
        putBlock("photos", configuration(blockPublicAcls = true))

        assertEquals(204, s3.send("DELETE", "/photos", query = "publicAccessBlock").status)

        val answer = s3.send("GET", "/photos", query = "publicAccessBlock")
        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchPublicAccessBlockConfiguration")
    }

    @Test
    fun `removing a configuration that was never there is still 204`() {
        // `test_get_undefined_public_block:14219` opens with exactly this delete and asserts 204 —
        // the same shape as `DeleteBucketPolicy`: the request asks for a state, not for a change.
        s3.createBucket("photos")

        assertEquals(204, s3.send("DELETE", "/photos", query = "publicAccessBlock").status)

        val answer = s3.send("GET", "/photos", query = "publicAccessBlock")
        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchPublicAccessBlockConfiguration")
    }

    @Test
    fun `a document naming a setting this server does not apply is refused and stores nothing`() {
        s3.createBucket("photos")
        val document =
            """<PublicAccessBlockConfiguration><BlockPublicKeys>true</BlockPublicKeys>""" +
                """</PublicAccessBlockConfiguration>"""

        val answer = s3.send("PUT", "/photos", query = "publicAccessBlock", body = document.toByteArray())

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "MalformedXML")
        // A refusal that left a configuration behind is not a refusal.
        assertEquals(404, s3.send("GET", "/photos", query = "publicAccessBlock").status)
    }

    @Test
    fun `only the owner of the bucket sets it`() {
        s3.createBucket("photos")

        val answer =
            s3.send(
                "PUT",
                "/photos",
                query = "publicAccessBlock",
                body = configuration(blockPublicAcls = true),
                asOther = true,
            )

        assertEquals(403, answer.status, answer.text)
    }

    @Test
    fun `a policy denying the read of the configuration is obeyed even for the owner`() {
        // `test_get_public_block_deny_bucket_policy:14236`, and it is worth noticing which way it
        // runs: the caller is the **owner**, and the owner is refused. The escape hatch that keeps
        // an owner from locking themselves out covers `?policy` alone (§3.8) — this handle is not
        // one a bad `Deny` can brick, because `DeleteBucketPolicy` still removes the document.
        s3.createBucket("photos")
        putBlock("photos", configuration(true, true, true, false))
        assertEquals(200, s3.send("GET", "/photos", query = "publicAccessBlock").status)

        val deny = policyFor("photos", action = "s3:GetBucketPublicAccessBlock", principal = """"*"""", effect = "Deny")
        assertEquals(204, s3.send("PUT", "/photos", query = "policy", body = deny.toByteArray()).status)

        assertEquals(403, s3.send("GET", "/photos", query = "publicAccessBlock").status)
    }

    // --- BlockPublicAcls: refuse a public canned name as it arrives -------------------------------

    @Test
    fun `BlockPublicAcls refuses the three public canned names on the bucket acl`() {
        // `test_block_public_put_bucket_acls:14283`. `authenticated-read` is in the list because
        // `AuthenticatedUsers` is every account in the world, which S3 counts as public.
        s3.createBucket("photos")
        putBlock("photos", configuration(blockPublicAcls = true, blockPublicPolicy = true))

        for (acl in listOf("public-read", "public-read-write", "authenticated-read")) {
            val answer = s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to acl))
            assertEquals(403, answer.status, "$acl: ${answer.text}")
        }
        assertEquals(
            200,
            s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "private")).status,
        )
    }

    @Test
    fun `BlockPublicAcls refuses an object arriving with a public canned name`() {
        // `test_block_public_object_canned_acls:14312`, whose last line is the half that matters as
        // much: `private` still goes through, so the flag refuses public ACLs rather than ACLs.
        s3.createBucket("photos")
        putBlock("photos", configuration(blockPublicAcls = true))

        for ((key, acl) in listOf(
            "foo1" to "public-read",
            "foo2" to "public-read-write",
            "foo3" to "authenticated-read",
        )) {
            val answer = s3.put("photos", key, "", headers = listOf("x-amz-acl" to acl))
            assertEquals(403, answer.status, "$acl: ${answer.text}")
        }
        assertEquals(200, s3.put("photos", "foo4", "", headers = listOf("x-amz-acl" to "private")).status)
    }

    @Test
    fun `BlockPublicAcls refuses a form field too, where the acl is not in the head`() {
        // The POST form is the one operation authorised after its body arrives (§4.4), so its `acl`
        // never passes the screen where every other route's is judged. Without this the flag has a
        // door left open in the one place a browser uses.
        s3.createBucket("photos")
        putBlock("photos", configuration(blockPublicAcls = true))
        val policy =
            """{"expiration": "2099-01-01T00:00:00Z", "conditions": [{"bucket": "photos"}, """ +
                """["starts-with", "${'$'}key", ""], {"acl": "public-read"}]}"""

        val answer =
            s3.postForm(
                "photos",
                s3.signedPolicy(policy) + listOf("key" to "report.txt", "acl" to "public-read"),
                "body".toByteArray(),
            )

        assertEquals(403, answer.status, answer.text)
    }

    @Test
    fun `BlockPublicAcls refuses a public canned name from a request carrying no credentials`() {
        // The refusal stands in both branches of the screen, and it has to: with layer two on, an
        // unsigned `PUT` into a `public-read-write` bucket is a write like any other, and it can
        // name an ACL like any other. A guard written into the signed branch alone is the shape
        // this repository has already paid for — a permission enforced on one path and not on the
        // one nobody was looking at.
        S3Fixture(anonymous = true).use { open ->
            open.createBucket("photos")
            open.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read-write"))
            assertEquals(200, open.unsigned("PUT", "/photos/before", body = "body".toByteArray()).status)

            open.send(
                "PUT",
                "/photos",
                query = "publicAccessBlock",
                body = configuration(blockPublicAcls = true),
            )

            val answer =
                open.unsigned(
                    "PUT",
                    "/photos/after",
                    headers = listOf("x-amz-acl" to "public-read"),
                    body = "body".toByteArray(),
                )
            assertEquals(403, answer.status, answer.text)
        }
    }

    @Test
    fun `BlockPublicAcls leaves an acl that is already there alone`() {
        // "Enabling this setting doesn't affect existing policies or ACLs" — the model, on this
        // member. This is the line that separates it from `IgnorePublicAcls` next door: one refuses
        // arrivals, the other stops obeying what arrived.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read"))
        s3.put("photos", "a.txt", "body")

        putBlock("photos", configuration(blockPublicAcls = true))

        assertEquals(200, s3.send("GET", "/photos", query = "list-type=2", asOther = true).status)
    }

    // --- IgnorePublicAcls: stop obeying the acl that is already there -----------------------------

    @Test
    fun `IgnorePublicAcls shuts a public bucket and its public objects to another key`() {
        // `test_ignore_public_acls:14415`, and both halves of it: the bucket's own listing and an
        // object whose own canned name is public.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read"))
        s3.put("photos", "key1", "abcde", headers = listOf("x-amz-acl" to "public-read"))
        assertEquals(200, s3.send("GET", "/photos", query = "list-type=2", asOther = true).status)
        assertEquals(200, s3.send("GET", "/photos/key1", asOther = true).status)

        putBlock("photos", configuration(ignorePublicAcls = true))

        assertEquals(403, s3.send("GET", "/photos", query = "list-type=2", asOther = true).status)
        assertEquals(403, s3.send("GET", "/photos/key1", asOther = true).status)
    }

    @Test
    fun `IgnorePublicAcls does not rewrite the acl it stops obeying`() {
        // "Enabling this setting doesn't affect the persistence of any existing ACLs", so the
        // document still says `public-read` and nothing acts on it. A flag that quietly rewrote the
        // stored ACL would survive being switched off, which is not what a switch is.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read"))
        putBlock("photos", configuration(ignorePublicAcls = true))

        val acl = s3.send("GET", "/photos", query = "acl")
        assertEquals(200, acl.status, acl.text)
        assertTrue("AllUsers" in acl.text, acl.text)

        assertEquals(204, s3.send("DELETE", "/photos", query = "publicAccessBlock").status)
        assertEquals(200, s3.send("GET", "/photos", query = "list-type=2", asOther = true).status)
    }

    @Test
    fun `IgnorePublicAcls closes the form upload a public-read-write bucket used to accept`() {
        // The form decides its own authorisation after the body arrives (§4.4), from a resource it
        // builds itself — so the flag has to reach that construction too, and a test that only
        // walked the screen would not notice if it did not.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read-write"))
        val policy =
            """{"expiration": "2099-01-01T00:00:00Z", "conditions": [{"bucket": "photos"}, """ +
                """["starts-with", "${'$'}key", ""]]}"""
        val fields = s3.signedPolicy(policy, asOther = true) + listOf("key" to "report.txt")
        assertEquals(204, s3.postForm("photos", fields, "body".toByteArray()).status)

        putBlock("photos", configuration(ignorePublicAcls = true))

        val answer =
            s3.postForm(
                "photos",
                s3.signedPolicy(policy, asOther = true) + listOf("key" to "second.txt"),
                "body".toByteArray(),
            )
        assertEquals(403, answer.status, answer.text)
    }

    @Test
    fun `IgnorePublicAcls leaves the owner alone`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read"))
        s3.put("photos", "key1", "abcde")

        putBlock("photos", configuration(ignorePublicAcls = true))

        assertEquals("abcde", s3.get("photos", "key1").text)
    }

    // --- BlockPublicPolicy: refuse a document that grants to everybody -----------------------------

    @Test
    fun `BlockPublicPolicy refuses a policy naming a star principal`() {
        // `test_block_public_policy:14340`, read through `check_access_denied`, so 403 and not 400:
        // the document is well-formed, and what refuses it is a permission rather than its grammar.
        s3.createBucket("photos")
        putBlock("photos", configuration(blockPublicPolicy = true))

        val answer =
            s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())

        assertEquals(403, answer.status, answer.text)
        // And nothing was stored: a refusal that left the policy behind is not a refusal.
        assertEquals(404, s3.send("GET", "/photos", query = "policy").status)
    }

    @Test
    fun `BlockPublicPolicy takes a policy naming one account`() {
        // `test_block_public_policy_with_principal:14357`. The flag is about `*`, not about
        // policies, and a server that refused both would pass the case above and fail this one.
        s3.createBucket("photos")
        putBlock("photos", configuration(blockPublicPolicy = true))

        val document = policyFor("photos", principal = """{"AWS": "arn:aws:iam::s3tenant1:root"}""")

        assertEquals(204, s3.send("PUT", "/photos", query = "policy", body = document.toByteArray()).status)
    }

    /**
     * The behaviour that changed when two definitions of "public" became one, and that nothing
     * pinned until now.
     *
     * `BlockPublicPolicy` and `GetBucketPolicyStatus` were built in parallel and each wrote its own
     * rule. They agreed about `Allow` and about `*`, and disagreed here: the reporting one calls a
     * conditioned statement not public, the refusing one did not look at conditions at all. AWS
     * spells both with a single rule, and a server that reported one answer and enforced the other
     * would be describing a permission nobody has — so the stricter, reporting rule won, and
     * a document like this one is now **taken**.
     *
     * Which is the interesting direction: the flag became slightly more permissive, and no test in
     * either milestone noticed.
     */
    @Test
    fun `BlockPublicPolicy takes a star principal that a condition narrows`() {
        s3.createBucket("photos")
        putBlock("photos", configuration(blockPublicPolicy = true))
        val conditioned =
            """{"Version": "2012-10-17", "Statement": [{"Action": "s3:GetObject", """ +
                """"Principal": {"AWS": "*"}, "Effect": "Allow", "Resource": "arn:aws:s3:::photos/*", """ +
                """"Condition": {"StringLike": {"s3:prefix": "public/*"}}}]}"""

        assertEquals(204, s3.send("PUT", "/photos", query = "policy", body = conditioned.toByteArray()).status)

        // And the other end of the same rule agrees, which is the whole point of there being one.
        val status = s3.send("GET", "/photos", query = "policyStatus")
        assertContains(status.text, "<IsPublic>false</IsPublic>", message = status.text)
    }

    @Test
    fun `BlockPublicPolicy leaves a policy that is already there alone`() {
        // "Enabling this setting doesn't affect existing bucket policies" — the model. Taking the
        // public policy away here would be `RestrictPublicBuckets`, which is a different switch.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "body")
        s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())

        putBlock("photos", configuration(blockPublicPolicy = true))

        assertEquals(200, s3.send("GET", "/photos/a.txt", asOther = true).status)
    }

    // --- RestrictPublicBuckets: stop obeying the policy that is already there ----------------------

    @Test
    fun `RestrictPublicBuckets takes a public policy away from another key and leaves the owner`() {
        // `test_block_public_restrict_public_buckets:14375` walks this with an unauthenticated
        // reader, which needs layer two switched on; the same rule is visible to a second key with
        // the server as it ships, and that is what this asks.
        s3.createBucket("photos")
        s3.put("photos", "foo", "bar")
        s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())
        assertEquals("bar", s3.send("GET", "/photos/foo", asOther = true).text)

        putBlock("photos", configuration(restrictPublicBuckets = true))

        assertEquals(403, s3.send("GET", "/photos/foo", asOther = true).status)
        assertEquals("bar", s3.get("photos", "foo").text)
    }

    @Test
    fun `RestrictPublicBuckets shuts the unauthenticated reader the policy had opened the bucket to`() {
        // The suite's own shape (`test_block_public_restrict_public_buckets:14375`): a public policy
        // is what lets an unsigned request read at all — layer two only opens the door, the policy
        // is what grants (§3.7, §3.8) — and this flag is what closes it again. Off by default here,
        // so the case is `off-by-default` on the shipped configuration and this fixture turns the
        // switch on rather than leaving the whole flag unproven.
        S3Fixture(anonymous = true).use { open ->
            open.createBucket("photos")
            open.put("photos", "foo", "bar")
            open.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())
            assertEquals("bar", open.unsigned("GET", "/photos/foo").text)

            assertEquals(
                200,
                open
                    .send(
                        "PUT",
                        "/photos",
                        query = "publicAccessBlock",
                        body = configuration(restrictPublicBuckets = true),
                    ).status,
            )

            assertEquals(403, open.unsigned("GET", "/photos/foo").status)
            assertEquals("bar", open.get("photos", "foo").text)
        }
    }

    @Test
    fun `RestrictPublicBuckets leaves a policy that names one account alone`() {
        // The flag restricts a **public** policy. A document granting to a named key is not one,
        // and taking it away would break the sharing the owner deliberately arranged.
        s3.createBucket("photos")
        s3.put("photos", "foo", "bar")
        val toTheOtherKey = policyFor("photos", principal = """{"AWS": "${S3Fixture.OTHER_ACCESS_KEY}"}""")
        s3.send("PUT", "/photos", query = "policy", body = toTheOtherKey.toByteArray())

        putBlock("photos", configuration(restrictPublicBuckets = true))

        assertEquals("bar", s3.send("GET", "/photos/foo", asOther = true).text)
    }

    @Test
    fun `RestrictPublicBuckets leaves the owner what the policy gave them over somebody else's object`() {
        // Where the owner's exemption is **visible**, and it took a surviving mutation to find:
        // the test above proves nothing about it, because the owner reads their own object through
        // the ACL and would do so with the exemption deleted. The one door that is the policy's and
        // only the policy's is an object in the owner's bucket written by **another key** — private,
        // and therefore closed to the bucket's owner by §3.6's "reading an object is governed by the
        // object". That is what "authorized users within this account" buys, and nothing else here
        // does.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read-write"))
        assertEquals(200, s3.send("PUT", "/photos/theirs", body = "bar".toByteArray(), asOther = true).status)
        assertEquals(403, s3.get("photos", "theirs").status)
        s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())
        assertEquals("bar", s3.get("photos", "theirs").text)

        putBlock("photos", configuration(restrictPublicBuckets = true))

        assertEquals("bar", s3.get("photos", "theirs").text)
    }

    @Test
    fun `RestrictPublicBuckets leaves the logging service its permission`() {
        // The model names the exemption: the flag restricts a public bucket "to only Amazon Web
        // Services service principals and authorized users within this account". The one service
        // principal here is the delivery of an access log (M-202), and it is not a caller — a `*`
        // principal never matched it in the first place, so taking its own statement away would be
        // this flag punishing the only party it names as exempt.
        s3.createBucket("photos")
        s3.createBucket("logs")
        val document =
            """{"Version": "2012-10-17", "Statement": [""" +
                """{"Sid": "S3ServerAccessLogsPolicy", "Effect": "Allow", """ +
                """"Principal": {"Service": "logging.s3.amazonaws.com"}, "Action": ["s3:PutObject"], """ +
                """"Resource": "arn:aws:s3:::logs/log/", """ +
                """"Condition": {"ArnLike": {"aws:SourceArn": "arn:aws:s3:::photos"}}}, """ +
                """{"Action": "s3:GetObject", "Principal": {"AWS": "*"}, "Effect": "Allow", """ +
                """"Resource": ["arn:aws:s3:::logs/*"]}]}"""
        assertEquals(204, s3.send("PUT", "/logs", query = "policy", body = document.toByteArray()).status)
        putBlock("logs", configuration(restrictPublicBuckets = true))

        val status =
            "<BucketLoggingStatus><LoggingEnabled><TargetBucket>logs</TargetBucket>" +
                "<TargetPrefix>log/</TargetPrefix></LoggingEnabled></BucketLoggingStatus>"
        val answer = s3.send("PUT", "/photos", query = "logging", body = status.toByteArray())

        assertEquals(200, answer.status, answer.text)
    }

    @Test
    fun `RestrictPublicBuckets does not soften an explicit Deny`() {
        // The flag exists to stop a policy handing things out. A `Deny` hands nothing out, and
        // reading it through the same door would turn the strictest document into the loosest.
        s3.createBucket("photos")
        s3.put("photos", "foo", "bar")
        val document =
            """{"Version": "2012-10-17", "Statement": [""" +
                """{"Action": "s3:GetObject", "Principal": {"AWS": "*"}, "Effect": "Allow", """ +
                """"Resource": ["arn:aws:s3:::photos/*"]}, """ +
                """{"Action": "s3:GetObject", "Principal": {"AWS": "*"}, "Effect": "Deny", """ +
                """"Resource": ["arn:aws:s3:::photos/foo"]}]}"""
        s3.send("PUT", "/photos", query = "policy", body = document.toByteArray())

        putBlock("photos", configuration(restrictPublicBuckets = true))

        val answer = s3.send("GET", "/photos/foo", asOther = true)
        assertEquals(403, answer.status, answer.text)
        assertContains(answer.text, "a bucket policy denies it")
    }

    @Test
    fun `RestrictPublicBuckets closes the source of a copy as well as its own read`() {
        // A copy is two requests wearing one (§3.8), and the source travels in a header rather than
        // in the route — which is how a permission gets enforced on one of the two and not on the
        // other. The flag lives in the policy decision itself so that both ends see it.
        s3.createBucket("photos")
        // Owned by the second key, so that what refuses the copy can only be the read of the source.
        assertEquals(200, s3.send("PUT", "/mine", asOther = true).status)
        s3.put("photos", "foo", "bar")
        s3.send("PUT", "/photos", query = "policy", body = policyFor("photos").toByteArray())
        assertEquals(
            200,
            s3
                .send("PUT", "/mine/copied", headers = listOf("x-amz-copy-source" to "/photos/foo"), asOther = true)
                .status,
        )

        putBlock("photos", configuration(restrictPublicBuckets = true))

        val answer =
            s3.send(
                "PUT",
                "/mine/stolen",
                headers = listOf("x-amz-copy-source" to "/photos/foo"),
                asOther = true,
            )
        assertEquals(403, answer.status, answer.text)
    }
}
