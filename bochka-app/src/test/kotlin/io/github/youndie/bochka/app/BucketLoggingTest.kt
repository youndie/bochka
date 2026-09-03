package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * `?logging`: the configuration, and who may set it (M-202).
 *
 * Shapes are `docs/spec/s3-service-2.json`: `BucketLoggingStatus` (`:2183`), `LoggingEnabled`
 * (`:9023`), `TargetObjectKeyFormat` (`:13342`). Behaviour is `test_put_bucket_logging:15528`,
 * `…_errors:16526`, `…_permissions:16692` and `test_rm_bucket_logging`.
 *
 * **Records are not delivered, and that is a decision rather than a gap** — 33 of the 39 failing
 * cases in this family are `fails_on_aws` and pin RGW's journal, not S3's behaviour.
 */
class BucketLoggingTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    /** What the log bucket has to say before it will be written to. */
    private fun deliveryPolicy(
        logBucket: String,
        source: String,
        prefix: String = "log/",
    ) = """{"Version": "2012-10-17", "Statement": [{"Sid": "S3ServerAccessLogsPolicy", """ +
        """"Effect": "Allow", "Principal": {"Service": "logging.s3.amazonaws.com"}, """ +
        """"Action": ["s3:PutObject"], "Resource": "arn:aws:s3:::$logBucket/$prefix", """ +
        """"Condition": {"ArnLike": {"aws:SourceArn": "arn:aws:s3:::$source"}}}]}"""

    private fun status(
        target: String,
        prefix: String = "log/",
    ) = "<BucketLoggingStatus><LoggingEnabled><TargetBucket>$target</TargetBucket>" +
        "<TargetPrefix>$prefix</TargetPrefix></LoggingEnabled></BucketLoggingStatus>"

    private fun allow(
        logBucket: String,
        source: String,
    ) = assertEquals(
        204,
        s3.send("PUT", "/$logBucket", query = "policy", body = deliveryPolicy(logBucket, source).toByteArray()).status,
    )

    @Test
    fun `a bucket nobody configured answers an empty status`() {
        s3.createBucket("photos")

        val answer = s3.send("GET", "/photos", query = "logging")

        assertEquals(200, answer.status, answer.text)
        assertContains(answer.text, "BucketLoggingStatus")
        assertEquals(false, answer.text.contains("LoggingEnabled"), answer.text)
    }

    @Test
    fun `the configuration comes back with the key format it did not name`() {
        // `test_put_bucket_logging` sets the minimal configuration and compares the read-back with
        // {'SimplePrefix': {}} added: the default is a value, not an absence.
        s3.createBucket("photos")
        s3.createBucket("logs")
        allow("logs", "photos")

        assertEquals(200, s3.send("PUT", "/photos", query = "logging", body = status("logs").toByteArray()).status)

        val answer = s3.send("GET", "/photos", query = "logging")
        assertContains(answer.text, "<TargetBucket>logs</TargetBucket>")
        assertContains(answer.text, "<TargetPrefix>log/</TargetPrefix>")
        assertContains(answer.text, "<SimplePrefix></SimplePrefix>")
    }

    @Test
    fun `the empty TargetGrants a client writes anyway is not a refusal`() {
        s3.createBucket("photos")
        s3.createBucket("logs")
        allow("logs", "photos")
        val withEmptyGrants =
            "<BucketLoggingStatus><LoggingEnabled><TargetBucket>logs</TargetBucket>" +
                "<TargetGrants></TargetGrants><TargetPrefix>log/</TargetPrefix>" +
                "</LoggingEnabled></BucketLoggingStatus>"

        val answer = s3.send("PUT", "/photos", query = "logging", body = withEmptyGrants.toByteArray())

        assertEquals(200, answer.status, answer.text)
    }

    @Test
    fun `a self-closed TargetGrants is not a refusal either`() {
        s3.createBucket("photos")
        s3.createBucket("logs")
        allow("logs", "photos")
        val selfClosed =
            "<BucketLoggingStatus><LoggingEnabled><TargetBucket>logs</TargetBucket>" +
                "<TargetGrants/><TargetPrefix>log/</TargetPrefix>" +
                "</LoggingEnabled></BucketLoggingStatus>"

        val answer = s3.send("PUT", "/photos", query = "logging", body = selfClosed.toByteArray())

        assertEquals(200, answer.status, answer.text)
    }

    @Test
    fun `an empty status is how logging is switched off`() {
        s3.createBucket("photos")
        s3.createBucket("logs")
        allow("logs", "photos")
        s3.send("PUT", "/photos", query = "logging", body = status("logs").toByteArray())

        val off = "<BucketLoggingStatus></BucketLoggingStatus>"
        assertEquals(200, s3.send("PUT", "/photos", query = "logging", body = off.toByteArray()).status)

        assertEquals(false, s3.send("GET", "/photos", query = "logging").text.contains("LoggingEnabled"))
    }

    @Test
    fun `a missing source bucket and a missing target bucket answer differently`() {
        s3.createBucket("photos")
        s3.createBucket("logs")
        allow("logs", "photos")

        val noSource = s3.send("PUT", "/nowhere", query = "logging", body = status("logs").toByteArray())
        assertEquals(404, noSource.status, noSource.text)
        assertContains(noSource.text, "NoSuchBucket")

        // NoSuchKey for the target, which reads as a mistake and is what the suite asserts.
        val noTarget = s3.send("PUT", "/photos", query = "logging", body = status("elsewhere").toByteArray())
        assertEquals(404, noTarget.status, noTarget.text)
        assertContains(noTarget.text, "NoSuchKey")
    }

    @Test
    fun `a target that logs somewhere itself is refused`() {
        s3.createBucket("photos")
        s3.createBucket("logs")
        s3.createBucket("meta")
        allow("meta", "logs")
        assertEquals(200, s3.send("PUT", "/logs", query = "logging", body = status("meta").toByteArray()).status)
        allow("logs", "photos")

        val answer = s3.send("PUT", "/photos", query = "logging", body = status("logs").toByteArray())

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "InvalidArgument")
    }

    @Test
    fun `a target bucket that never agreed to receive refuses the configuration`() {
        s3.createBucket("photos")
        s3.createBucket("logs")

        val answer = s3.send("PUT", "/photos", query = "logging", body = status("logs").toByteArray())

        assertEquals(403, answer.status, answer.text)
        assertContains(answer.text, "AccessDenied")
    }

    @Test
    fun `the policy has to name this source, not just any`() {
        s3.createBucket("photos")
        s3.createBucket("other")
        s3.createBucket("logs")
        allow("logs", "other")

        assertEquals(403, s3.send("PUT", "/photos", query = "logging", body = status("logs").toByteArray()).status)
        assertEquals(200, s3.send("PUT", "/other", query = "logging", body = status("logs").toByteArray()).status)
    }

    @Test
    fun `a delivery policy may name the account instead of the bucket`() {
        // `aws:SourceAccount` is the second of the two condition keys whose value comes from the
        // delivery rather than from a request, and until now only its sibling `aws:SourceArn` had
        // ever been seen working. A key that is accepted by `PutBucketPolicy` and answered by
        // nothing compares against null, which makes the condition silently false — so the pair is
        // asserted here, both halves: the right account admits the configuration and a wrong one
        // does not.
        s3.createBucket("photos")
        s3.createBucket("logs")

        val byAccount = { account: String ->
            """{"Version": "2012-10-17", "Statement": [{"Sid": "S3ServerAccessLogsPolicy", """ +
                """"Effect": "Allow", "Principal": {"Service": "logging.s3.amazonaws.com"}, """ +
                """"Action": ["s3:PutObject"], "Resource": "arn:aws:s3:::logs/log/", """ +
                """"Condition": {"StringEquals": {"aws:SourceAccount": "$account"}}}]}"""
        }

        assertEquals(
            204,
            s3.send("PUT", "/logs", query = "policy", body = byAccount("somebody-else").toByteArray()).status,
        )
        assertEquals(
            403,
            s3.send("PUT", "/photos", query = "logging", body = status("logs").toByteArray()).status,
            "the delivery went to a bucket whose policy names another account",
        )

        assertEquals(
            204,
            s3.send("PUT", "/logs", query = "policy", body = byAccount(S3Fixture.ACCESS_KEY).toByteArray()).status,
        )
        assertEquals(
            200,
            s3.send("PUT", "/photos", query = "logging", body = status("logs").toByteArray()).status,
        )
    }

    /** `MalformedXML` rather than `InvalidArgument`, which is what the suite asks for. */
    @Test
    fun `a partition date source outside the two names is refused`() {
        s3.createBucket("photos")
        s3.createBucket("logs")
        allow("logs", "photos")
        val bad =
            "<BucketLoggingStatus><LoggingEnabled><TargetBucket>logs</TargetBucket>" +
                "<TargetPrefix>log/</TargetPrefix><TargetObjectKeyFormat><PartitionedPrefix>" +
                "<PartitionDateSource>kaboom</PartitionDateSource></PartitionedPrefix>" +
                "</TargetObjectKeyFormat></LoggingEnabled></BucketLoggingStatus>"

        val answer = s3.send("PUT", "/photos", query = "logging", body = bad.toByteArray())

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "MalformedXML")
    }

    @Test
    fun `a stranger does not set the logging of somebody else's bucket`() {
        s3.createBucket("photos")
        s3.createBucket("logs")
        allow("logs", "photos")

        val answer = s3.send("PUT", "/photos", query = "logging", body = status("logs").toByteArray(), asOther = true)

        assertEquals(403, answer.status, answer.text)
    }
}
