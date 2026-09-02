package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The storage class an object was written with, kept and answered (M-301).
 *
 * Found by `mint`: `awscli` writes an object with a class and asks `HEAD` for it back — "StorageClass
 * was not applied" — and `mc` expects a class nobody implements to be refused. Before this, the
 * header was read by nothing at all: every listing said `STANDARD` because that string was written
 * into the document, and the request's own value was dropped on the floor.
 *
 * That is the shape this repository forbids itself in one line: **do not accept what you do not
 * enforce**. A client that names a class and is answered `200` has been told the object is stored
 * that way.
 */
class StorageClassTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    @Test
    fun `a class that is stored comes back on head and in a listing`() {
        s3.createBucket("photos")

        assertEquals(
            200,
            s3.put("photos", "cold.txt", "content", headers = listOf("x-amz-storage-class" to "STANDARD_IA")).status,
        )

        val head = s3.send("HEAD", "/photos/cold.txt")
        assertEquals("STANDARD_IA", head.header("x-amz-storage-class"), "the class was not applied")

        val listing = s3.send("GET", "/photos", query = "list-type=2")
        assertContains(listing.text, "<StorageClass>STANDARD_IA</StorageClass>")
    }

    @Test
    fun `the default class is not announced, because S3 does not announce it`() {
        // `x-amz-storage-class` is absent for a STANDARD object rather than present and saying
        // STANDARD — a client that reads the header to decide whether an object is on a slow tier
        // would otherwise see one on every object.
        s3.createBucket("photos")
        s3.put("photos", "warm.txt", "content")

        assertNull(s3.send("HEAD", "/photos/warm.txt").header("x-amz-storage-class"))
        assertContains(s3.send("GET", "/photos", query = "list-type=2").text, "<StorageClass>STANDARD</StorageClass>")
    }

    @Test
    fun `a class whose behaviour this server does not implement is refused by name`() {
        // GLACIER and DEEP_ARCHIVE are not slower storage, they are storage that has to be restored
        // before it can be read. Accepting one and serving the object immediately would be a lie
        // told in a header, which is exactly what the rule above is about.
        s3.createBucket("photos")

        val glacier = s3.put("photos", "frozen.txt", "content", headers = listOf("x-amz-storage-class" to "GLACIER"))

        assertEquals(400, glacier.status, "GLACIER was accepted by a server with no restore path")
        assertContains(glacier.text, "InvalidStorageClass")
    }

    @Test
    fun `a class that is not a class at all is refused`() {
        s3.createBucket("photos")

        val nonsense = s3.put("photos", "x.txt", "content", headers = listOf("x-amz-storage-class" to "WARM_ISH"))

        assertEquals(400, nonsense.status)
        assertContains(nonsense.text, "InvalidStorageClass")
    }
}
