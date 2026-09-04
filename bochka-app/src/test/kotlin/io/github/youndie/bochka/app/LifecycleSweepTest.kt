package io.github.youndie.bochka.app

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The sweep that actually deletes.
 *
 * This file is what the rules were written for: a configuration the server stores and hands back
 * but does not apply is the `PutBucketPolicy` of the "what not to do" list, and the client finds
 * out about it from a storage bill rather than from an error.
 *
 * **Not one `sleep`.** A rule's "day" is a setting; the test sets it to a millisecond and calls the
 * sweep by hand, while the server calls the same sweep from a background thread. A test that waits
 * for time is either slow or flaky, and usually both.
 */
class LifecycleSweepTest {
    @Test
    fun `what the rule covers expires, and nothing else does`() {
        instant { s3 ->
            s3.createBucket("photos")
            for (key in listOf("expire1/foo", "expire1/bar", "keep2/foo", "expire3/foo")) {
                s3.put("photos", key, "x")
            }
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>"))

            val report = s3.sweepLifecycle(s3.later())

            assertEquals(2, report.objects, report.toString())
            assertEquals(404, s3.get("photos", "expire1/foo").status)
            assertEquals(404, s3.get("photos", "expire1/bar").status)
            assertEquals(200, s3.get("photos", "keep2/foo").status)
            assertEquals(200, s3.get("photos", "expire3/foo").status)
        }
    }

