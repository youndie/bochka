package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Method and URI per operation: `docs/spec/s3-service-2.json`, `operations.*.http`.
 */
class S3RouterTest {
    private val pathStyle = S3Router()
    private val virtual = S3Router(virtualHostSuffixes = listOf("s3.example.com"))

    @Test
    fun `the health handle is a path no bucket can be called`() {
        // M-143. A kubelet needs an unauthenticated 200 or it forks a shell every period, and an
        // S3 server has no spare namespace to put one in -- MinIO put its own under /minio, which
        // is a name somebody's bucket can legally have. `-` cannot: one character is below the
        // three-character floor and it is neither a letter nor a digit at either edge
        // (`BucketNameRules`), so nothing legal is shadowed by taking it.
        assertEquals(S3Router.Route.Health, pathStyle.route("GET", "h", "/-/healthy", ""))
        assertEquals(S3Router.Route.Health, pathStyle.route("HEAD", "h", "/-/healthy", ""))
    }

    @Test
    fun `nothing else under that path is the health handle`() {
        // The hole is exactly one method on exactly one path. Anything else keeps meaning what it
        // meant, which for a bucket called `-` is a refusal by name.
        assertIs<S3Router.Route.PutObject>(pathStyle.route("PUT", "h", "/-/healthy", ""))
        assertIs<S3Router.Route.GetObject>(pathStyle.route("GET", "h", "/-/ready", ""))
        assertIs<S3Router.Route.GetObject>(pathStyle.route("GET", "h", "/-/healthy/deeper", ""))
    }

