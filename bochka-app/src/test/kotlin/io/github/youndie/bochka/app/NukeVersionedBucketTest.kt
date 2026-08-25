package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Clearing out a versioning bucket — what the foreign suite does **after every** test.
 *
 * `nuke_prefixed_buckets` lists the versions, deletes each by name and removes the bucket; if
 * anything is left after that, deleting the bucket answers `409` and the fixture goes round again.
 * From outside, that loop does not look like an error but like a hung server — 24 cases ran into
 * the sixty-second timeout exactly that way.
 */
class NukeVersionedBucketTest {
    @Test
    fun `a versioned bucket can be emptied by listing versions and deleting each by id`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
            )
            for (i in 0 until 3) s3.put("photos", "k$i.txt", "тело $i")
            // The way the fixture does it: an ordinary delete first, which lays tombstones.
            for (i in 0 until 3) s3.send("DELETE", "/photos/k$i.txt")

            var rounds = 0
            while (rounds++ < 5) {
                val body = s3.send("GET", "/photos", query = "versions").text
                val ids =
                    Regex("<Key>([^<]*)</Key>\\s*<VersionId>([^<]*)</VersionId>")
                        .findAll(body)
                        .map { it.groupValues[1] to it.groupValues[2] }
                        .toList()
                if (ids.isEmpty()) break
                for ((key, version) in ids) s3.send("DELETE", "/photos/$key", query = "versionId=$version")
            }

            val left = s3.send("GET", "/photos", query = "versions").text
            assertTrue("<Version>" !in left && "<DeleteMarker>" !in left, "what is left: $left")
            assertEquals(
                204,
                s3.send("DELETE", "/photos").status,
                "the bucket does not go — the fixture will loop round again",
            )
        }
    }

    @Test
    fun `more than one page of versions can be paged through and deleted`() {
        // `test_bucket_list_delimiter_not_skip_special:683` puts 1004 keys, and the cleanup after
        // it lists versions in pages. A test on three keys does not see that: the difference is
        // exactly that the second page resumes on a pair of markers, and a mistake there costs the
        // whole suite — the bucket does not empty, `DeleteBucket` answers `BucketNotEmpty`, and
        // every case after it fails in its own fixture.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            for (i in 0 until 1004) s3.put("photos", "k%04d.txt".format(i), "т")

            var keyMarker = ""
            var versionMarker = ""
            var deleted = 0
            var pages = 0
            while (pages++ < 10) {
                val query =
                    buildString {
                        append("versions")
                        if (keyMarker.isNotEmpty()) append("&key-marker=").append(keyMarker)
                        if (versionMarker.isNotEmpty()) append("&version-id-marker=").append(versionMarker)
                    }
                val body = s3.send("GET", "/photos", query = query).text
                val ids =
                    Regex("<Key>([^<]*)</Key>\\s*<VersionId>([^<]*)</VersionId>")
                        .findAll(body)
                        .map { it.groupValues[1] to it.groupValues[2] }
                        .toList()
                for ((key, version) in ids) {
                    s3.send("DELETE", "/photos/$key", query = "versionId=$version")
                    deleted++
                }
                if ("<IsTruncated>true</IsTruncated>" !in body) break
                keyMarker = body.substringAfter("<NextKeyMarker>").substringBefore("</NextKeyMarker>")
                versionMarker = body.substringAfter("<NextVersionIdMarker>").substringBefore("</NextVersionIdMarker>")
            }

            assertEquals(1004, deleted, "the cleanup has to see every version rather than the first page")
            assertEquals(
                204,
                s3.send("DELETE", "/photos").status,
                "the bucket does not empty — the fixture will stall",
            )
        }
    }

    @Test
    fun `one pass of the cleanup empties a versioned bucket`() {
        // The foreign suite's cleanup lists the versions **once** and deletes each by name. The
        // first version of this test went round up to five times and so would not have seen the
        // difference: if anything is left after one pass, `DeleteBucket` answers `BucketNotEmpty`
        // and the next case fails in its fixture — which is the mechanism by which one test takes
        // down a whole run.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
            )
            for (i in 0 until 3) s3.put("photos", "k$i.txt", "тело $i")

            val body = s3.send("GET", "/photos", query = "versions").text
            val ids =
                Regex("<Key>([^<]*)</Key>\\s*<VersionId>([^<]*)</VersionId>")
                    .findAll(body)
                    .map { it.groupValues[1] to it.groupValues[2] }
                    .toList()
            assertEquals(3, ids.size, "the version listing has to show all three: $body")
            for ((key, version) in ids) s3.send("DELETE", "/photos/$key", query = "versionId=$version")

            assertEquals(204, s3.send("DELETE", "/photos").status, "one pass has to be enough")
        }
    }
}
