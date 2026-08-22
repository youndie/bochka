package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PublicAccessBlockConfiguration`: the document, and what "public" means to it (M-227).
 *
 * The shape is `docs/spec/s3-service-2.json`, `PublicAccessBlockConfiguration`: four members of
 * type `Setting`, none of them required. The wire form is the one botocore writes — a member per
 * flag, `true` or `false` as text — and the read-back is what the suite compares against, four
 * assertions per case (`test_put_public_block:14277`).
 */
class PublicAccessBlockTest {
    private fun document(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?><PublicAccessBlockConfiguration>$body</PublicAccessBlockConfiguration>"""
            .toByteArray()

    @Test
    fun `the four settings come back as they were written`() {
        val decoded =
            PublicAccessBlock.decode(
                document(
                    "<BlockPublicAcls>true</BlockPublicAcls>" +
                        "<IgnorePublicAcls>true</IgnorePublicAcls>" +
                        "<BlockPublicPolicy>true</BlockPublicPolicy>" +
                        "<RestrictPublicBuckets>false</RestrictPublicBuckets>",
                ),
            )

        assertEquals(
            PublicAccessBlock.Configuration(
                blockPublicAcls = true,
                ignorePublicAcls = true,
                blockPublicPolicy = true,
                restrictPublicBuckets = false,
            ),
            decoded,
        )
    }

    @Test
    fun `a member nobody sent is off`() {
        // `Setting` has no default in the model, and an absent restriction is no restriction.
        val decoded = PublicAccessBlock.decode(document("<BlockPublicAcls>true</BlockPublicAcls>"))

        assertTrue(decoded.blockPublicAcls)
        assertFalse(decoded.ignorePublicAcls)
        assertFalse(decoded.blockPublicPolicy)
        assertFalse(decoded.restrictPublicBuckets)
    }

    @Test
    fun `the answer carries all four members even when three are off`() {
        // botocore turns an absent member into an absent **key**, so a document that leaves out
        // what is off answers `KeyError` to three of the four assertions in `test_put_public_block`.
        val written = String(PublicAccessBlock.encode(PublicAccessBlock.Configuration(blockPublicAcls = true)))

        assertContains(written, "<BlockPublicAcls>true</BlockPublicAcls>")
        assertContains(written, "<IgnorePublicAcls>false</IgnorePublicAcls>")
        assertContains(written, "<BlockPublicPolicy>false</BlockPublicPolicy>")
        assertContains(written, "<RestrictPublicBuckets>false</RestrictPublicBuckets>")
    }

    @Test
    fun `a member this server does not know is refused rather than skipped`() {
        // The one XML reader here that refuses the unknown, and the reason is that every member of
        // this shape is a restriction: skipping one means the caller asked for a lock they did not
        // get, which is the failure this repository refuses everywhere else.
        val refused =
            assertFailsWith<PublicAccessBlock.Refused> {
                PublicAccessBlock.decode(document("<BlockPublicEverything>true</BlockPublicEverything>"))
            }

        assertContains(refused.message, "BlockPublicEverything")
    }

    @Test
    fun `a setting that is neither true nor false is refused naming the text`() {
        val refused =
            assertFailsWith<PublicAccessBlock.Refused> {
                PublicAccessBlock.decode(document("<BlockPublicAcls>yes</BlockPublicAcls>"))
            }

        assertContains(refused.message, "yes")
    }

    // --- what makes a policy public (M-227, and M-228 will read the same function) --------------

    private fun policy(
        principal: String,
        effect: String = "Allow",
        resource: String = """["arn:aws:s3:::photos/*"]""",
    ) = BucketPolicy.decode(
        """{"Version": "2012-10-17", "Statement": [{"Action": "s3:GetObject", "Principal": $principal, """ +
            """"Effect": "$effect", "Resource": $resource}]}""",
    )

    @Test
    fun `an Allow to a star principal is public`() {
        assertTrue(PublicAccessBlock.isPublic(policy(""""*"""")))
        assertTrue(PublicAccessBlock.isPublic(policy("""{"AWS": "*"}""")))
    }

    @Test
    fun `an Allow to one account is not`() {
        // `test_block_public_policy_with_principal:14357` is exactly this document, and it requires
        // the same bucket that refuses the one above to accept this one.
        assertFalse(PublicAccessBlock.isPublic(policy("""{"AWS": "arn:aws:iam::s3tenant1:root"}""")))
    }

    @Test
    fun `a Deny to everybody is not public`() {
        // The flags are about a policy handing something out; a policy that refuses hands out
        // nothing, and reading a `Deny` as public would make `BlockPublicPolicy` refuse the
        // strictest document anyone can write.
        assertFalse(PublicAccessBlock.isPublic(policy(""""*"""", effect = "Deny")))
    }

    @Test
    fun `a statement with no Resource is not public because it matches nothing`() {
        val text =
            """{"Version": "2012-10-17", "Statement": [{"Action": "s3:GetObject", """ +
                """"Principal": "*", "Effect": "Allow"}]}"""

        assertFalse(PublicAccessBlock.isPublic(BucketPolicy.decode(text)))
    }
}