    @Test
    fun `a virtual host addresses an object, health or not`() {
        // Under virtual-host addressing the bucket comes from the Host header and the whole path
        // is the key, so `-/healthy` is a perfectly ordinary key in somebody's bucket. Answering
        // health there would make that key unreachable -- silently, and only for whoever stored it.
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("-/healthy")),
            virtual.route("GET", "photos.s3.example.com", "/-/healthy", ""),
        )
    }

    @Test
    fun `bucket operations route by method`() {
        assertEquals(S3Router.Route.ListBuckets, pathStyle.route("GET", "h", "/", ""))
        assertEquals(S3Router.Route.CreateBucket("photos"), pathStyle.route("PUT", "h", "/photos", ""))
        assertEquals(S3Router.Route.DeleteBucket("photos"), pathStyle.route("DELETE", "h", "/photos", ""))
        assertEquals(S3Router.Route.HeadBucket("photos"), pathStyle.route("HEAD", "h", "/photos", ""))
    }

    @Test
    fun `an operation can be decided by a query parameter with no value`() {
        // `?uploads`, `?delete` and `?location` carry nothing; they *are* the operation. A router
        // that only looks at name=value pairs routes all three to a listing.
        assertEquals(S3Router.Route.GetBucketLocation("photos"), pathStyle.route("GET", "h", "/photos", "location"))
        assertEquals(S3Router.Route.ListMultipartUploads("photos"), pathStyle.route("GET", "h", "/photos", "uploads"))
        assertEquals(S3Router.Route.DeleteObjects("photos"), pathStyle.route("POST", "h", "/photos", "delete"))
        assertEquals(
            S3Router.Route.CreateMultipartUpload("photos", ObjectKey.of("big.bin")),
            pathStyle.route("POST", "h", "/photos/big.bin", "uploads"),
        )
    }

    @Test
    fun `the same method and path are different operations by query`() {
        val create = pathStyle.route("POST", "h", "/photos/big.bin", "uploads")
        val complete = pathStyle.route("POST", "h", "/photos/big.bin", "uploadId=abc")

        assertIs<S3Router.Route.CreateMultipartUpload>(create)
        assertEquals(S3Router.Route.CompleteMultipartUpload("photos", ObjectKey.of("big.bin"), "abc"), complete)
    }

    @Test
    fun `listing v1 and v2 are told apart by list-type`() {
        assertEquals(S3Router.Route.ListObjectsV2("photos"), pathStyle.route("GET", "h", "/photos", "list-type=2"))
        assertEquals(S3Router.Route.ListObjects("photos"), pathStyle.route("GET", "h", "/photos", ""))
        assertEquals(
            S3Router.Route.ListObjects("photos"),
            pathStyle.route("GET", "h", "/photos", "prefix=a&max-keys=10"),
        )
    }

    @Test
    fun `object operations carry the decoded key`() {
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("my dir/file.txt")),
            pathStyle.route("GET", "h", "/photos/my%20dir/file.txt", ""),
        )
        assertEquals(
            S3Router.Route.PutObject("photos", ObjectKey.of("a/b/c")),
            pathStyle.route("PUT", "h", "/photos/a/b/c", ""),
        )
        // %2F is a byte of the key, not a separator: the key is `a/b`, one segment on the wire.
        assertEquals(
            S3Router.Route.DeleteObject("photos", ObjectKey.of("a/b")),
            pathStyle.route("DELETE", "h", "/photos/a%2Fb", ""),
        )
    }

    @Test
    fun `an upload part carries its number and upload id`() {
        assertEquals(
            S3Router.Route.UploadPart("photos", ObjectKey.of("big.bin"), "abc", 7),
            pathStyle.route("PUT", "h", "/photos/big.bin", "partNumber=7&uploadId=abc"),
        )
        assertEquals(
            S3Router.Route.ListParts("photos", ObjectKey.of("big.bin"), "abc"),
            pathStyle.route("GET", "h", "/photos/big.bin", "uploadId=abc"),
        )
        assertEquals(
            S3Router.Route.AbortMultipartUpload("photos", ObjectKey.of("big.bin"), "abc"),
            pathStyle.route("DELETE", "h", "/photos/big.bin", "uploadId=abc"),
        )
    }

    @Test
    fun `virtual-hosted addressing takes the bucket from the host`() {
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("a.txt")),
            virtual.route("GET", "photos.s3.example.com", "/a.txt", ""),
        )
        assertEquals(
            S3Router.Route.ListObjectsV2("photos"),
            virtual.route("GET", "photos.s3.example.com", "/", "list-type=2"),
        )
        assertEquals(S3Router.Route.ListBuckets, virtual.route("GET", "s3.example.com", "/", ""))
    }

    @Test
    fun `a host that is not a configured suffix is path-style`() {
        // No guessing: an unconfigured domain means the bucket is in the path, even when the host
        // looks like it could carry one. Guessing wrong makes Host sign one thing and route another.
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("a.txt")),
            virtual.route("GET", "photos.s3.elsewhere.com", "/photos/a.txt", ""),
        )
        assertEquals(
            S3Router.Route.GetObject("photos", ObjectKey.of("a.txt")),
            pathStyle.route("GET", "127.0.0.1:9000", "/photos/a.txt", ""),
        )
    }

    @Test
    fun `the port is not part of the host when matching a suffix`() {
        assertEquals(
            S3Router.Route.HeadObject("photos", ObjectKey.of("a.txt")),
            virtual.route("HEAD", "photos.s3.example.com:9000", "/a.txt", ""),
        )
    }

    @Test
    fun `listing versions is answered, because a bucket without versioning still has an answer`() {
        // It looks like a versioning feature and is not one: `?versions` is how a client lists a
        // bucket that has no versioning, and the answer is the objects at version `null`. Refusing
        // it makes a store unusable rather than unversioned — the compatibility suite calls it
        // before every single test to clean up, and a 501 errored 837 of 838 tests before any of
        // them reached what they check.
        assertEquals(S3Router.Route.ListObjectVersions("photos"), pathStyle.route("GET", "h", "/photos", "versions"))
    }

    @Test
    fun `what bochka does not implement is refused by name rather than answered`() {
        // The important half. `GET /photos?versions` answered with an empty listing tells the
        // client there are no versions, which is a lie shaped exactly like an answer.
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("GET", "h", "/photos", "notification"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("GET", "h", "/photos/a.txt", "restore"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("PATCH", "h", "/photos/a.txt", ""))
    }

    @Test
    fun `tags and CORS are intercepted before the blanket refusal`() {
        // This line used to stand in the test above: `?tagging` was refused by name. The list of
        // refusals is a record of the boundaries, and when the boundaries move it is the list that
        // changes, not the behaviour to fit it.
        assertIs<S3Router.Route.ObjectTagging>(pathStyle.route("PUT", "h", "/photos/a.txt", "tagging"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "tagging"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("PUT", "h", "/photos", "cors"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("DELETE", "h", "/photos", "cors"))
        // `?versioning` moved across this line in M-103: it was refused by name, and now it is
        // answered — a bucket nobody configured has a defined empty configuration, and that is not
        // the same as a feature the server does not have.
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("PUT", "h", "/photos", "versioning"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "versioning"))
        // The third move was M20: `?policy`, `?lifecycle` and `?acl` began answering `GET` — "there
        // is no setting" is a question with a definite answer — but **only** `GET`. The sixth was
        // M-201а, and that ended the half-measure: the server started **enforcing** the policy, so
        // the accepting side stopped being a refusal. The direction is the same as in every earlier
        // move, and there is no travelling back.
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "policy"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("PUT", "h", "/photos", "policy"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("DELETE", "h", "/photos", "policy"))
        // And the fifth move, M27: `?acl` left the answer-on-`GET` group for settings on both
        // methods, and the object got a route of its own. The direction is the same: the server
        // began **doing** what this subresource describes — an owner and a canned ACL decide who
        // may do what — and until then `PUT ?acl` was a refusal by name.
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "acl"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("PUT", "h", "/photos", "acl"))
        assertIs<S3Router.Route.ObjectAcl>(pathStyle.route("GET", "h", "/photos/a.txt", "acl"))
        assertIs<S3Router.Route.ObjectAcl>(pathStyle.route("PUT", "h", "/photos/a.txt", "acl"))
        // And the fourth move, M23: `?lifecycle` was among those answering `GET` and refusing
        // everything else, and became a setting on three methods. The moves all run one way: a
        // subresource leaves the refusing group when the server begins **doing** what it describes,
        // not when answering has become convenient.
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("GET", "h", "/photos", "lifecycle"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("PUT", "h", "/photos", "lifecycle"))
        assertIs<S3Router.Route.BucketSubresource>(pathStyle.route("DELETE", "h", "/photos", "lifecycle"))
    }

    @Test
    fun `a preflight routes the same way from a bucket and from an object`() {
        // The rules belong to the bucket, while a browser sends `OPTIONS` to the address it intends
        // to request — that is, more often to an object. The key is needed here for nothing at
        // all.
        assertIs<S3Router.Route.Preflight>(pathStyle.route("OPTIONS", "h", "/photos", ""))
        assertIs<S3Router.Route.Preflight>(pathStyle.route("OPTIONS", "h", "/photos/a.txt", ""))
    }
}
