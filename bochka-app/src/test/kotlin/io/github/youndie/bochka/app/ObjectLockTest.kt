package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Object lock and retention (M-109…M-111).
 *
 * The negative matters more here than the positive: a lock is worth exactly what it **stops** you
 * doing, while the suite far more often checks that what is allowed is allowed. So most of the
 * tests below are about refusals, and every one of them was seen red before the code existed.
 */
class ObjectLockTest {
    private fun versioning(status: String) =
        "<VersioningConfiguration><Status>$status</Status></VersioningConfiguration>".toByteArray()

    private fun S3Fixture.locked(bucket: String) {
        send("PUT", "/$bucket", headers = listOf("x-amz-bucket-object-lock-enabled" to "true"))
    }

    private fun retention(
        mode: String,
        until: String,
    ) = "<Retention><Mode>$mode</Mode><RetainUntilDate>$until</RetainUntilDate></Retention>".toByteArray()

    private val far = "2099-01-01T00:00:00Z"

    private fun lockConfig(rule: String = ""): ByteArray {
        val document =
            "<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled>$rule" +
                "</ObjectLockConfiguration>"
        return document.toByteArray()
    }

    @Test
    fun `a locked bucket versions whether it was asked to or not`() {
        // Retention on something that can be overwritten in place protects nothing: versioning
        // comes with the lock rather than as a separate call.
        S3Fixture().use { s3 ->
            s3.locked("photos")

            assertTrue("<Status>Enabled</Status>" in s3.send("GET", "/photos", query = "versioning").text)
        }
    }

