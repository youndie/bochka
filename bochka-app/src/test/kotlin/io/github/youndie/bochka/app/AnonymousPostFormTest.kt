package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layer two on the one entrance that has its own signature: the POST form (M-225).
 *
 * The form is the operation whose authorisation lives **inside the body**, so `screen` cannot
 * decide it — it lets `PostObject` past untouched and the handler asks instead. Until this
 * milestone the handler asked one question, "is there a `policy` field", and answered `403` when
 * there was not. That was true while nothing anonymous could get anywhere; M28 made it a second
 * door with its own lock, fitted before the first one existed.
 *
 * **What replaces it is not the absence of a check.** A form with no policy is a request that
 * named nobody, and it goes through the same two gates every other unnamed request goes through:
 * the deployment's switch first, the bucket's ACL after. `public-read-write` is what lets it in,
 * exactly as it lets an unsigned `PUT` in, and every other bucket still refuses it.
 *
 * Four cases of `ceph/s3-tests` land here, and they are two different things wearing one name:
 * `test_post_object_anonymous_request:1948` and `test_post_object_tags_anonymous_request:12203`
 * say so in their names, while `test_post_object_set_success_code:2072` and
 * `test_post_object_set_invalid_success_code:2087` read as being about `success_action_status` —
 * which this server has answered correctly since M16. They fail for the same one reason as the
 * other two: no policy, no upload.
 *
 * Both configurations are here on purpose. As shipped the switch is off and all four are refused,
 * which is what `off-by-default` in `ci/s3-tests-scope.txt` claims — and a claim of that shape is
 * only worth the line if something flips the switch and watches the other answer arrive.
 */
class AnonymousPostFormTest {
    /** A bucket anybody may write into, which is the premise of all four suite cases. */
    private fun publicBucket(s3: S3Fixture): String {
        s3.createBucket("photos", headers = listOf("x-amz-acl" to "public-read-write"))
        return "photos"
    }

    @Test
    fun `a form with no policy at all uploads into a public-read-write bucket`() {
        // `test_post_object_anonymous_request:1948`: `key`, `acl`, `Content-Type`, `file`, and
        // nothing that names anybody. It wants `204` and the bytes readable afterwards.
        S3Fixture(anonymous = true).use { s3 ->
            val bucket = publicBucket(s3)

            val answer =
                s3.postForm(
                    bucket,
                    listOf("key" to "foo.txt", "acl" to "public-read", "Content-Type" to "text/plain"),
                    "bar".toByteArray(),
                )

            assertEquals(204, answer.status, answer.text)
            assertEquals("bar", s3.get(bucket, "foo.txt").text)
            assertEquals("text/plain", s3.get(bucket, "foo.txt").header("Content-Type"))
        }
    }

    @Test
    fun `and the same form as shipped is refused before the bucket is asked`() {
        // The `off-by-default` line, checked by flipping the switch. `BOCHKA_ANONYMOUS` is the
        // deployment's own answer and it comes first: the bucket is as public here as it is above.
        S3Fixture().use { s3 ->
            val bucket = publicBucket(s3)

            val answer =
                s3.postForm(
                    bucket,
                    listOf("key" to "foo.txt", "acl" to "public-read", "Content-Type" to "text/plain"),
                    "bar".toByteArray(),
                )

            assertEquals(403, answer.status, answer.text)
            assertTrue("AccessDenied" in answer.text, answer.text)
            assertEquals(404, s3.get(bucket, "foo.txt").status, "a refused form stored something")
        }
    }

    @Test
    fun `a form with no policy into a bucket that grants nothing is refused with the switch on`() {
        // The half the suite does not insure. `public-read-write` is what admits the form; take it
        // away and the answer is `403` again, with the switch in exactly the position that let the
        // upload above through. Without this the milestone would read "no policy means no check".
        S3Fixture(anonymous = true).use { s3 ->
            s3.createBucket("private-photos")

            val answer =
                s3.postForm(
                    "private-photos",
                    listOf("key" to "foo.txt", "Content-Type" to "text/plain"),
                    "bar".toByteArray(),
                )

            assertEquals(403, answer.status, answer.text)
            assertEquals(404, s3.get("private-photos", "foo.txt").status)
        }
    }

