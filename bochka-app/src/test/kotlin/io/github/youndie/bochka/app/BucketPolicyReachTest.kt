package io.github.youndie.bochka.app

import io.github.youndie.bochka.s3.S3Router
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `Deny` that names everything reaches every route (M-201б).
 *
 * The mapping from a route to a policy action is a table, and a table quietly stops covering
 * things. A route that maps to no action is invisible to policies — which reads as "this request
 * is not covered" and behaves as "this request is allowed", the worst pair of the two. So the
 * question here is blunt and the same for every route: with a `Deny` naming every action and both
 * the bucket and everything under it, does the route refuse?
 *
 * Kotlin nests block comments, so the document itself is not quoted here — a policy ARN ending in
 * a star inside a slash-star comment closes it early, which cost this file one compile.
 *
 * Asked as the **owner**, whose ACL allows everything: that way the only thing that can produce a
 * refusal is the policy, and a passing row cannot be the ACL answering by accident.
 *
 * The second half checks the list against the router's own sealed hierarchy, so a route added
 * later fails this test for being absent rather than passing by not being here — the same guard
 * `AnonymousReachTest` carries for layer two.
 */
class BucketPolicyReachTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private val denyEverything =
        """{"Version": "2012-10-17", "Statement": [{"Effect": "Deny", "Principal": "*", """ +
            """"Action": "s3:*", "Resource": ["arn:aws:s3:::vault", "arn:aws:s3:::vault/*"]}]}"""

    private val requests =
        listOf(
            Triple("PUT", "/vault", "") to S3Router.Route.CreateBucket::class,
            Triple("DELETE", "/vault", "") to S3Router.Route.DeleteBucket::class,
            Triple("HEAD", "/vault", "") to S3Router.Route.HeadBucket::class,
            Triple("GET", "/vault", "location") to S3Router.Route.GetBucketLocation::class,
            Triple("GET", "/vault", "list-type=2") to S3Router.Route.ListObjectsV2::class,
            Triple("GET", "/vault", "") to S3Router.Route.ListObjects::class,
            Triple("GET", "/vault", "uploads") to S3Router.Route.ListMultipartUploads::class,
            Triple("GET", "/vault", "versions") to S3Router.Route.ListObjectVersions::class,
            Triple("POST", "/vault", "delete") to S3Router.Route.DeleteObjects::class,
            Triple("PUT", "/vault/a.txt", "") to S3Router.Route.PutObject::class,
            Triple("GET", "/vault/a.txt", "") to S3Router.Route.GetObject::class,
            Triple("HEAD", "/vault/a.txt", "") to S3Router.Route.HeadObject::class,
            Triple("DELETE", "/vault/a.txt", "") to S3Router.Route.DeleteObject::class,
            Triple("GET", "/vault/a.txt", "attributes") to S3Router.Route.GetObjectAttributes::class,
            Triple("GET", "/vault/a.txt", "tagging") to S3Router.Route.ObjectTagging::class,
            Triple("GET", "/vault", "tagging") to S3Router.Route.BucketSubresource::class,
            // A second sub-resource beside `tagging`, because this table is keyed by route class
            // and one row would leave every other **name** unasked: a sub-resource that maps to no
            // policy action is exactly the invisible-and-therefore-allowed case above, and it is
            // reached by adding a name rather than a route (M-227).
            Triple("GET", "/vault", "publicAccessBlock") to S3Router.Route.BucketSubresource::class,
            Triple("DELETE", "/vault", "publicAccessBlock") to S3Router.Route.BucketSubresource::class,
            Triple("GET", "/vault", "object-lock") to S3Router.Route.ObjectLockSubresource::class,
            Triple("GET", "/vault/a.txt", "acl") to S3Router.Route.ObjectAcl::class,
            Triple("POST", "/vault/a.txt", "uploads") to S3Router.Route.CreateMultipartUpload::class,
            Triple("PUT", "/vault/a.txt", "partNumber=1&uploadId=u") to S3Router.Route.UploadPart::class,
            Triple("POST", "/vault/a.txt", "uploadId=u") to S3Router.Route.CompleteMultipartUpload::class,
            Triple("DELETE", "/vault/a.txt", "uploadId=u") to S3Router.Route.AbortMultipartUpload::class,
            Triple("GET", "/vault/a.txt", "uploadId=u") to S3Router.Route.ListParts::class,
        )

    /**
     * Routes no bucket policy can speak about, each for a reason that is not permission.
     *
     * `ListBuckets` names no bucket, so there is no policy to consult — the owner filter answers it
     * instead (M27). `Health` and `Preflight` are answered before the access model and carry no
     * credentials by design. `NotImplemented` is refused earlier still. `PostObject` reaches the
     * same `s3:PutObject` as `PutObject` but arrives with its signature inside the form.
     *
     * **`CopyObject` and `UploadPartCopy` are here for a reason that turned out to be wrong once
     * already.** The first version of this list said all three were "covered by the sibling that
     * shares their action" — true of what they write, and false of what they **read**: nothing
     * asked whether the caller could read the source, and a stranger could copy any object in the
     * store into a bucket of their own. `CopySourceAccessTest` is where that question is asked
     * now; these two stay out of the loop below only because routing them needs a source object,
     * not because a sibling answers for them. An exemption in a completeness guard is exactly
     * where a gap hides.
     */
    private val outsideThePolicy =
        setOf(
            S3Router.Route.ListBuckets::class,
            S3Router.Route.Health::class,
            S3Router.Route.Preflight::class,
            S3Router.Route.NotImplemented::class,
            S3Router.Route.PostObject::class,
            S3Router.Route.CopyObject::class,
            S3Router.Route.UploadPartCopy::class,
        )

    @Test
    fun `every route is refused while the policy denies everything`() {
        s3.createBucket("vault")
        s3.put("vault", "a.txt", "body")
        assertEquals(204, s3.send("PUT", "/vault", query = "policy", body = denyEverything.toByteArray()).status)

        for ((request, route) in requests) {
            val (method, path, query) = request
            val answer = s3.send(method, path, query = query)
            assertEquals(
                403,
                answer.status,
                "${route.simpleName}: $method $path?$query answered ${answer.status} ${answer.text}",
            )
        }
    }

    @Test
    fun `the owner keeps the handle that removes the document`() {
        s3.createBucket("vault")
        s3.send("PUT", "/vault", query = "policy", body = denyEverything.toByteArray())

        // Denied to everyone by the text, and reachable anyway — otherwise one bad statement locks
        // the bucket for good.
        assertEquals(200, s3.send("GET", "/vault", query = "policy").status)
        assertEquals(204, s3.send("PUT", "/vault", query = "policy", body = denyEverything.toByteArray()).status)
        assertEquals(204, s3.send("DELETE", "/vault", query = "policy").status)
    }

    @Test
    fun `and a second key does not keep it`() {
        s3.createBucket("vault")
        s3.send("PUT", "/vault", query = "policy", body = denyEverything.toByteArray())

        assertEquals(403, s3.send("GET", "/vault", query = "policy", asOther = true).status)
        assertEquals(403, s3.send("DELETE", "/vault", query = "policy", asOther = true).status)
    }

    @Test
    fun `the list covers every route the router can produce`() {
        val covered = requests.map { it.second }.toSet() + outsideThePolicy
        val all =
            S3Router.Route::class.java.permittedSubclasses
                .orEmpty()
                .map { it.kotlin }
                .toSet()

        val missing = all - covered
        assertTrue(missing.isEmpty(), "routes nobody asked about: ${missing.map { it.simpleName }}")
    }
}
