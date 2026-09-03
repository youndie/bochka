package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.s3.BucketPolicy
import io.github.youndie.bochka.s3.S3Router
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every condition key a policy may name is a key the server can answer (M-201в).
 *
 * The rule the milestone states is "do not accept what you do not enforce", and it is enforced in
 * one direction only: a key outside [BucketPolicy.KNOWN_CONDITION_KEYS] is refused by name at
 * `PutBucketPolicy`. The other direction had nothing. The accepting list lives in `bochka-s3` and
 * the answering `when` lives in `bochka-app`, so a key added to the first without a branch in the
 * second is accepted, stored, and then compares against **nothing** — a condition that is silently
 * false, which is a policy that looks stricter than it is or one that never grants what it says.
 *
 * Hence a guard of the same shape as the one over routes: a table of how a request carries each
 * key, checked against the published set in both directions, so a new key fails this test by being
 * absent from the table rather than passing by not being in it.
 */
class PolicyConditionKeysTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    /**
     * The two keys no ordinary request carries, and the reason they are not a hole.
     *
     * They belong to a delivery this server makes on a bucket's behalf, so their values come from
     * the log delivery rather than from the request that triggered it, and `policyKeys` answers
     * them with null on purpose. What covers them instead is [BucketLoggingTest], by name: the
     * delivery policy is conditioned on `aws:SourceArn` in most of its tests and on
     * `aws:SourceAccount` in `a delivery policy may name the account instead of the bucket`.
     */
    private val answeredByTheLogDelivery = setOf("aws:SourceArn", "aws:SourceAccount")

    /** Every other known key, and the value the request below carries for it. */
    private val carried =
        mapOf(
            "s3:prefix" to "photos/",
            "s3:delimiter" to "/",
            "s3:max-keys" to "10",
            "s3:VersionId" to "v-1",
            "s3:x-amz-acl" to "public-read",
            "s3:x-amz-copy-source" to "/other/a.txt",
            "s3:x-amz-metadata-directive" to "REPLACE",
            "s3:x-amz-server-side-encryption" to "AES256",
            "s3:x-amz-server-side-encryption-aws-kms-key-id" to "some-key",
            "s3:x-amz-server-side-encryption-customer-algorithm" to "AES256",
            "s3:x-amz-storage-class" to "STANDARD",
            "aws:Referer" to "https://example.test/",
            "aws:UserAgent" to "some-client/1.0",
        )

    private val head =
        HttpRequestParser.Head(
            method = "GET",
            target = "/photos/a.txt?prefix=photos%2F&delimiter=%2F&max-keys=10&versionId=v-1",
            version = "HTTP/1.1",
            headers =
                listOf(
                    "host" to "127.0.0.1",
                    "x-amz-acl" to "public-read",
                    "x-amz-copy-source" to "/other/a.txt",
                    "x-amz-metadata-directive" to "REPLACE",
                    "x-amz-server-side-encryption" to "AES256",
                    "x-amz-server-side-encryption-aws-kms-key-id" to "some-key",
                    "x-amz-server-side-encryption-customer-algorithm" to "AES256",
                    "x-amz-storage-class" to "STANDARD",
                    "referer" to "https://example.test/",
                    "user-agent" to "some-client/1.0",
                    "x-amz-tagging" to "wanted=fresh",
                ),
        )

    @Test
    fun `every key a policy may name is answered from the request`() =
        runTest {
            s3.store.createBucket("photos")
            s3.store.put(
                "photos",
                ObjectKey.of("a.txt"),
                Metadata(tags = mapOf("colour" to "green")),
            ) { out -> out.write("hello".toByteArray(), 0, 5) }

            val keys =
                s3.handler.policyKeys(
                    head,
                    S3Router.Route.GetObject("photos", ObjectKey.of("a.txt")),
                    "photos",
                )

            for ((key, value) in carried) {
                assertEquals(value, keys(key), "the policy language accepts $key and nothing answers it")
            }
            // The two prefixed forms, which are matched by prefix rather than by equality and so
            // cannot be listed one by one.
            assertEquals("green", keys("s3:ExistingObjectTag/colour"))
            assertEquals("fresh", keys("s3:RequestObjectTag/wanted"))

            // And the shape of an honest absence: a tag the object does not carry is null rather
            // than the empty string, or `StringNotEquals` on it would be true of everything.
            assertNull(keys("s3:ExistingObjectTag/missing"))
        }

    @Test
    fun `the table above covers every key the policy language accepts`() {
        assertEquals(
            emptySet(),
            BucketPolicy.KNOWN_CONDITION_KEYS - carried.keys - answeredByTheLogDelivery,
            "a condition key was added to the policy language and nothing here answers it",
        )
        assertEquals(
            emptySet(),
            carried.keys - BucketPolicy.KNOWN_CONDITION_KEYS,
            "this names a condition key the policy language no longer accepts",
        )
        assertEquals(
            emptySet(),
            answeredByTheLogDelivery - BucketPolicy.KNOWN_CONDITION_KEYS,
            "an exemption names a condition key the policy language no longer accepts",
        )
    }
}