    @Test
    fun `a public-read bucket admits an anonymous reader and not an anonymous form`() {
        // `public-read` is about the data going out, never about the data coming in — the same
        // rule `AccessControl.allowsObjectWrite` states for an unsigned `PUT`. A form that got in
        // here would mean the two paths had drifted, which is the drift this milestone exists to
        // avoid.
        S3Fixture(anonymous = true).use { s3 ->
            s3.createBucket("gallery", headers = listOf("x-amz-acl" to "public-read"))
            s3.put("gallery", "a.txt", "содержимое", headers = listOf("x-amz-acl" to "public-read"))

            assertEquals(200, s3.unsigned("GET", "/gallery/a.txt").status, "the premise: reading is open")

            val answer = s3.postForm("gallery", listOf("key" to "foo.txt"), "bar".toByteArray())

            assertEquals(403, answer.status, answer.text)
            assertEquals(404, s3.get("gallery", "foo.txt").status)
        }
    }

    @Test
    fun `an anonymous form carries its tags as a document, and they are stored`() {
        // `test_post_object_tags_anonymous_request:12203`, and the half of it the task did not
        // predict: the field is called `tagging` and it holds a **`<Tagging>` document**, not the
        // `a=1&b=2` query form `x-amz-tagging` uses. The authenticated twin of this case
        // (`test_post_object_tags_authenticated_request:12234`) passes without any of it because
        // it never asks for the tags back — it checks the status code and the body and stops.
        S3Fixture(anonymous = true).use { s3 ->
            val bucket = publicBucket(s3)
            val document =
                "<Tagging><TagSet><Tag><Key>0</Key><Value>0</Value></Tag>" +
                    "<Tag><Key>1</Key><Value>1</Value></Tag></TagSet></Tagging>"

            val answer =
                s3.postForm(
                    bucket,
                    listOf(
                        "key" to "foo.txt",
                        "acl" to "public-read",
                        "Content-Type" to "text/plain",
                        "tagging" to document,
                    ),
                    "bar".toByteArray(),
                )

            assertEquals(204, answer.status, answer.text)
            assertEquals("bar", s3.get(bucket, "foo.txt").text)

            val tags = s3.send("GET", "/$bucket/foo.txt", query = "tagging")
            assertEquals(200, tags.status, tags.text)
            assertTrue("<Key>0</Key><Value>0</Value>" in tags.text, tags.text)
            assertTrue("<Key>1</Key><Value>1</Value>" in tags.text, tags.text)
        }
    }

    @Test
    fun `a tagging document a form cannot parse is a bad request, not a stored object`() {
        // Accepting a `tagging` field and dropping it would be the shape this repository refuses
        // everywhere: the client is told the tags arrived and finds out otherwise by reading them
        // back empty.
        S3Fixture(anonymous = true).use { s3 ->
            val bucket = publicBucket(s3)

            val answer =
                s3.postForm(
                    bucket,
                    listOf("key" to "foo.txt", "tagging" to "<Tagging><TagSet><Tag><Key>0</Key>"),
                    "bar".toByteArray(),
                )

            assertEquals(400, answer.status, answer.text)
            assertEquals(404, s3.get(bucket, "foo.txt").status)
        }
    }

    @Test
    fun `success_action_status 201 answers an anonymous form with a document naming the key`() {
        // `test_post_object_set_success_code:2072`. The mechanism is M16's and unchanged; what was
        // missing was the door in front of it.
        S3Fixture(anonymous = true).use { s3 ->
            val bucket = publicBucket(s3)

            val answer =
                s3.postForm(
                    bucket,
                    listOf(
                        "key" to "foo.txt",
                        "acl" to "public-read",
                        "success_action_status" to "201",
                        "Content-Type" to "text/plain",
                    ),
                    "bar".toByteArray(),
                )

            assertEquals(201, answer.status, answer.text)
            assertTrue("<Key>foo.txt</Key>" in answer.text, answer.text)
        }
    }