    @Test
    fun `a disabled rule does nothing`() {
        instant { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "expire1/foo", "x")
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>", status = "Disabled"))

            assertTrue(s3.sweepLifecycle(s3.later()).empty)
            assertEquals(200, s3.get("photos", "expire1/foo").status)
        }
    }

    @Test
    fun `a term that has not come does not arrive because the sweep was called`() {
        // The other side of it: with a "day" of an hour, a rule saying "after one day" does not fire
        // however often the sweep runs. Without this test the previous one would only have shown
        // that the sweep deletes something.
        S3Fixture(lifecycleDay = Duration.ofHours(1)).use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "expire1/foo", "x")
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>"))

            assertTrue(s3.sweepLifecycle(s3.later()).empty)
            assertEquals(200, s3.get("photos", "expire1/foo").status)
        }
    }

    @Test
    fun `in a versioning bucket a term lays a tombstone rather than erasing the version`() {
        // A term means "treat as deleted", not "erase". The version stays under the tombstone and
        // is reachable by name, exactly as after an ordinary `DELETE`.
        instant { s3 ->
            s3.createBucket("photos")
            s3.versioned("photos")
            s3.put("photos", "expire1/foo", "x")
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>"))

            assertEquals(1, s3.sweepLifecycle(s3.later()).objects)

            val read = s3.get("photos", "expire1/foo")
            assertEquals(404, read.status)
            assertEquals("true", read.header("x-amz-delete-marker"))
            val versions = s3.send("GET", "/photos", query = "versions").text
            assertEquals(1, Regex("<DeleteMarker>").findAll(versions).count(), versions)
            assertEquals(1, Regex("<Version>").findAll(versions).count(), versions)
        }
    }

    @Test
    fun `noncurrent versions expire and the current one stays`() {
        instant { s3 ->
            s3.createBucket("photos")
            s3.versioned("photos")
            repeat(4) { s3.put("photos", "myobject_", "v$it") }
            s3.rules(
                rule(
                    "rule1",
                    "",
                    "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>" +
                        "</NoncurrentVersionExpiration>",
                ),
            )

            val report = s3.sweepLifecycle(s3.later())

            assertEquals(3, report.versions, report.toString())
            assertEquals(0, report.objects, report.toString())
            assertEquals("v3", s3.get("photos", "myobject_").text)
            assertEquals(1, Regex("<Version>").findAll(s3.send("GET", "/photos", query = "versions").text).count())
        }
    }

    @Test
    fun `NewerNoncurrentVersions keeps the number of recent ones it names`() {
        // Counted from the current one down: with ten versions and `NewerNoncurrentVersions: 5` the
        // current one and the five below it stay while the bottom four go —
        // `test_lifecycle_expiration_newer_noncurrent:8854`.
        instant { s3 ->
            s3.createBucket("photos")
            s3.versioned("photos")
            repeat(10) { s3.put("photos", "myobject_", "v$it") }
            s3.rules(
                rule(
                    "rule1",
                    "",
                    "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>" +
                        "<NewerNoncurrentVersions>5</NewerNoncurrentVersions></NoncurrentVersionExpiration>",
                ),
            )

            assertEquals(4, s3.sweepLifecycle(s3.later()).versions)

            val versions = s3.send("GET", "/photos", query = "versions").text
            assertEquals(6, Regex("<Version>").findAll(versions).count(), versions)
        }
    }

    @Test
    fun `a tombstone goes only once nothing is left under it`() {
        // The order within one sweep: noncurrent versions first, the tombstone after. While a
        // version remains under it, it is not orphaned, and the rule about orphaned tombstones does
        // not apply — `test_lifecycle_deletemarker_expiration:9361` checks this very sequence.
        instant { s3 ->
            s3.createBucket("photos")
            s3.versioned("photos")
            s3.put("photos", "test1/a", "x")
            s3.send("DELETE", "/photos/test1/a")

            s3.rules(
                rule(
                    "rule1",
                    "test1/",
                    "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>",
                ),
            )
            // A version still lies under the tombstone, so there is nothing to touch.
            assertTrue(s3.sweepLifecycle(s3.later()).empty)
            assertEquals(2, Regex("<(Version|DeleteMarker)>").findAll(versions(s3)).count(), versions(s3))

            // A rule about noncurrent versions appears, and one sweep takes both: the version
            // first, then the tombstone it orphaned.
            s3.rules(
                rule(
                    "rule1",
                    "test1/",
                    "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>" +
                        "</NoncurrentVersionExpiration>" +
                        "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>",
                ),
            )
            val report = s3.sweepLifecycle(s3.later())

            assertEquals(1, report.versions, report.toString())
            assertEquals(1, report.markers, report.toString())
            assertTrue("<Version>" !in versions(s3), versions(s3))
            assertTrue("<DeleteMarker>" !in versions(s3), versions(s3))
        }
    }

    @Test
    fun `size is compared, and a rule about size leaves its neighbour alone`() {
        instant { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "myobject_small", "a".repeat(1000))
            s3.put("photos", "myobject_big", "b".repeat(3000))
            s3.rules(
                "<LifecycleConfiguration><Rule><ID>object_gt1</ID>" +
                    "<Expiration><Days>1</Days></Expiration>" +
                    "<Filter><Prefix /><ObjectSizeGreaterThan>2000</ObjectSizeGreaterThan></Filter>" +
                    "<Status>Enabled</Status></Rule></LifecycleConfiguration>",
            )

            assertEquals(1, s3.sweepLifecycle(s3.later()).objects)
            assertEquals(200, s3.get("photos", "myobject_small").status)
            assertEquals(404, s3.get("photos", "myobject_big").status)
        }
    }

    @Test
    fun `an abandoned multipart upload is aborted by prefix`() {
        instant { s3 ->
            s3.createBucket("photos")
            s3.send("POST", "/photos/test1/a", query = "uploads")
            s3.send("POST", "/photos/test2/b", query = "uploads")
            s3.rules(
                rule(
                    "rule1",
                    "test1/",
                    "<AbortIncompleteMultipartUpload><DaysAfterInitiation>1</DaysAfterInitiation>" +
                        "</AbortIncompleteMultipartUpload>",
                ),
            )

            assertEquals(1, s3.sweepLifecycle(s3.later()).uploads)

            val listed = s3.send("GET", "/photos", query = "uploads").text
            assertTrue("test2/b" in listed, listed)
            assertTrue("test1/a" !in listed, listed)
        }
    }

    @Test
    fun `a rule with a size condition does not abort an upload that has written nothing`() {
        // An upload that has begun has neither bytes nor tags. `ObjectSizeLessThan: 2000` would
        // match it as "zero is less than two thousand" — that is, the upload would be aborted on a
        // condition nobody ever evaluated about it. A rule naming a size or a tag does not apply to
        // uploads at all.
        instant { s3 ->
            s3.createBucket("photos")
            s3.send("POST", "/photos/test1/a", query = "uploads")
            s3.rules(
                "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                    "<AbortIncompleteMultipartUpload><DaysAfterInitiation>1</DaysAfterInitiation>" +
                    "</AbortIncompleteMultipartUpload>" +
                    "<Filter><Prefix>test1/</Prefix><ObjectSizeLessThan>2000</ObjectSizeLessThan></Filter>" +
                    "<Status>Enabled</Status></Rule></LifecycleConfiguration>",
            )

            assertEquals(0, s3.sweepLifecycle(s3.later()).uploads)
            assertTrue("test1/a" in s3.send("GET", "/photos", query = "uploads").text)
        }
    }

    @Test
    fun `a version under a legal hold outlives its term`() {
        // A negative test, and the important one here: a lock is a promise that outranks a term. A
        // rule that took a held version away would be the worst way to lose data — quiet, delayed,
        // and recorded in the configuration as something the owner asked for.
        instant { s3 ->
            s3.send("PUT", "/photos", headers = listOf("x-amz-bucket-object-lock-enabled" to "true"))
            s3.send(
                "PUT",
                "/photos",
                query = "object-lock",
                body =
                    (
                        "<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled>" +
                            "</ObjectLockConfiguration>"
                    ).toByteArray(),
            )
            // The hold goes on the **old** version, by name: without a `versionId` it would land on
            // the current one, which this rule does not touch anyway — the test would have been
            // green having checked nothing.
            val held = s3.put("photos", "held/a", "first").header("x-amz-version-id")
            s3.put("photos", "held/a", "second")
            val hold =
                s3.send(
                    "PUT",
                    "/photos/held/a",
                    query = "legal-hold&versionId=$held",
                    body = "<LegalHold><Status>ON</Status></LegalHold>".toByteArray(),
                )
            assertEquals(200, hold.status, hold.text)
            s3.rules(
                rule(
                    "rule1",
                    "held/",
                    "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>" +
                        "</NoncurrentVersionExpiration>",
                ),
            )

            // The sweep ran and the old version, being held, stayed. And with it: a refusal on one
            // version does not stop the sweep, it simply leaves that one alone.
            s3.sweepLifecycle(s3.later())

            assertEquals(200, s3.get("photos", "held/a").status)
            assertEquals(2, Regex("<Version>").findAll(versions(s3)).count(), versions(s3))
        }
    }

    private fun versions(s3: S3Fixture) = s3.send("GET", "/photos", query = "versions").text

    /**
     * A sweep that broke has to say so rather than report a zero (M-207).
     *
     * `runCatching { … }.fold({ deleted }, { did not delete })` turned **any** exception into
     * "there was nothing to delete", and a genuinely broken sweep became indistinguishable from one
     * with no work to do. The difference is visible only from outside: objects with a term stop
     * disappearing while the log reports every round that all is well.
     *
     * The failure here is arranged surgically: the store's journal is closed. Reading the index
     * lives in memory and keeps working, so what fails is exactly the write — the place
     * `runCatching` stood.
     */
    @Test
    fun `a broken sweep fails rather than reporting a zero`() {
        instant { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "expire/foo", "x")
            s3.rules(rule("rule1", "expire/", "<Expiration><Days>1</Days></Expiration>"))
            s3.store.close()

            val thrown = assertFails { s3.sweepLifecycle(s3.later()) }

            assertTrue(thrown !is AssertionError, "the sweep reported instead of failing: $thrown")
        }
    }

    /**
     * Which clock the sweep uses when nobody hands it one.
     *
     * Everywhere else in this file the instant is stated, and a stated instant is exactly what
     * hides the answer: the default is never exercised. The server does not state one -- the
     * background thread calls `sweep()` bare -- so what the default reads is a property of the
     * shipped product and of nothing in this file.
     *
     * The clock here is a quarter of a century from the machine's, and it is the store's. Nothing
     * has aged: the object was written and swept at the same instant. Read the JVM's clock instead
     * and the same object was written in 2001, which is a thousand "days" many times over, and the
     * rule fires on an object nobody had time to age.
     */
    @Test
    fun `a sweep nobody gave a time to reads the store's clock, not the machine's`() {
        S3Fixture(lifecycleDay = Duration.ofMillis(1), clock = { frozen }).use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "expire1/foo", "x")
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>"))

            assertTrue(s3.sweepLifecycle().empty, "the sweep aged an object against a clock the store does not use")
        }
    }

    /**
     * A fixture whose "day" lasts a millisecond: everything with a term reaches it at once.
     *
     * Not `Duration.ZERO`: a zero would mean "expired at the moment it was written", and the test
     * would stop telling a rule that fired from a rule applied to anything at all.
     */
    private fun instant(body: (S3Fixture) -> Unit) = S3Fixture(lifecycleDay = Duration.ofMillis(1)).use(body)

    /** Far enough from any machine's clock that no plausible skew could produce it. */
    private val frozen: Instant = Instant.parse("2001-02-03T04:05:06Z")

    /**
     * The instant the sweep judges a term against is stated rather than "now".
     *
     * A "day" here lasts a millisecond, so under the default clock expiry is decided by how much
     * time passed between writing the object and sweeping. That is a property of the machine rather
     * than of the code: `LifecycleSweepTest` failed in CI on a tree that had been green five runs
     * in a row here. One second forward is a thousand "days", so the term has certainly passed for
     * everything that has one, and no "the rule did not fire" assertion is weakened by it: what
     * fires or does not fire there is the **rule**, not the clock.
     */
    private fun S3Fixture.later(): Instant = store.clock().plusSeconds(1)

    private fun S3Fixture.rules(document: String) {
        val answer = send("PUT", "/photos", query = "lifecycle", body = document.toByteArray())
        assertEquals(200, answer.status, answer.text)
    }

    private fun S3Fixture.versioned(bucket: String) {
        send(
            "PUT",
            "/$bucket",
            query = "versioning",
            body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
        )
    }

    private fun rule(
        id: String,
        prefix: String,
        what: String,
        status: String = "Enabled",
    ) = "<LifecycleConfiguration><Rule><ID>$id</ID>$what" +
        "<Prefix>$prefix</Prefix><Status>$status</Status></Rule></LifecycleConfiguration>"
}
