package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every arm of the routing table, one case each, in the order the table decides them (M-246).
 *
 * The neighbouring file asks about the interesting routes — the health handle, virtual-hosted
 * addressing, what is refused by name. This one asks the dull question that nothing else did: does
 * **each** branch of the `when` cascade decide what it says it decides.
 *
 * That question has an owner. Twenty-five negated conditionals in `bucketRoute` and `objectRoute`
 * survived the whole suite, and a negated conditional in a cascade means one thing: no request
 * distinguishes this arm from the one after it. Routing is where a request stops being bytes and
 * becomes an operation, so an arm that quietly falls through is a request answered by the wrong
 * code with a perfectly well-formed reply.
 *
 * The expected route is compared whole, because the branches are told apart by their **fields** as
 * often as by their type: `?cors` and `?policyStatus` both produce a `BucketSubresource`, and only
 * the name in it says which arm ran.
 */
class S3RouteTableTest {
    private val router = S3Router()
    private val key = ObjectKey.of("a.txt")

    private fun route(
        method: String,
        query: String = "",
        path: String = "/photos",
        copySource: String? = null,
    ) = router.route(method, "h", path, query, copySource)

    @Test
    fun `a bucket GET is decided by the first sub-resource that claims it`() {
        // Order is the specification here. `?location` before `?uploads` before `?versions` before
        // `list-type`, then the three families of sub-resource, then a plain listing. A case per
        // arm, because a case that lands two arms down proves nothing about the one above it.
        assertEquals(S3Router.Route.GetBucketLocation("photos"), route("GET", "location"))
        assertEquals(S3Router.Route.ListMultipartUploads("photos"), route("GET", "uploads"))
        assertEquals(S3Router.Route.ListObjectVersions("photos"), route("GET", "versions"))
        assertEquals(S3Router.Route.ListObjectsV2("photos"), route("GET", "list-type=2"))
        assertEquals(S3Router.Route.BucketSubresource("photos", "cors", "GET"), route("GET", "cors"))
        assertEquals(
            S3Router.Route.BucketSubresource("photos", "policyStatus", "GET"),
            route("GET", "policyStatus"),
            "read-only sub-resources answer a GET and nothing else",
        )
        assertEquals(
            S3Router.Route.NotImplemented("GET /photos?notification"),
            route("GET", "notification"),
            "a sub-resource S3 has and this does not is refused by name",
        )
        assertEquals(S3Router.Route.ListObjects("photos"), route("GET"))
    }

    @Test
    fun `list-type decides between the two listings, and only the value 2 does`() {
        // `list-type=1` is not a thing a client sends, and answering v2 to it would hand a v1
        // client a document with no marker in it.
        assertEquals(S3Router.Route.ListObjects("photos"), route("GET", "list-type=1"))
        assertEquals(S3Router.Route.ListObjects("photos"), route("GET", "list-type="))
    }

    @Test
    fun `the other bucket methods have their own cascades`() {
        assertEquals(S3Router.Route.BucketSubresource("photos", "policy", "PUT"), route("PUT", "policy"))
        assertEquals(S3Router.Route.NotImplemented("PUT /photos?…"), route("PUT", "website"))
        assertEquals(S3Router.Route.CreateBucket("photos"), route("PUT"))

        assertEquals(S3Router.Route.BucketSubresource("photos", "tagging", "DELETE"), route("DELETE", "tagging"))
        assertEquals(S3Router.Route.NotImplemented("DELETE /photos?…"), route("DELETE", "replication"))
        assertEquals(S3Router.Route.DeleteBucket("photos"), route("DELETE"))

        assertEquals(S3Router.Route.Preflight("photos"), route("OPTIONS"))
        assertEquals(S3Router.Route.HeadBucket("photos"), route("HEAD"))
        assertEquals(S3Router.Route.DeleteObjects("photos"), route("POST", "delete"))
        assertEquals(S3Router.Route.PostObject("photos"), route("POST"))
        assertEquals(S3Router.Route.NotImplemented("PATCH /photos"), route("PATCH"))
    }

