package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner and the canned ACL over a real socket (M27).
 *
 * The matrix itself is [io.github.youndie.bochka.s3.AccessControlTest]; what is here is everything
 * that only exists on the wire — which status a refusal carries, what the ACL document says, and
 * which requests are refused **from the head**, before a body is read.
 *
 * Almost every assertion is a refusal, and that is the rule this milestone inherits from M19 and
 * M24: for permissions the positive checks prove nothing on their own, and the suite that scores
 * this server checks far more often that the allowed is allowed.
 */
class AccessModelTest {
    private fun S3Fixture.otherGet(
        bucket: String,
        key: String,
    ) = send("GET", "/$bucket/$key", asOther = true)

    @Test
    fun `a bucket belongs to the key that created it, and a stranger is refused`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "a.txt", "содержимое")

            assertEquals(403, s3.otherGet("photos", "a.txt").status)
            assertEquals(403, s3.send("GET", "/photos", asOther = true).status, "listing is refused too")
            assertEquals(403, s3.send("HEAD", "/photos", asOther = true).status)
            assertEquals(403, s3.send("PUT", "/photos/b.txt", body = "чужое".toByteArray(), asOther = true).status)
            // And the owner still has everything.
            assertEquals(200, s3.get("photos", "a.txt").status)
        }
    }

    @Test
    fun `public-read opens the object and never the write`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "a.txt", "содержимое", headers = listOf("x-amz-acl" to "public-read"))

            assertEquals(200, s3.otherGet("photos", "a.txt").status)
            assertEquals(
                403,
                s3.send("PUT", "/photos/a.txt", body = "поверх".toByteArray(), asOther = true).status,
                "a readable object in a private bucket is not writable",
            )
        }
    }

    @Test
    fun `writing an object asks the bucket and never the object`() {
        // `test_access_bucket_publicreadwrite_object_private`, which is the case that kills the
        // plausible model: the object stays private and is overwritten by a stranger anyway,
        // because the permission to write it belongs to the bucket.
        S3Fixture().use { s3 ->
            s3.createBucket("photos", headers = listOf("x-amz-acl" to "public-read-write"))
            s3.put("photos", "a.txt", "содержимое")

            assertEquals(403, s3.otherGet("photos", "a.txt").status, "the object is still private")
            assertEquals(200, s3.send("PUT", "/photos/a.txt", body = "поверх".toByteArray(), asOther = true).status)
            assertEquals("поверх", s3.otherGet("photos", "a.txt").text)

            // And the bucket's owner cannot read what the stranger just wrote into their bucket:
            // the new version belongs to whoever wrote it, and it is private. Surprising the first
            // time and exactly right — it is the reason `bucket-owner-full-control` exists at all.
            assertEquals(403, s3.get("photos", "a.txt").status)
        }
    }

    @Test
    fun `the bucket acl is answered with the owner and the grants it implies`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val private = s3.send("GET", "/photos", query = "acl")
            assertEquals(200, private.status)
            assertTrue("<ID>${S3Fixture.ACCESS_KEY}</ID>" in private.text, private.text)
            assertTrue("FULL_CONTROL" in private.text, private.text)
            assertTrue("AllUsers" !in private.text, "a private bucket grants nothing to anybody else")

            assertEquals(
                200,
                s3.send("PUT", "/photos", query = "acl", headers = listOf("x-amz-acl" to "public-read")).status,
            )

            val opened = s3.send("GET", "/photos", query = "acl")
            assertTrue("http://acs.amazonaws.com/groups/global/AllUsers" in opened.text, opened.text)
            assertTrue("xsi:type=\"Group\"" in opened.text, "the grantee type is an attribute, and clients read it")
            assertTrue("<Permission>READ</Permission>" in opened.text, opened.text)
            assertEquals(200, s3.send("GET", "/photos", asOther = true).status, "and it is enforced, not decorative")
        }
    }

    @Test
    fun `an object acl can be changed by its owner and by nobody else`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "a.txt", "содержимое")

            assertEquals(403, s3.otherGet("photos", "a.txt").status)
            assertEquals(
                403,
                s3
                    .send(
                        "PUT",
                        "/photos/a.txt",
                        query = "acl",
                        headers = listOf("x-amz-acl" to "public-read"),
                        asOther = true,
                    ).status,
                "a stranger cannot open an object by rewriting its ACL",
            )
            assertEquals(
                200,
                s3.send("PUT", "/photos/a.txt", query = "acl", headers = listOf("x-amz-acl" to "public-read")).status,
            )
            assertEquals(200, s3.otherGet("photos", "a.txt").status)
        }
    }

    @Test
    fun `a grant to a named user is refused by name rather than stored`() {
        // The rule the milestone is built on. A grant names a user; this server has access keys
        // and no user table, so accepting one means writing down a permission for somebody who
        // does not exist and never applying it.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val granted =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "acl",
                    headers = listOf("x-amz-grant-read" to "id=\"someone\""),
                )
            assertEquals(501, granted.status, granted.text)
            assertTrue("NotImplemented" in granted.text, granted.text)

            // A document of grants in the body is the same request in another shape.
            val document =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "acl",
                    body = "<AccessControlPolicy><AccessControlList/></AccessControlPolicy>".toByteArray(),
                )
            assertEquals(501, document.status, document.text)
        }
    }

    @Test
    fun `a canned name this server does not enforce is refused before the body`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val refused =
                s3.put("photos", "a.txt", "содержимое", headers = listOf("x-amz-acl" to "log-delivery-write"))

            assertEquals(400, refused.status, refused.text)
            assertEquals(404, s3.get("photos", "a.txt").status, "and nothing was stored")
        }
    }

    @Test
    fun `a bucket name taken by another key is a conflict rather than a share`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val taken = s3.send("PUT", "/photos", asOther = true)

            assertEquals(409, taken.status, taken.text)
            assertTrue("BucketAlreadyExists" in taken.text, taken.text)
            // Making your own bucket again stays the success every client relies on...
            assertEquals(200, s3.createBucket("photos").status)
            // ...until an ACL is in the request, which is a different ask about a bucket that may
            // already hold objects (`test_bucket_recreate_new_acl`).
            assertEquals(409, s3.send("PUT", "/photos", headers = listOf("x-amz-acl" to "public-read")).status)
        }
    }

    @Test
    fun `the key scope wins over the acl, in both directions`() {
        // M-196, and the whole reason the answer was written before the code: two models of
        // permissions, and only one of them may ever be the reason something is allowed.
        S3Fixture(
            scope =
                io.github.youndie.bochka.s3.sigv4
                    .KeyScope(io.github.youndie.bochka.s3.sigv4.KeyScope.Mode.RO),
        ).use { s3 ->
            // The read-only key cannot even make a bucket, so the other key makes it and opens
            // it as wide as a canned name can.
            assertEquals(200, s3.send("PUT", "/photos", asOther = true).status)
            assertEquals(
                200,
                s3
                    .send(
                        "PUT",
                        "/photos",
                        query = "acl",
                        headers = listOf("x-amz-acl" to "public-read-write"),
                        asOther = true,
                    ).status,
            )

            // `public-read-write` says anybody may write. The scope says this key may not, and
            // the scope is applied first — so the ACL cannot hand back what it took away.
            assertEquals(403, s3.put("photos", "a.txt", "содержимое").status)
            // Reading is what the scope left, and there the ACL decides — and allows, once the
            // object the other key wrote says so. Without that header the refusal would look like
            // the scope working when it is only the default `private` doing it.
            assertEquals(
                200,
                s3
                    .send(
                        "PUT",
                        "/photos/a.txt",
                        headers = listOf("x-amz-acl" to "public-read"),
                        body = "чужое".toByteArray(),
                        asOther = true,
                    ).status,
            )
            assertEquals(200, s3.get("photos", "a.txt").status)
        }
    }
}
