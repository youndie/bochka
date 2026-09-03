package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.s3.SseC
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SSE-C: encryption under a key the client brings (M26).
 *
 * The shape is in `docs/spec/s3-service-2.json`: `PutObjectRequest` (`:11815`) takes three headers,
 * `x-amz-server-side-encryption-customer-algorithm`, `-key` and `-key-MD5`, while `GetObjectOutput`
 * (`:6385`) returns **two** of the three: the algorithm and the MD5. No operation returns the key —
 * the model says so outright, and that is also the first thing asserted here.
 *
 * **Why the server keeps the MD5 at all.** It does not keep the key and must not, or encryption
 * under the client's key is no different from encryption under the server's. But a read has to be
 * able to tell a right key from a wrong one, or a wrong key hands back rubbish instead of a
 * refusal. The MD5 is exactly for that: it is what is compared, not the result of decrypting.
 *
 * What this milestone costs is named in the coverage research and not hidden: an encrypted object
 * has no `transferTo` path. Whoever sent the key pays for it; an ordinary object still goes out
 * with `sendfile`, and there is a separate precondition test saying so.
 */
class EncryptionSseCTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private val key = "pO3upElrwuEXSoFwCfnZPdSsmt/xWeFa0N9KgDijwVs="
    private val keyMd5 = "DWygnHRtgiJ77HCm+1rvHw=="
    private val otherKey = "6b+WOZ1T3cqZMxgThRcXAQBrcccMhqz1t3+/9Sxk3Kg="
    private val otherMd5 = "D1IPsYYEdPdiYKKd/N2XlQ=="

    private fun sseC(
        keyValue: String = key,
        md5: String = keyMd5,
    ) = listOf(
        "x-amz-server-side-encryption-customer-algorithm" to "AES256",
        "x-amz-server-side-encryption-customer-key" to keyValue,
        "x-amz-server-side-encryption-customer-key-md5" to md5,
    )

    @Test
    fun `an object written with a customer key comes back with the same key`() {
        s3.createBucket("photos")

        val written = s3.put("photos", "secret.txt", "A".repeat(1000), sseC())
        assertEquals(200, written.status, written.text)
        assertEquals("AES256", written.header("x-amz-server-side-encryption-customer-algorithm"))
        assertEquals(keyMd5, written.header("x-amz-server-side-encryption-customer-key-md5"))

        val read = s3.get("photos", "secret.txt", sseC())
        assertEquals(200, read.status, read.text)
        assertEquals("A".repeat(1000), read.text)
        assertEquals("AES256", read.header("x-amz-server-side-encryption-customer-algorithm"))
        assertEquals(keyMd5, read.header("x-amz-server-side-encryption-customer-key-md5"))
    }

    @Test
    fun `the key itself never appears in an answer`() {
        // `GetObjectOutput` names two members of the three, and the missing third is not the model
        // being forgetful. A server that hands the key back undoes the whole difference between
        // SSE-C and SSE-S3.
        s3.createBucket("photos")
        s3.put("photos", "secret.txt", "hello", sseC())

        val read = s3.get("photos", "secret.txt", sseC())

        assertNull(read.header("x-amz-server-side-encryption-customer-key"))
        assertFalse(read.text.contains(key))
    }

    @Test
    fun `reading an encrypted object without a key is refused, and with 400`() {
        // `test_encryption_sse_c_method_head` expects `400` and not `403`: a missing key is an
        // incomplete request rather than a refusal of access. The client sees the difference and it
        // is not cosmetic — a `403` sends it off to reissue a signature, a `400` to look at what it
        // failed to send.
        s3.createBucket("photos")
        s3.put("photos", "secret.txt", "hello", sseC())

        assertEquals(400, s3.get("photos", "secret.txt").status)
        assertEquals(400, s3.send("HEAD", "/photos/secret.txt").status)
    }

    @Test
    fun `reading an encrypted object with the wrong key is refused, and with 400`() {
        // **This said 403, and that was reasoning rather than a fact.** The reasoning went: a key
        // that does not open the object is a refusal of access. The suite says `400`
        // (`test_encryption_sse_c_other_key`, with no `fails_on_aws` marker), the model says
        // nothing about the status, and so the suite decides.
        //
        // On second look it is right, too: access here is decided by the **signature**, and this
        // request's signature is good. The key is a request parameter, and a parameter that cannot
        // do its job is a bad request — the same answer a wrong checksum gets. A `403` would send
        // the client off to reissue a signature that was never the problem.
        s3.createBucket("photos")
        s3.put("photos", "secret.txt", "hello", sseC())

        assertEquals(400, s3.get("photos", "secret.txt", sseC(otherKey, otherMd5)).status)
    }

    @Test
    fun `a key whose md5 does not match it is refused before anything is stored`() {
        // Checked before the write: an MD5 that does not match the key means the client made a
        // mistake now, not that the object is damaged. Storing it and finding out on a read turns a
        // typo into a lost object.
        s3.createBucket("photos")

        assertEquals(400, s3.put("photos", "secret.txt", "hello", sseC(key, otherMd5)).status)
        assertEquals(404, s3.get("photos", "secret.txt", sseC()).status)
    }

    @Test
    fun `the object on the disk is not the object`() {
        // Without this the rest is theatre. What is asserted is not "the server gave the same bytes
        // back" — it would do that without encrypting at all — but that **what is on the disk is
        // different**. The test reaches into the file behind the server's back precisely because
        // from the outside those two cases look identical.
        s3.createBucket("photos")
        val plain = "the quick brown fox".repeat(10)
        s3.put("photos", "secret.txt", plain, sseC())

        val stored =
            s3.store.get(
                "photos",
                io.github.youndie.bochka.core.ObjectKey
                    .of("secret.txt"),
            )!!
        val onDisk =
            java.nio.file.Files
                .readAllBytes(s3.store.pathOf(stored))

        assertEquals(plain.length.toLong(), stored.size, "counter mode does not change the length")
        assertFalse(String(onDisk).contains("quick"), "the plaintext is on the disk")
        assertEquals("AES256", stored.encryption?.algorithm)
        assertEquals(keyMd5, stored.encryption?.keyMd5)
    }

    @Test
    fun `an ordinary object still goes out the fast way`() {
        // M-188, a precondition test. The second read path exists for encrypted objects and for
        // nothing else, and what it costs has been measured; an object nobody encrypted must still
        // go out with `transferTo`. The marker is the slice's `through`: its presence **is** the
        // slow path.
        s3.createBucket("photos")
        s3.put("photos", "plain.txt", "hello")

        val answer = s3.get("photos", "plain.txt")

        assertEquals(200, answer.status)
        assertEquals("hello", answer.text)
        assertNull(answer.header("x-amz-server-side-encryption-customer-algorithm"))

        // And the marker itself, which none of the three assertions above can see: both read paths
        // put the same bytes on the same wire, so a server that filtered every response would
        // answer this request exactly as it just did. The filter is asked for directly.
        val plain = s3.store.get("photos", ObjectKey.of("plain.txt"))!!
        assertNull(s3.handler.decrypting(plain, null, 0), "an unencrypted object was given a filter")
        assertNull(
            s3.handler.decrypting(plain, SseC.of { name -> sseC().toMap()[name] }, 0),
            "a key presented for an object that has none turned the fast path off",
        )
    }

    @Test
    fun `an encrypted object is given a filter, which is what makes the check above mean anything`() {
        // The other half of the pair. Without it "no filter" is a statement that would also be true
        // of a server which had lost the slow path entirely, and then SSE-C would be answering
        // ciphertext.
        s3.createBucket("photos")
        s3.put("photos", "secret.txt", "hello", sseC())

        val stored = s3.store.get("photos", ObjectKey.of("secret.txt"))!!

        assertNotNull(
            s3.handler.decrypting(stored, SseC.of { name -> sseC().toMap()[name] }, 0),
            "an encrypted object went out unfiltered",
        )
    }

    @Test
    fun `a multipart object is encrypted part by part and reads back whole`() {
        // M-189. Every part has its own IV and the object is assembled by concatenating the
        // ciphertexts, so a read has to switch cipher at the seams. The parts here are deliberately
        // of different sizes: with equal ones a mistake in the boundary arithmetic does not show.
        s3.createBucket("photos")
        val first = "A".repeat(5 * 1024 * 1024)
        val second = "B".repeat(1024)

        val started = s3.send("POST", "/photos/big.bin", query = "uploads", headers = sseC())
        assertEquals(200, started.status, started.text)
        val uploadId = Regex("<UploadId>([^<]+)</UploadId>").find(started.text)!!.groupValues[1]

        val p1 =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                headers = sseC(),
                body = first.toByteArray(),
            )
        val p2 =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=2&uploadId=$uploadId",
                headers = sseC(),
                body = second.toByteArray(),
            )
        assertEquals(200, p1.status, p1.text)
        assertEquals(200, p2.status, p2.text)

        val completion =
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>${p1.header("ETag")}</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>${p2.header("ETag")}</ETag></Part>" +
                "</CompleteMultipartUpload>"
        val done = s3.send("POST", "/photos/big.bin", query = "uploadId=$uploadId", body = completion.toByteArray())
        assertEquals(200, done.status, done.text)

        val read = s3.get("photos", "big.bin", sseC())
        assertEquals(200, read.status, read.text)
        assertEquals(first + second, read.text)
    }

    @Test
    fun `a part that does not carry the upload's key is refused`() {
        // And the refusal comes **from screen**, before the body is read: an answer sent after the
        // part has started moving means the server closes the connection while the client is still
        // writing five mebibytes. Both sides then wait, and the suite reports that as a timeout
        // rather than as a refusal — which is what happened while the check lived in the handler.
        s3.createBucket("photos")
        val started = s3.send("POST", "/photos/big.bin", query = "uploads", headers = sseC())
        val uploadId = Regex("<UploadId>([^<]+)</UploadId>").find(started.text)!!.groupValues[1]

        val wrong =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                headers = sseC(otherKey, otherMd5),
                body = "x".toByteArray(),
            )
        assertEquals(400, wrong.status)

        val none =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                body = "x".toByteArray(),
            )
        assertEquals(400, none.status)
    }

    @Test
    fun `an unencrypted object refuses a key rather than pretending`() {
        // The other side of it: a key on an object nobody encrypted. S3 answers `400`, and that is
        // the same rule by which everything the server does not enforce is refused here — taking
        // the key and quietly handing back unencrypted bytes would be a lie about encryption.
        s3.createBucket("photos")
        s3.put("photos", "plain.txt", "hello")

        assertEquals(400, s3.get("photos", "plain.txt", sseC()).status)
    }

    @Test
    fun `a part uploaded twice gets the same ETag`() {
        // M-190а. Every upload has its own IV, so an ETag taken over the ciphertext came out
        // different for the same bytes — while `ceph/s3-tests` (`test_multipart_sse_c_get_part`)
        // records the ETag of the **first** upload, sends the part again, and completes with the
        // old value. The case is not marked as failing on AWS, so re-upload is deterministic there.
        //
        // This is not about one case of the suite: a client that retries a part upload which
        // already succeeded would otherwise be refused at completion, and the cause would be a
        // random number inside the server.
        val b = "photos"
        s3.createBucket(b)
        val upload = s3.send("POST", "/$b/big.bin", query = "uploads", headers = sseC())
        val id = upload.text.substringAfter("<UploadId>").substringBefore("</UploadId>")
        val part = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }

        val first = s3.send("PUT", "/$b/big.bin", query = "partNumber=1&uploadId=$id", headers = sseC(), body = part)
        val again = s3.send("PUT", "/$b/big.bin", query = "partNumber=1&uploadId=$id", headers = sseC(), body = part)

        assertEquals(200, first.status, first.text)
        assertEquals(200, again.status, again.text)
        assertEquals(first.header("ETag"), again.header("ETag"), "the same bytes under the same key give the same ETag")
    }

    @Test
    fun `the ETag of an encrypted object does not give its contents away`() {
        // A listing hands out ETags and asks for no key, so an ETag equal to the plaintext MD5 would
        // give anybody who can list the bucket a way to confirm a guess about the contents. Hence a
        // MAC under the client's key: deterministic for whoever holds it, opaque to everyone else.
        val b = "photos"
        s3.createBucket(b)
        val body = "содержимое, о котором можно догадаться".toByteArray()
        val plainMd5 =
            java.security.MessageDigest
                .getInstance("MD5")
                .digest(body)
                .joinToString("") { "%02x".format(it) }

        s3.put(b, "secret.txt", String(body), headers = sseC())
        val listed = s3.send("GET", "/$b", query = "list-type=2")

        assertTrue("secret.txt" in listed.text, listed.text)
        assertTrue(plainMd5 !in listed.text, "a listing must not hand out the plaintext MD5")

        // And the same object unencrypted keeps the ordinary MD5, because that is what is expected
        // there.
        s3.put(b, "open.txt", String(body))
        assertEquals("\"$plainMd5\"", s3.get(b, "open.txt").header("ETag"))
    }

    @Test
    fun `a POST form encrypts and gives the same ETag as an ordinary write`() {
        // The third path that encrypts, and until M-190а it had no test at all — so nobody would
        // have checked the ETag change there. Equality with an ordinary write asserts two things at
        // once: that the form went through the same MAC, and that the MAC depends on nothing but
        // the bytes and the key.
        val b = "photos"
        s3.createBucket(b)
        val body = "то же самое, двумя дорогами".toByteArray()

        val put = s3.put(b, "by-put.bin", String(body), headers = sseC())
        val posted =
            s3.postForm(
                b,
                s3.signedPolicy(
                    buildString {
                        val open = { field: String -> """["starts-with","${'$'}$field",""]""" }
                        append("""{"expiration":"2099-01-01T00:00:00Z","conditions":[""")
                        append("""{"bucket":"$b"},""")
                        append(open("key"))
                        for (name in sseC()) append(",").append(open(name.first))
                        append("]}")
                    },
                ) + listOf("key" to "by-form.bin") + sseC(),
                body,
            )

        assertEquals(200, put.status, put.text)
        assertEquals(204, posted.status, posted.text)
        assertEquals(put.header("ETag"), s3.send("HEAD", "/$b/by-form.bin", headers = sseC()).header("ETag"))
    }

    @Test
    fun `a part outside the range of an encrypted object is InvalidPart`() {
        val b = "photos"
        s3.createBucket(b)
        val upload = s3.send("POST", "/$b/big.bin", query = "uploads", headers = sseC())
        val id = upload.text.substringAfter("<UploadId>").substringBefore("</UploadId>")
        val part = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val one = s3.send("PUT", "/$b/big.bin", query = "partNumber=1&uploadId=$id", headers = sseC(), body = part)
        val two =
            s3.send(
                "PUT",
                "/$b/big.bin",
                query = "partNumber=2&uploadId=$id",
                headers = sseC(),
                body = "хвост".toByteArray(),
            )
        val completion =
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>${one.header("ETag")}</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>${two.header("ETag")}</ETag></Part>" +
                "</CompleteMultipartUpload>"
        // The key headers on the completion too — that is how the suite sends them (`**get_args`),
        // and it is the only way its request differs from the obvious one.
        val done =
            s3.send("POST", "/$b/big.bin", query = "uploadId=$id", headers = sseC(), body = completion.toByteArray())
        assertEquals(200, done.status, done.text)

        // **With no key**, the way the suite does it: the part number is checked before the key is
        // required.
        val outOfRange = s3.send("GET", "/$b/big.bin", query = "partNumber=5")

        assertEquals(400, outOfRange.status, outOfRange.text)
        assertTrue("InvalidPart" in outOfRange.text, outOfRange.text)
    }
}