    @Test
    fun `a bucket made without the lock refuses to configure one`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "object-lock",
                    body =
                        "<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>"
                            .toByteArray(),
                )

            assertEquals(409, answer.status, answer.text)
            assertTrue("InvalidBucketState" in answer.text, answer.text)
        }
    }

    @Test
    fun `a versioning bucket may take the lock after creation`() {
        // Creation is not the only door: the real precondition is versioning, and
        // `ObjectLockEnabledForBucket` was a special case of it.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = versioning("Enabled"),
            )

            val answer =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "object-lock",
                    body =
                        "<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>"
                            .toByteArray(),
                )

            assertEquals(200, answer.status, answer.text)
        }
    }

    @Test
    fun `a locked bucket cannot stop versioning`() {
        S3Fixture().use { s3 ->
            s3.locked("photos")

            val answer =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "versioning",
                    body = versioning("Suspended"),
                )

            assertEquals(409, answer.status, answer.text)
        }
    }

    @Test
    fun `a version under retention refuses to be deleted`() {
        S3Fixture().use { s3 ->
            s3.locked("photos")
            val version = s3.put("photos", "a.txt", "тело").header("x-amz-version-id")!!
            s3.send("PUT", "/photos/a.txt", query = "retention", body = retention("COMPLIANCE", far))

            val answer = s3.send("DELETE", "/photos/a.txt", query = "versionId=$version")

            assertEquals(403, answer.status, answer.text)
            assertEquals("тело", s3.get("photos", "a.txt").text)
        }
    }

    @Test
    fun `GOVERNANCE yields to a caller that says so, COMPLIANCE yields to nobody`() {
        // This is the difference between the modes, and it is not about strength but about who can
        // undo it. A promise its author can withdraw is not the promise a lock is put on for.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            val governed = s3.put("photos", "g.txt", "тело").header("x-amz-version-id")!!
            val complied = s3.put("photos", "c.txt", "тело").header("x-amz-version-id")!!
            s3.send("PUT", "/photos/g.txt", query = "retention", body = retention("GOVERNANCE", far))
            s3.send("PUT", "/photos/c.txt", query = "retention", body = retention("COMPLIANCE", far))
            val bypass = listOf("x-amz-bypass-governance-retention" to "true")

            assertEquals(
                204,
                s3.send("DELETE", "/photos/g.txt", query = "versionId=$governed", headers = bypass).status,
            )
            assertEquals(
                403,
                s3.send("DELETE", "/photos/c.txt", query = "versionId=$complied", headers = bypass).status,
            )
        }
    }

    @Test
    fun `shortening a retention is refused, extending it is not`() {
        S3Fixture().use { s3 ->
            s3.locked("photos")
            s3.put("photos", "a.txt", "тело")
            s3.send("PUT", "/photos/a.txt", query = "retention", body = retention("COMPLIANCE", "2098-01-01T00:00:00Z"))

            val shorter =
                s3.send(
                    "PUT",
                    "/photos/a.txt",
                    query = "retention",
                    body = retention("COMPLIANCE", "2097-01-01T00:00:00Z"),
                )
            val longer = s3.send("PUT", "/photos/a.txt", query = "retention", body = retention("COMPLIANCE", far))

            assertEquals(403, shorter.status, shorter.text)
            assertEquals(200, longer.status, longer.text)
        }
    }

    @Test
    fun `a legal hold blocks deletion on its own, and does not care about retention`() {
        S3Fixture().use { s3 ->
            s3.locked("photos")
            val version = s3.put("photos", "a.txt", "тело").header("x-amz-version-id")!!
            s3.send(
                "PUT",
                "/photos/a.txt",
                query = "legal-hold",
                body = "<LegalHold><Status>ON</Status></LegalHold>".toByteArray(),
            )

            val held = s3.send("DELETE", "/photos/a.txt", query = "versionId=$version")
            assertEquals(403, held.status, held.text)
            assertTrue("<Status>ON</Status>" in s3.send("GET", "/photos/a.txt", query = "legal-hold").text)

            s3.send(
                "PUT",
                "/photos/a.txt",
                query = "legal-hold",
                body = "<LegalHold><Status>OFF</Status></LegalHold>".toByteArray(),
            )
            assertEquals(204, s3.send("DELETE", "/photos/a.txt", query = "versionId=$version").status)
        }
    }

    @Test
    fun `an object sub-resource on a bucket without the lock is a bad request, not a bad bucket`() {
        // The same absence seen from two sides, with different codes: `409` on the bucket, `400` on
        // the object. The client fixes different things — one recreates the bucket, the other stops
        // asking.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "a.txt", "тело")

            val answer = s3.send("GET", "/photos/a.txt", query = "retention")

            assertEquals(400, answer.status, answer.text)
            assertTrue("InvalidRequest" in answer.text, answer.text)
        }
    }

    @Test
    fun `the lock stated on the upload is on the object before anybody else can touch it`() {
        S3Fixture().use { s3 ->
            s3.locked("photos")

            s3.put(
                "photos",
                "a.txt",
                "тело",
                headers =
                    listOf(
                        "x-amz-object-lock-mode" to "COMPLIANCE",
                        "x-amz-object-lock-retain-until-date" to far,
                    ),
            )

            val read = s3.get("photos", "a.txt")
            assertEquals("COMPLIANCE", read.header("x-amz-object-lock-mode"))
            assertEquals(
                403,
                s3.send("DELETE", "/photos/a.txt", query = "versionId=${read.header("x-amz-version-id")}").status,
            )
        }
    }

    @Test
    fun `a period that parses and cannot be meant is its own refusal`() {
        S3Fixture().use { s3 ->
            s3.locked("photos")

            val answer =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "object-lock",
                    body =
                        (
                            "<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled><Rule>" +
                                "<DefaultRetention><Mode>GOVERNANCE</Mode><Days>0</Days></DefaultRetention>" +
                                "</Rule></ObjectLockConfiguration>"
                        ).toByteArray(),
                )

            assertEquals(400, answer.status, answer.text)
            assertTrue("InvalidRetentionPeriod" in answer.text, answer.text)
        }
    }

    @Test
    fun `the sequence test_object_lock_get_obj_metadata runs leaves nothing behind`() {
        // The case cleans up after itself: it clears the legal hold and deletes the version with a
        // GOVERNANCE bypass. If anything is left in the bucket after that, the foreign fixture runs
        // into a retention lasting until 2030 and takes down every case after it — 92 of them.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            s3.put("photos", "file1", "abc")
            s3.send(
                "PUT",
                "/photos/file1",
                query = "legal-hold",
                body = "<LegalHold><Status>ON</Status></LegalHold>".toByteArray(),
            )
            s3.send("PUT", "/photos/file1", query = "retention", body = retention("GOVERNANCE", "2030-01-01T00:00:00Z"))

            val head = s3.send("HEAD", "/photos/file1")
            assertEquals("GOVERNANCE", head.header("x-amz-object-lock-mode"))
            assertEquals("ON", head.header("x-amz-object-lock-legal-hold"))
            val version = head.header("x-amz-version-id")

            s3.send(
                "PUT",
                "/photos/file1",
                query = "legal-hold",
                body = "<LegalHold><Status>OFF</Status></LegalHold>".toByteArray(),
            )
            val removed =
                s3.send(
                    "DELETE",
                    "/photos/file1",
                    query = "versionId=$version",
                    headers = listOf("x-amz-bypass-governance-retention" to "true"),
                )

            assertEquals(204, removed.status, removed.text)
            val left = s3.send("GET", "/photos", query = "versions").text
            assertTrue("<Version>" !in left && "<DeleteMarker>" !in left, "what is left: $left")
        }
    }

    @Test
    fun `the batch delete the cleanup uses steps over GOVERNANCE when it says so`() {
        // `nuke_bucket` does not delete one at a time: it sends `POST ?delete` in batches of 128
        // with `BypassGovernanceRetention=True`. Cases that set a retention until 2030 and do not
        // clean up after themselves rely on exactly that path — if the bypass does not reach this
        // far, the bucket stays locked for years and the whole run goes down with it.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            val version = s3.put("photos", "a.txt", "тело").header("x-amz-version-id")!!
            s3.send("PUT", "/photos/a.txt", query = "retention", body = retention("GOVERNANCE", "2030-01-01T00:00:00Z"))

            val body =
                (
                    "<Delete><Quiet>true</Quiet><Object><Key>a.txt</Key>" +
                        "<VersionId>$version</VersionId></Object></Delete>"
                ).toByteArray()
            // A batch delete demands `Content-MD5`, and demands it rightly: the body names the
            // objects that will disappear, and a truncation on the wire must not turn into the
            // wrong thing being deleted.
            val md5 =
                java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest
                        .getInstance("MD5")
                        .digest(body),
                )
            val answer =
                s3.send(
                    "POST",
                    "/photos",
                    query = "delete",
                    headers =
                        listOf(
                            "x-amz-bypass-governance-retention" to "true",
                            "Content-MD5" to md5,
                        ),
                    body = body,
                )

            assertEquals(200, answer.status, answer.text)
            assertTrue("<Error>" !in answer.text, answer.text)
            val left = s3.send("GET", "/photos", query = "versions").text
            assertTrue("<Version>" !in left, "what is left: $left")
        }
    }

    @Test
    fun `the document botocore actually sends is accepted`() {
        // The tests here wrote the document by hand and so could not see what the suite sees: a
        // real client sends the root element with a namespace and the date with an offset rather
        // than with `Z`. The run's log showed `PUT ?retention -> 400` where there should have been
        // no refusal at all.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            s3.put("photos", "a.txt", "тело")

            val answer =
                s3.send(
                    "PUT",
                    "/photos/a.txt",
                    query = "retention",
                    body =
                        (
                            "<Retention xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                                "<Mode>GOVERNANCE</Mode>" +
                                "<RetainUntilDate>2030-01-01T00:00:00+00:00</RetainUntilDate></Retention>"
                        ).toByteArray(),
                )

            assertEquals(200, answer.status, answer.text)
            assertEquals("GOVERNANCE", s3.get("photos", "a.txt").header("x-amz-object-lock-mode"))
        }
    }

    @Test
    fun `the retain-until header comes back in the form the client sent it`() {
        // The one of the three assertions in `test_object_lock_get_obj_metadata:13955` the tests
        // here did not cover: the mode and the hold status were compared and the date was not. The
        // case aborts on the failed assertion, never reaches its own cleanup, and leaves behind a
        // legal hold nothing can clear — and three hundred cases then fail in their fixtures.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            s3.put("photos", "a.txt", "тело")
            s3.send("PUT", "/photos/a.txt", query = "retention", body = retention("GOVERNANCE", "2030-01-01T00:00:00Z"))

            val head = s3.send("HEAD", "/photos/a.txt")

            assertEquals("2030-01-01T00:00:00Z", head.header("x-amz-object-lock-retain-until-date"))
            // The case takes the version out of **this** answer in order to delete it later:
            // without the header it fails on a `KeyError` before its cleanup, and the legal hold
            // stays forever.
            assertNotNull(head.header("x-amz-version-id"), "a HEAD has to name the version")
        }
    }

    @Test
    fun `a retention mode cannot be changed while the date stays the same`() {
        // M-175. `test_object_lock_changing_mode_from_governance_without_bypass:13993` and
        // `..._from_compliance:14010`. The "weakening" check looked only at the date, and the date
        // does not change here — the mode does, which is exactly what makes one lock different from
        // the other. A `COMPLIANCE` that became `GOVERNANCE` is a promise that became withdrawable.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            s3.put(
                "photos",
                "a.txt",
                "abc",
                headers =
                    listOf(
                        "x-amz-object-lock-mode" to "GOVERNANCE",
                        "x-amz-object-lock-retain-until-date" to far,
                    ),
            )

            val toCompliance =
                s3.send("PUT", "/photos/a.txt", query = "retention", body = retention("COMPLIANCE", far))

            assertEquals(403, toCompliance.status, toCompliance.text)
            assertContains(toCompliance.text, "AccessDenied")
            assertEquals("GOVERNANCE", s3.send("HEAD", "/photos/a.txt").header("x-amz-object-lock-mode"))
        }
    }

    @Test
    fun `with a GOVERNANCE bypass the mode can be changed, and not changed back`() {
        // The second half of the same rule, and without it the first would only show that changing
        // a mode is forbidden to everybody. `GOVERNANCE` yields to whoever says so out loud;
        // `COMPLIANCE` yields to nobody, that same person included.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            s3.put(
                "photos",
                "a.txt",
                "abc",
                headers =
                    listOf(
                        "x-amz-object-lock-mode" to "GOVERNANCE",
                        "x-amz-object-lock-retain-until-date" to far,
                    ),
            )

            val bypassed =
                s3.send(
                    "PUT",
                    "/photos/a.txt",
                    query = "retention",
                    headers = listOf("x-amz-bypass-governance-retention" to "true"),
                    body = retention("COMPLIANCE", far),
                )
            assertEquals(200, bypassed.status, bypassed.text)

            val back =
                s3.send(
                    "PUT",
                    "/photos/a.txt",
                    query = "retention",
                    headers = listOf("x-amz-bypass-governance-retention" to "true"),
                    body = retention("GOVERNANCE", far),
                )
            assertEquals(403, back.status, back.text)
            assertEquals("COMPLIANCE", s3.send("HEAD", "/photos/a.txt").header("x-amz-object-lock-mode"))
        }
    }

    @Test
    fun `a lock named when a multipart upload starts survives to the object`() {
        // M-175. `test_object_lock_delete_multipart_object_with_retention:13708`. The lock headers
        // travel on `CreateMultipartUpload` while the object appears minutes later — and until this
        // task they were simply lost: the upload completed into an object with no protection at
        // all, and the client was told it succeeded, because the upload really had.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            val started =
                s3.send(
                    "POST",
                    "/photos/big.bin",
                    query = "uploads",
                    headers =
                        listOf(
                            "x-amz-object-lock-mode" to "GOVERNANCE",
                            "x-amz-object-lock-retain-until-date" to far,
                        ),
                )
            val uploadId = Regex("<UploadId>(.*?)</UploadId>").find(started.text)!!.groupValues[1]
            val part =
                s3.send(
                    "PUT",
                    "/photos/big.bin",
                    query = "partNumber=1&uploadId=$uploadId",
                    body = "abc".toByteArray(),
                )
            val eTag = part.header("ETag")!!
            val done =
                s3.send(
                    "POST",
                    "/photos/big.bin",
                    query = "uploadId=$uploadId",
                    body =
                        "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>$eTag</ETag></Part></CompleteMultipartUpload>"
                            .toByteArray(),
                )
            assertEquals(200, done.status, done.text)
            val version = done.header("x-amz-version-id")!!

            assertEquals("GOVERNANCE", s3.send("HEAD", "/photos/big.bin").header("x-amz-object-lock-mode"))
            val refused = s3.send("DELETE", "/photos/big.bin", query = "versionId=$version")
            assertEquals(403, refused.status, refused.text)

            // And the same request with a bypass succeeds: the lock is real rather than a refusal
            // of everything.
            val allowed =
                s3.send(
                    "DELETE",
                    "/photos/big.bin",
                    query = "versionId=$version",
                    headers = listOf("x-amz-bypass-governance-retention" to "true"),
                )
            assertEquals(204, allowed.status, allowed.text)
        }
    }

    @Test
    fun `a legal hold named when a multipart upload starts survives too`() {
        // `test_object_lock_delete_multipart_object_with_legal_hold_on:13909`.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            val started =
                s3.send(
                    "POST",
                    "/photos/big.bin",
                    query = "uploads",
                    headers = listOf("x-amz-object-lock-legal-hold" to "ON"),
                )
            val uploadId = Regex("<UploadId>(.*?)</UploadId>").find(started.text)!!.groupValues[1]
            val part =
                s3.send(
                    "PUT",
                    "/photos/big.bin",
                    query = "partNumber=1&uploadId=$uploadId",
                    body = "abc".toByteArray(),
                )
            val eTag = part.header("ETag")!!
            val done =
                s3.send(
                    "POST",
                    "/photos/big.bin",
                    query = "uploadId=$uploadId",
                    body =
                        "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>$eTag</ETag></Part></CompleteMultipartUpload>"
                            .toByteArray(),
                )
            val version = done.header("x-amz-version-id")!!

            assertEquals("ON", s3.send("HEAD", "/photos/big.bin").header("x-amz-object-lock-legal-hold"))
            assertEquals(403, s3.send("DELETE", "/photos/big.bin", query = "versionId=$version").status)

            // The hold is cleared, and then the version deletes. A governance bypass has nothing to
            // do with it.
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "legal-hold",
                body = "<LegalHold><Status>OFF</Status></LegalHold>".toByteArray(),
            )
            assertEquals(204, s3.send("DELETE", "/photos/big.bin", query = "versionId=$version").status)
        }
    }
}
