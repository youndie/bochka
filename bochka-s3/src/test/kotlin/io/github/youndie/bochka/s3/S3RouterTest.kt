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
    fun `what bochka does not implement is refused by name rather than answered`() {
        // The important half. `GET /photos?versions` answered with an empty listing tells the
        // client there are no versions, which is a lie shaped exactly like an answer.
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("GET", "h", "/photos", "versions"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("GET", "h", "/photos", "acl"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("PUT", "h", "/photos", "versioning"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("GET", "h", "/photos/a.txt", "acl"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("PUT", "h", "/photos/a.txt", "tagging"))
        assertIs<S3Router.Route.NotImplemented>(pathStyle.route("PATCH", "h", "/photos/a.txt", ""))
    }
}