    @Test
    fun `an object PUT is a part, a sub-resource, a copy or a write, in that order`() {
        val on = "/photos/a.txt"
        assertEquals(
            S3Router.Route.UploadPart("photos", key, "u-1", 3),
            route("PUT", "uploadId=u-1&partNumber=3", on),
        )
        assertEquals(
            S3Router.Route.NotImplemented("partNumber=three"),
            route("PUT", "uploadId=u-1&partNumber=three", on),
            "a part number that is not a number is refused rather than defaulted",
        )
        assertEquals(
            S3Router.Route.UploadPartCopy("photos", key, "u-1", 3, "src", ObjectKey.of("b.txt"), null),
            route("PUT", "uploadId=u-1&partNumber=3", on, copySource = "/src/b.txt"),
        )
        assertEquals(
            S3Router.Route.NotImplemented("x-amz-copy-source: nonsense"),
            route("PUT", "uploadId=u-1&partNumber=3", on, copySource = "nonsense"),
        )

        assertEquals(S3Router.Route.ObjectTagging("photos", key, "PUT"), route("PUT", "tagging", on))
        assertEquals(S3Router.Route.ObjectAcl("photos", key, "PUT", null), route("PUT", "acl", on))
        assertEquals(
            S3Router.Route.ObjectLockSubresource("photos", key, "retention", "PUT", null),
            route("PUT", "retention", on),
        )
        assertEquals(
            S3Router.Route.ObjectLockSubresource("photos", key, "legal-hold", "PUT", "v-9"),
            route("PUT", "legal-hold&versionId=v-9", on),
            "a lock names the version it is put on, or it lands on whichever is current",
        )
        assertEquals(S3Router.Route.NotImplemented("PUT object sub-resource"), route("PUT", "restore", on))
        assertEquals(
            S3Router.Route.CopyObject("photos", key, "src", ObjectKey.of("b.txt"), "v-2"),
            route("PUT", "", on, copySource = "/src/b.txt?versionId=v-2"),
        )
        assertEquals(
            S3Router.Route.NotImplemented("x-amz-copy-source: nonsense"),
            route("PUT", "", on, copySource = "nonsense"),
        )
        assertEquals(S3Router.Route.PutObject("photos", key), route("PUT", "", on))
    }

    @Test
    fun `an object GET is parts, attributes, a sub-resource or the bytes`() {
        val on = "/photos/a.txt"
        assertEquals(S3Router.Route.ListParts("photos", key, "u-1"), route("GET", "uploadId=u-1", on))
        assertEquals(
            S3Router.Route.GetObjectAttributes("photos", key, "v-3"),
            route("GET", "attributes&versionId=v-3", on),
        )
        assertEquals(S3Router.Route.ObjectTagging("photos", key, "GET"), route("GET", "tagging", on))
        assertEquals(S3Router.Route.ObjectAcl("photos", key, "GET", "v-3"), route("GET", "acl&versionId=v-3", on))
        assertEquals(
            S3Router.Route.ObjectLockSubresource("photos", key, "retention", "GET", null),
            route("GET", "retention", on),
        )
        assertEquals(S3Router.Route.NotImplemented("GET object sub-resource"), route("GET", "torrent", on))
        assertEquals(S3Router.Route.GetObject("photos", key, 2, "v-3"), route("GET", "partNumber=2&versionId=v-3", on))
        assertEquals(
            S3Router.Route.GetObject("photos", key, null, null),
            route("GET", "partNumber=two", on),
            "a part number that is not a number is not a part number, and the object still answers",
        )
    }

    @Test
    fun `the remaining object methods`() {
        val on = "/photos/a.txt"
        assertEquals(
            S3Router.Route.HeadObject("photos", key, 2, "v-3"),
            route("HEAD", "partNumber=2&versionId=v-3", on),
        )
        assertEquals(S3Router.Route.Preflight("photos"), route("OPTIONS", "", on))

        assertEquals(S3Router.Route.CreateMultipartUpload("photos", key), route("POST", "uploads", on))
        assertEquals(
            S3Router.Route.CompleteMultipartUpload("photos", key, "u-1"),
            route("POST", "uploadId=u-1", on),
        )
        assertEquals(S3Router.Route.NotImplemented("POST /photos/…"), route("POST", "", on))

        assertEquals(S3Router.Route.AbortMultipartUpload("photos", key, "u-1"), route("DELETE", "uploadId=u-1", on))
        assertEquals(S3Router.Route.ObjectTagging("photos", key, "DELETE"), route("DELETE", "tagging", on))
        assertEquals(S3Router.Route.NotImplemented("DELETE object sub-resource"), route("DELETE", "torrent", on))
        assertEquals(S3Router.Route.DeleteObject("photos", key, "v-3"), route("DELETE", "versionId=v-3", on))

        assertEquals(S3Router.Route.NotImplemented("PATCH /photos/…"), route("PATCH", "", on))
    }

    @Test
    fun `a query parameter is a name with an optional value, and both forms decide`() {
        // `?uploads` carries no value and `?list-type=2` carries one; a parser that required `=`
        // would route every valueless sub-resource to a plain listing, and one that ignored the
        // value would answer v2 to a v1 client.
        assertEquals(S3Router.Route.ListMultipartUploads("photos"), route("GET", "uploads="))
        assertEquals(S3Router.Route.ListMultipartUploads("photos"), route("GET", "uploads&max-uploads=10"))
        assertEquals(S3Router.Route.ListObjectsV2("photos"), route("GET", "prefix=a&list-type=2"))
        assertEquals(
            S3Router.Route.GetObject("photos", key, null, "v 3"),
            route("GET", "versionId=v%203", "/photos/a.txt"),
            "a query value is percent-decoded, or a version id with a space names nothing",
        )
    }
}