    @Test
    fun `a success_action_status this server does not answer with falls back to an empty 204`() {
        // `test_post_object_set_invalid_success_code:2087` asks for `404` on success and expects
        // `204` with an empty body — a status the client made up is not a status the server owes.
        S3Fixture(anonymous = true).use { s3 ->
            val bucket = publicBucket(s3)

            val answer =
                s3.postForm(
                    bucket,
                    listOf(
                        "key" to "foo.txt",
                        "acl" to "public-read",
                        "success_action_status" to "404",
                        "Content-Type" to "text/plain",
                    ),
                    "bar".toByteArray(),
                )

            assertEquals(204, answer.status, answer.text)
            assertEquals(0, answer.body.size, answer.text)
            assertEquals("bar", s3.get(bucket, "foo.txt").text)
        }
    }

    @Test
    fun `what an anonymous form creates belongs to the bucket's owner, not to nobody`() {
        // The hole two correct rules make when they meet. `owner == null` means "written before the
        // access model existed, so no model applies" — an object open to every key. A
        // `public-read-write` bucket would then turn each anonymous form into an object nobody can
        // close. The bucket's owner is who is accountable for what lands there.
        //
        // The proof is what **another key** sees: a private object with an owner is closed to it,
        // an object with no owner is not.
        S3Fixture(anonymous = true).use { s3 ->
            val bucket = publicBucket(s3)

            val answer =
                s3.postForm(bucket, listOf("key" to "foo.txt", "acl" to "private"), "bar".toByteArray())
            assertEquals(204, answer.status, answer.text)

            assertEquals(403, s3.send("GET", "/$bucket/foo.txt", asOther = true).status)
            assertEquals(200, s3.send("GET", "/$bucket/foo.txt").status, "the bucket's owner is the owner")
        }
    }

    @Test
    fun `a form with a signature but no policy is incomplete, not anonymous`() {
        // The symmetric half of `test_post_object_missing_signature:2455`, which the suite pins at
        // `400`: a policy with no signature is a form missing a part it declared. A signature over
        // a policy that is not there is the same mistake from the other side, and reading it as
        // "anonymous" would let a form drop its own constraints and be judged by the bucket alone.
        // The switch is **on** here, so the `400` cannot be layer two answering.
        S3Fixture(anonymous = true).use { s3 ->
            val bucket = publicBucket(s3)

            val answer =
                s3.postForm(
                    bucket,
                    listOf(
                        "key" to "foo.txt",
                        "AWSAccessKeyId" to S3Fixture.ACCESS_KEY,
                        "signature" to "bm90IGEgc2lnbmF0dXJl",
                    ),
                    "bar".toByteArray(),
                )

            assertEquals(400, answer.status, answer.text)
            assertTrue("MalformedPOSTRequest" in answer.text, answer.text)
            assertEquals(404, s3.get(bucket, "foo.txt").status)
        }
    }

    @Test
    fun `a signed form still answers to its policy when the switch is on`() {
        // The switch may only ever add, and this is the sentence that says so about this entrance:
        // a form that **did** sign a policy is still held to it with layer two open. Otherwise
        // "anonymous is allowed" would have quietly become "policies are advisory".
        S3Fixture(anonymous = true).use { s3 ->
            val bucket = publicBucket(s3)
            val policy =
                """{"expiration": "2099-01-01T00:00:00Z", "conditions": """ +
                    """[{"bucket": "photos"}, ["starts-with", "${'$'}key", "uploads/"]]}"""

            val answer =
                s3.postForm(bucket, s3.signedPolicy(policy) + listOf("key" to "foo.txt"), "bar".toByteArray())

            assertEquals(403, answer.status, answer.text)
            assertEquals(404, s3.get(bucket, "foo.txt").status)
        }
    }
}
