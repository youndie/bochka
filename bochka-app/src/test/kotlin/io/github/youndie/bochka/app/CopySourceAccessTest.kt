package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A copy reads its source, so the source's permission has to be asked (found in M29).
 *
 * Every other route is screened before the handler, by the route it arrived on. A copy is two
 * requests wearing one: it **writes** the destination, which the screen covers, and it **reads**
 * the source, which nothing covered — the handler went straight to the bytes. So a key that could
 * write anywhere it owns could read everything in the store by copying it home.
 *
 * Found while mapping routes to policy actions: `CopyObject` and `UploadPartCopy` were the two
 * routes exempted from that guard on the grounds that their siblings covered them. The siblings
 * cover the destination. Nothing covered the source, and the exemption is what made that invisible.
 */
class CopySourceAccessTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    @Test
    fun `a stranger cannot read a private object by copying it home`() {
        s3.createBucket("vault")
        s3.put("vault", "secret.txt", "the contents")
        assertEquals(403, s3.send("GET", "/vault/secret.txt", asOther = true).status, "the premise: it cannot be read")

        s3.send("PUT", "/mine", asOther = true)
        val answer =
            s3.send(
                "PUT",
                "/mine/stolen.txt",
                headers = listOf("x-amz-copy-source" to "/vault/secret.txt"),
                asOther = true,
            )

        assertEquals(403, answer.status, "copied a private object into its own bucket: ${answer.text}")
    }

    @Test
    fun `and cannot do it a part at a time either`() {
        s3.createBucket("vault")
        s3.put("vault", "secret.txt", "the contents")
        s3.send("PUT", "/mine", asOther = true)
        val started = s3.send("POST", "/mine/stolen.txt", query = "uploads", asOther = true)
        assertEquals(200, started.status, started.text)
        val uploadId = started.text.substringAfter("<UploadId>").substringBefore("</UploadId>")

        val answer =
            s3.send(
                "PUT",
                "/mine/stolen.txt",
                query = "partNumber=1&uploadId=$uploadId",
                headers = listOf("x-amz-copy-source" to "/vault/secret.txt"),
                asOther = true,
            )

        assertEquals(403, answer.status, "copied a private object part into its own upload: ${answer.text}")
    }

    @Test
    fun `a policy on the source bucket is what makes the copy legal`() {
        // `test_bucket_policy_upload_part_copy:12641`: the source bucket grants s3:GetObject on
        // `public/*` to everybody, and the copy then works — from `public/`, and only from there.
        s3.createBucket("vault")
        s3.put("vault", "public/open.txt", "open")
        s3.put("vault", "private/shut.txt", "shut")
        val grant =
            """{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": "*", """ +
                """"Action": "s3:GetObject", "Resource": "arn:aws:s3:::vault/public/*"}]}"""
        assertEquals(200, s3.send("PUT", "/vault", query = "policy", body = grant.toByteArray()).status)
        s3.send("PUT", "/mine", asOther = true)

        assertEquals(
            200,
            s3
                .send(
                    "PUT",
                    "/mine/copy.txt",
                    headers = listOf("x-amz-copy-source" to "/vault/public/open.txt"),
                    asOther = true,
                ).status,
        )
        assertEquals(
            403,
            s3
                .send(
                    "PUT",
                    "/mine/copy2.txt",
                    headers = listOf("x-amz-copy-source" to "/vault/private/shut.txt"),
                    asOther = true,
                ).status,
        )
    }
}
