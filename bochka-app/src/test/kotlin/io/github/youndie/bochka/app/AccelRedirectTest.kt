package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Handing a whole-object read to the terminator in front (`X-Accel-Redirect`, open question 5).
 *
 * The measurement that opened the question is in `docs/measurements.md`: nginx relaying an upstream
 * socket costs 2.408 processor-seconds per gibibyte, and nginx sending a file costs 0.898 — same
 * TLS, same kTLS, same bytes. This is the mechanism that turns the second number into the one the
 * deployment gets, and it is off unless a deployment asks for it, because it requires the
 * terminator to be able to read the data directory.
 *
 * What is checked here is the **shape** of the answer. That nginx then serves the right bytes is
 * not a thing this test can know, and it was checked on the stand instead.
 */
class AccelRedirectTest {
    private val plain = S3Fixture()
    private val handing = S3Fixture(accelRedirect = "/bochka-data")

    @AfterTest
    fun cleanup() {
        plain.close()
        handing.close()
    }

    @Test
    fun `a whole-object GET names the file and sends no body`() {
        handing.createBucket("photos")
        handing.put("photos", "a.txt", "the bytes themselves")

        val answer = handing.get("photos", "a.txt")

        assertEquals(200, answer.status, answer.text)
        val redirect = answer.header("X-Accel-Redirect")
        assertNotNull(redirect, "the terminator has to be told which file to send")
        assertTrue(redirect.startsWith("/bochka-data/"), "named under the internal prefix, got $redirect")
        assertEquals(0, answer.body.size, "the body is the terminator's to send, not ours")

        // The path is relative to the data root, because that is the only root both processes
        // agree on: this server's absolute path is its own view of the filesystem.
        val relative = redirect.removePrefix("/bochka-data/")
        assertTrue(
            handing.store.dataRoot
                .resolve(relative)
                .toFile()
                .exists(),
            "the named file has to exist under the data root, got $relative",
        )
    }

    @Test
    fun `the headers a client reads still come from here`() {
        // The terminator sends the bytes; everything that says what the bytes **are** is still this
        // server's answer, because it is the only side that has the index.
        handing.createBucket("photos")
        handing.put("photos", "a.txt", "x", listOf("Content-Type" to "text/plain", "x-amz-meta-who" to "me"))

        val answer = handing.get("photos", "a.txt")

        assertEquals("text/plain", answer.header("Content-Type"))
        assertEquals("me", answer.header("x-amz-meta-who"))
        assertNotNull(answer.header("ETag"))
        assertNotNull(answer.header("Last-Modified"))
    }

    @Test
    fun `a HEAD is answered here, not handed over`() {
        // A `HEAD` has no body for the terminator to send, and handing it over would make nginx
        // produce one. It also has to keep answering `Content-Length`, which rclone reads.
        handing.createBucket("photos")
        handing.put("photos", "a.txt", "twelve bytes")

        val answer = handing.send("HEAD", "/photos/a.txt")

        assertEquals(200, answer.status)
        assertNull(answer.header("X-Accel-Redirect"), "a HEAD has nothing to hand over")
        assertEquals("12", answer.header("Content-Length"))
    }

    @Test
    fun `partNumber is answered here, because no header can name a slice`() {
        // `X-Accel-Redirect` says which file and never which part of it. A multipart object's part
        // is a range of the assembled file, so it stays on this side.
        val fiveMiB = 5 * 1024 * 1024
        handing.createBucket("photos")
        val started = handing.send("POST", "/photos/big.bin", query = "uploads")
        val uploadId = Regex("<UploadId>(.*?)</UploadId>").find(started.text)!!.groupValues[1]
        val first =
            handing.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                body = ByteArray(fiveMiB),
            )
        val second =
            handing.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=2&uploadId=$uploadId",
                body = "tail".toByteArray(),
            )
        val body =
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>${first.header("ETag")}</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>${second.header("ETag")}</ETag></Part>" +
                "</CompleteMultipartUpload>"
        handing.send("POST", "/photos/big.bin", query = "uploadId=$uploadId", body = body.toByteArray())

        val part = handing.send("GET", "/photos/big.bin", query = "partNumber=2")

        assertEquals(206, part.status, part.text)
        assertNull(part.header("X-Accel-Redirect"), "a part is a slice, and no header names one")
        assertEquals("tail", part.text)
    }

    @Test
    fun `unset, the object is sent from here as before`() {
        // The default, and the only thing that works when nothing shares this filesystem.
        plain.createBucket("photos")
        plain.put("photos", "a.txt", "the bytes themselves")

        val answer = plain.get("photos", "a.txt")

        assertNull(answer.header("X-Accel-Redirect"))
        assertEquals("the bytes themselves", answer.text)
    }
}
