package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layer two of the access model, over a socket (M28).
 *
 * **This is the most dangerous change this project has made, and the tests are shaped by that
 * rather than by what the suite checks.** Until it, "no signature means 403" was one branch anybody
 * could read; now whether a stranger may read an object is a computation, and a mistake in a
 * computation of that kind is a hole rather than a difference of opinion with S3. `ceph/s3-tests`
 * insures the wrong side — it checks far more often that the allowed is allowed.
 *
 * So almost everything below is a refusal, and every one of them was watched red: the fastest way
 * to write a green suite here is to leave the door open and test that it opens.
 */
class AnonymousAccessTest {
    private fun open(body: (S3Fixture) -> Unit) = S3Fixture(anonymous = true).use(body)

    private fun publicBucketWithObject(
        s3: S3Fixture,
        acl: String,
        objectAcl: String? = null,
    ): String {
        val bucket = "photos"
        s3.createBucket(bucket, headers = listOf("x-amz-acl" to acl))
        s3.put(bucket, "a.txt", "содержимое", headers = objectAcl?.let { listOf("x-amz-acl" to it) } ?: emptyList())
        return bucket
    }

    @Test
    fun `with the switch off no acl opens anything`() {
        // The switch is the deployment's own answer, and it comes first: a store with no public
        // objects should never run this computation at all.
        S3Fixture().use { s3 ->
            val bucket = publicBucketWithObject(s3, acl = "public-read", objectAcl = "public-read")

            assertEquals(403, s3.unsigned("GET", "/$bucket/a.txt").status)
            assertEquals(403, s3.unsigned("GET", "/$bucket").status)
        }
    }

    @Test
    fun `a public-read object is readable and nothing else about it is`() {
        open { s3 ->
            val bucket = publicBucketWithObject(s3, acl = "private", objectAcl = "public-read")

            assertEquals(200, s3.unsigned("GET", "/$bucket/a.txt").status)
            assertEquals(200, s3.unsigned("HEAD", "/$bucket/a.txt").status)
            // The bucket is private, so its listing is not open even though an object in it is.
            assertEquals(403, s3.unsigned("GET", "/$bucket").status)
            // Writing over a readable object asks the **bucket**, and this one says nothing.
            assertEquals(403, s3.unsigned("PUT", "/$bucket/a.txt", body = "чужое".toByteArray()).status)
            // And the permissions themselves never open: `public-read` is not
            // "public-read-and-rewritable-permissions".
            assertEquals(403, s3.unsigned("GET", "/$bucket/a.txt", query = "acl").status)
            assertEquals(403, s3.unsigned("PUT", "/$bucket/a.txt", query = "acl").status)
        }
    }

    @Test
    fun `a private object stays private however public its bucket is`() {
        open { s3 ->
            val bucket = publicBucketWithObject(s3, acl = "public-read")

            assertEquals(200, s3.unsigned("GET", "/$bucket").status, "the listing is open")
            assertEquals(403, s3.unsigned("GET", "/$bucket/a.txt").status, "and the object is not")
        }
    }

    @Test
    fun `authenticated-read is not a synonym for public`() {
        // The trap of the six canned names: it reads like "anybody" until the word is noticed. It
        // means every **key**, and a request with no key is not one of them.
        open { s3 ->
            val bucket = publicBucketWithObject(s3, acl = "authenticated-read", objectAcl = "authenticated-read")

            assertEquals(403, s3.unsigned("GET", "/$bucket/a.txt").status)
            assertEquals(403, s3.unsigned("GET", "/$bucket").status)
        }
    }

    @Test
    fun `a bucket older than the model is not open to nobody`() {
        // The hole two correct rules would have made between them. "No recorded owner means no
        // model" was written when an unsigned request could not get past the signature at all;
        // without this, switching layer two on would have opened every bucket made before M27 to
        // the world — retroactively, and without a line in any log.
        open { s3 ->
            // Made the way M27 would find it: straight through the store, with no owner recorded,
            // which is what every bucket in a store older than that milestone looks like.
            val bucket = "legacy"
            s3.store.createBucket(bucket, owner = null)
            s3.put(bucket, "a.txt", "старое")

            assertEquals(403, s3.unsigned("GET", "/$bucket/a.txt").status)
            assertEquals(403, s3.unsigned("GET", "/$bucket").status)
            assertEquals(403, s3.unsigned("PUT", "/$bucket/b.txt", body = "новое".toByteArray()).status)
        }
    }

