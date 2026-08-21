package io.github.youndie.bochka.app

import io.github.youndie.bochka.s3.S3Router
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No route reaches a handler unsigned without the access model having said so (M-200).
 *
 * The lesson this exists for is M24's: **positive checks do not see an open lock.** Every other
 * test of layer two asks one route a careful question; this one asks every route the same blunt
 * one, against a bucket that grants nothing, and the answer must be a refusal each time. A route
 * added later that forgets the gate answers something else, and the shape of that failure is a
 * hole rather than an incompatibility.
 *
 * The second half is the part that keeps it honest over time: the table below is checked against
 * the router's own sealed hierarchy, so a new route makes this test fail for being **absent** from
 * the table rather than passing by not being in it. A list that quietly stops covering everything
 * is the same lie as a rule that cannot fire.
 */
class AnonymousReachTest {
    /** One request per route, aimed at a bucket whose ACL grants nothing to anybody. */
    private val requests =
        listOf(
            Triple("GET", "/", "") to S3Router.Route.ListBuckets::class,
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
            Triple("GET", "/vault", "acl") to S3Router.Route.BucketSubresource::class,
            Triple("GET", "/vault", "object-lock") to S3Router.Route.ObjectLockSubresource::class,
            Triple("GET", "/vault/a.txt", "acl") to S3Router.Route.ObjectAcl::class,
            Triple("POST", "/vault/a.txt", "uploads") to S3Router.Route.CreateMultipartUpload::class,
            Triple("PUT", "/vault/a.txt", "partNumber=1&uploadId=u") to S3Router.Route.UploadPart::class,
            Triple("POST", "/vault/a.txt", "uploadId=u") to S3Router.Route.CompleteMultipartUpload::class,
            Triple("DELETE", "/vault/a.txt", "uploadId=u") to S3Router.Route.AbortMultipartUpload::class,
            Triple("GET", "/vault/a.txt", "uploadId=u") to S3Router.Route.ListParts::class,
        )

    /**
     * Routes deliberately reachable without credentials, each for a reason that is not permission.
     *
     * `Preflight` is a browser asking before it authorises anything and cannot carry a signature at
     * all; `Health` answers a kubelet that has none; `PostObject` carries its own signature in the
     * form's policy rather than in the head; `NotImplemented` is refused before any of this.
     * `CopyObject` and `UploadPartCopy` are covered by their non-copy siblings — the same handler
     * gate, one route apart — and need a source object to be routed at all.
     */
    private val unsignedByDesign =
        setOf(
            S3Router.Route.Preflight::class,
            S3Router.Route.Health::class,
            S3Router.Route.PostObject::class,
            S3Router.Route.NotImplemented::class,
            S3Router.Route.CopyObject::class,
            S3Router.Route.UploadPartCopy::class,
        )

    @Test
    fun `every route refuses a request that named nobody`() {
        S3Fixture(anonymous = true).use { s3 ->
            s3.createBucket("vault")
            s3.put("vault", "a.txt", "секрет")

            for ((request, route) in requests) {
                val (method, path, query) = request
                val answer = s3.unsigned(method, path, query)
                assertTrue(
                    answer.status == 403 || answer.status == 404,
                    "${route.simpleName}: $method $path?$query answered ${answer.status}, not a refusal\n${answer.text}",
                )
                assertTrue("секрет" !in answer.text, "${route.simpleName} put the object in a refusal")
            }
        }
    }

    @Test
    fun `the table above covers every route the router can produce`() {
        // `permittedSubclasses` rather than Kotlin's `sealedSubclasses`: the latter needs
        // kotlin-reflect on the classpath, and a project with no runtime dependencies does not
        // acquire one so that a test can enumerate a sealed hierarchy. The JDK has known this
        // since sealed classes landed, and Kotlin compiles a sealed interface to exactly that.
        val covered = (requests.map { it.second } + unsignedByDesign).map { it.java }.toSet()
        val all = S3Router.Route::class.java.permittedSubclasses.toSet()

        assertEquals(
            emptySet(),
            all - covered,
            "a route was added and nothing here asks what it answers an unsigned request",
        )
        // And the other way, so the table cannot accumulate names the router no longer has.
        assertEquals(emptySet(), covered - all, "this names a route the router does not produce")
    }
}
