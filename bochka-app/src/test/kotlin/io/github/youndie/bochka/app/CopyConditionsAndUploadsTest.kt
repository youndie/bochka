package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The two `mint` failures that were causing six others (M-303, M-304).
 *
 * Both are the same shape as M-301 — a header accepted and not enforced, and a document missing a
 * field — and both were found not by the case that names them but by the wreckage they left: the
 * objects they failed to clean up made a later listing report twelve where ten were expected, and
 * the bucket they left behind made three `after all` hooks fail on `BucketNotEmpty`. Six failures
 * downstream of two.
 */
class CopyConditionsAndUploadsTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    @Test
    fun `a copy is refused when the source changed after the date the client named`() {
        // `x-amz-copy-source-if-unmodified-since` in the past: the source was written a moment ago,
        // so it *has* been modified since, and the copy must not happen. Read by nothing before
        // this, so every such copy succeeded — and a client that uses the condition to avoid
        // overwriting a newer object was overwriting it.
        s3.createBucket("photos")
        s3.put("photos", "source.txt", "content")

        val copy =
            s3.send(
                "PUT",
                "/photos/copy.txt",
                headers =
                    listOf(
                        "x-amz-copy-source" to "/photos/source.txt",
                        "x-amz-copy-source-if-unmodified-since" to "Fri, 26 Mar 2010 12:00:00 GMT",
                    ),
            )

        assertEquals(412, copy.status, "the copy went ahead although the source changed after that date")
        assertEquals(404, s3.send("HEAD", "/photos/copy.txt").status, "a refused copy left an object behind")
    }

    @Test
    fun `a copy is refused when the source has not changed since the date the client named`() {
        // The other direction, and the reason it is here: a condition that refuses everything is
        // as wrong as one that refuses nothing, and only a pair says which one this is.
        s3.createBucket("photos")
        s3.put("photos", "source.txt", "content")

        val copy =
            s3.send(
                "PUT",
                "/photos/copy.txt",
                headers =
                    listOf(
                        "x-amz-copy-source" to "/photos/source.txt",
                        "x-amz-copy-source-if-modified-since" to "Sat, 01 Jan 2050 12:00:00 GMT",
                    ),
            )

        assertEquals(412, copy.status, "the copy went ahead although the source has not changed since that date")
    }

    @Test
    fun `a copy the conditions allow still happens`() {
        s3.createBucket("photos")
        s3.put("photos", "source.txt", "content")

        val copy =
            s3.send(
                "PUT",
                "/photos/copy.txt",
                headers =
                    listOf(
                        "x-amz-copy-source" to "/photos/source.txt",
                        "x-amz-copy-source-if-modified-since" to "Fri, 26 Mar 2010 12:00:00 GMT",
                    ),
            )

        assertEquals(200, copy.status, "a condition that is satisfied refused the copy: ${copy.text}")
    }

    @Test
    fun `a listing of uploads says who started each one`() {
        // `ListMultipartUploads` carries an `Initiator` and an `Owner` per upload in S3, and a
        // client that reads them does not check first: minio-js dereferences `upload.Initiator.ID`
        // and dies with a TypeError, which reads as a broken client rather than a short document.
        s3.createBucket("photos")
        val started = s3.send("POST", "/photos/big.bin", query = "uploads")
        assertEquals(200, started.status)

        val listing = s3.send("GET", "/photos", query = "uploads")

        assertContains(listing.text, "<Initiator>")
        assertContains(listing.text, "<Owner>")
        assertContains(
            listing.text,
            "<ID>${S3Fixture.ACCESS_KEY}</ID>",
            message = "the upload does not say which key started it: ${listing.text}",
        )
    }
}