    @Test
    fun `a wrong signature is never anonymous`() {
        // The single most important assertion here. What may become anonymous is the **absence** of
        // credentials; credentials that failed are somebody's key or somebody's clock, and letting
        // them fall through to a public ACL would turn a typo into unauthenticated access.
        open { s3 ->
            val bucket = publicBucketWithObject(s3, acl = "public-read", objectAcl = "public-read")

            // Now, and of the right shape: a stale date would be refused as a clock difference
            // before the signature is ever compared, and the assertion below would then be about
            // skew rather than about the thing this test exists for.
            val now = S3Fixture.signingTimestamp()
            val wrong =
                s3.unsigned(
                    "GET",
                    "/$bucket/a.txt",
                    headers =
                        listOf(
                            "Authorization" to
                                "AWS4-HMAC-SHA256 Credential=${S3Fixture.ACCESS_KEY}/${now.take(8)}/us-east-1/s3/" +
                                "aws4_request, SignedHeaders=host;x-amz-date, Signature=" + "0".repeat(64),
                            "x-amz-date" to now,
                        ),
                )

            assertEquals(403, wrong.status, wrong.text)
            assertTrue("SignatureDoesNotMatch" in wrong.text, wrong.text)
            assertTrue("содержимое" !in wrong.text, "the object must not be in the body of a refusal")
        }
    }

    @Test
    fun `nobody may make a bucket, list buckets, or touch a bucket's settings`() {
        open { s3 ->
            val bucket = publicBucketWithObject(s3, acl = "public-read-write", objectAcl = "public-read")

            assertEquals(403, s3.unsigned("PUT", "/brandnew").status, "no acl can grant creating a bucket")
            // And the same request against a bucket that already exists, which is a different
            // branch: the one above is refused before any ACL is consulted, because a bucket that
            // is not there has no owner and therefore no model. Found by mutation — reverting the
            // branch that refuses this changed nothing until this line existed.
            assertEquals(403, s3.unsigned("PUT", "/$bucket").status, "nor re-creating one that is there")
            assertEquals(403, s3.unsigned("GET", "/").status, "listing buckets has no bucket to ask about")
            assertEquals(403, s3.unsigned("DELETE", "/$bucket").status)
            assertEquals(403, s3.unsigned("GET", "/$bucket", query = "acl").status)
            assertEquals(403, s3.unsigned("PUT", "/$bucket", query = "versioning").status)
            assertEquals(403, s3.unsigned("GET", "/$bucket", query = "tagging").status)
        }
    }

    @Test
    fun `a public-read-write bucket takes writes, and what lands is not ownerless`() {
        open { s3 ->
            val bucket = "photos"
            s3.createBucket(bucket, headers = listOf("x-amz-acl" to "public-read-write"))

            assertEquals(200, s3.unsigned("PUT", "/$bucket/from-nobody.txt", body = "принято".toByteArray()).status)

            // The object an unsigned request creates must not be ownerless: in this model that
            // means "no model applies", and a public-read-write bucket would turn every anonymous
            // write into an object nobody could ever close again.
            assertEquals(
                403,
                s3.unsigned("GET", "/$bucket/from-nobody.txt").status,
                "the write was allowed by the bucket; reading it back is the object's own question",
            )
            assertEquals(200, s3.get(bucket, "from-nobody.txt").status, "and the bucket's owner can read it")
            // The assertion that actually pins the owner, and it was missing: an ownerless object
            // is open to **every key**, not to nobody, so the anonymous refusal above passes just
            // as well without the rule. Another key is where the hole would show.
            assertEquals(
                403,
                s3.send("GET", "/$bucket/from-nobody.txt", asOther = true).status,
                "a stranger's key must not read what an unsigned request left here",
            )
        }
    }

    @Test
    fun `an expected-bucket-owner that is not the owner refuses, signed or not`() {
        // Found by layer two rather than written for it. This header was accepted and ignored, and
        // an unsigned request could not get far enough to notice; opening the door revealed that a
        // `public-read-write` bucket then answered a stranger who had **asked to be sure whose
        // bucket it was**. Accepting a permission and not enforcing it is the one thing this
        // repository refuses everywhere else (`ceph/s3-tests`: `test_expected_bucket_owner`).
        open { s3 ->
            val bucket = "photos"
            s3.createBucket(bucket, headers = listOf("x-amz-acl" to "public-read-write"))
            val wrong = listOf("x-amz-expected-bucket-owner" to "somebody-else")

            assertEquals(403, s3.unsigned("GET", "/$bucket", headers = wrong).status)
            assertEquals(
                403,
                s3.unsigned("PUT", "/$bucket/a.txt", headers = wrong, body = "чужое".toByteArray()).status,
            )

            // A signed request is no different: the header is about the bucket, not about who asks.
            assertEquals(403, s3.send("GET", "/$bucket", headers = wrong).status)

            // The owner's own id passes, which is what keeps this from breaking every client that
            // sends the header.
            val right = listOf("x-amz-expected-bucket-owner" to S3Fixture.ACCESS_KEY)
            assertEquals(200, s3.send("GET", "/$bucket", headers = right).status)
        }
    }
}
