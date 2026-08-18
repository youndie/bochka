package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Object lock и retention (M-109…M-111).
 *
 * Отрицательное здесь важнее положительного: замок ценен ровно тем, чего он **не** даёт сделать,
 * а сьют куда чаще проверяет, что разрешённое разрешено. Поэтому большая часть тестов ниже —
 * про отказы, и каждый был увиден красным до того, как появился код.
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
        // Retention на том, что можно переписать на месте, не защищает ничего: версионирование
        // приходит вместе с замком, а не отдельным вызовом.
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
        // Создание — не единственная дверь: настоящее предусловие это версионирование, а
        // `ObjectLockEnabledForBucket` был его частным случаем.
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
        // Это и есть разница между режимами, и она не в силе, а в том, кто может её снять.
        // Обещание, которое автор может отозвать, — не то обещание, ради которого замок заводят.
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
        // Одно и то же отсутствие с двух сторон, и коды разные: у бакета `409`, у объекта `400`.
        // Клиент чинит разное — один пересоздаёт бакет, другой перестаёт спрашивать.
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
}
