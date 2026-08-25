package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `?versioning` — a bucket's versioning configuration (M-103).
 *
 * The important test here is about a bucket nobody configured. `s3-service-2.json` describes
 * `GetBucketVersioningOutput.Status` as an optional member, so an empty `VersioningConfiguration`
 * is an answer rather than a refusal. This repository has already paid for the opposite: a tool
 * read `NotImplemented` on `?versions` as "the server is broken" and took 837 cases down with it in
 * the foreign cleanup (M3).
 */
class VersioningConfigTest {
    @Test
    fun `a bucket nobody configured answers with an empty configuration`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer = s3.send("GET", "/photos", query = "versioning")

            assertEquals(200, answer.status, answer.text)
            assertTrue("VersioningConfiguration" in answer.text, answer.text)
            assertTrue("Status" !in answer.text, "no status, rather than a status of \"off\": ${answer.text}")
        }
    }

    @Test
    fun `Enabled is stored and read back`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val put =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "versioning",
                    body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
                )

            assertEquals(200, put.status, put.text)
            assertTrue("<Status>Enabled</Status>" in s3.send("GET", "/photos", query = "versioning").text)
        }
    }

    @Test
    fun `Suspended is not the same as never configured`() {
        // Two different answers, and the difference is not cosmetic: a suspended bucket can hold
        // versions made while it was enabled.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
            )

            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = "<VersioningConfiguration><Status>Suspended</Status></VersioningConfiguration>".toByteArray(),
            )

            assertTrue("<Status>Suspended</Status>" in s3.send("GET", "/photos", query = "versioning").text)
        }
    }

    @Test
    fun `Disabled is refused rather than accepted and ignored`() {
        // S3 cannot go back to "not configured" and neither can this. Accepting `Disabled` would
        // leave the client believing that versions had stopped being kept.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "versioning",
                    body = "<VersioningConfiguration><Status>Disabled</Status></VersioningConfiguration>".toByteArray(),
                )

            assertEquals(400, answer.status, answer.text)
        }
    }
}
