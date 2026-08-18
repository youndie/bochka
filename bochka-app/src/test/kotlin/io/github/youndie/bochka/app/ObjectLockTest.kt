package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun `the sequence test_object_lock_get_obj_metadata runs leaves nothing behind`() {
        // Кейс убирает за собой сам: снимает legal hold и удаляет версию с обходом GOVERNANCE.
        // Если после этого в бакете что-то остаётся, чужая фикстура упирается в retention до
        // 2030 года и валит все следующие кейсы — 92 штуки.
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
            assertEquals("ON", head.header("x-amz-object-lock-legal-hold-status"))
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
            assertTrue("<Version>" !in left && "<DeleteMarker>" !in left, "осталось: $left")
        }
    }

    @Test
    fun `the batch delete the cleanup uses steps over GOVERNANCE when it says so`() {
        // `nuke_bucket` не удаляет по одному: оно шлёт `POST ?delete` пачками по 128 с
        // `BypassGovernanceRetention=True`. Кейсы, которые ставят retention до 2030 и не убирают
        // за собой, рассчитывают именно на этот путь — если обход не доезжает досюда, бакет
        // остаётся запертым на годы, и это валит весь прогон.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            val version = s3.put("photos", "a.txt", "тело").header("x-amz-version-id")!!
            s3.send("PUT", "/photos/a.txt", query = "retention", body = retention("GOVERNANCE", "2030-01-01T00:00:00Z"))

            val body =
                (
                    "<Delete><Quiet>true</Quiet><Object><Key>a.txt</Key>" +
                        "<VersionId>$version</VersionId></Object></Delete>"
                ).toByteArray()
            // `Content-MD5` пакетное удаление требует, и требует правильно: тело называет
            // объекты, которые исчезнут, и обрыв на проводе не должен обернуться удалением
            // не того.
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
            assertTrue("<Version>" !in left, "осталось: $left")
        }
    }

    @Test
    fun `the document botocore actually sends is accepted`() {
        // Мои тесты писали документ руками и потому не видели того, что видит сьют: настоящий
        // клиент шлёт корневой элемент с пространством имён и дату со смещением, а не с `Z`.
        // Лог прогона показал `PUT ?retention -> 400` там, где отказа быть не должно.
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
        // Единственная из трёх проверок `test_object_lock_get_obj_metadata:13955`, которую мои
        // тесты не покрывали: режим и статус удержания сверялись, а дата — нет. Кейс обрывается
        // на упавшей проверке, до своей уборки не доходит и оставляет legal hold, который снять
        // уже нечем, — и следом триста кейсов падают в своих фикстурах.
        S3Fixture().use { s3 ->
            s3.locked("photos")
            s3.put("photos", "a.txt", "тело")
            s3.send("PUT", "/photos/a.txt", query = "retention", body = retention("GOVERNANCE", "2030-01-01T00:00:00Z"))

            val head = s3.send("HEAD", "/photos/a.txt")

            assertEquals("2030-01-01T00:00:00Z", head.header("x-amz-object-lock-retain-until-date"))
            // Кейс берёт версию из **этого** ответа, чтобы потом удалить её: без заголовка он
            // падает на `KeyError` до своей уборки, и legal hold остаётся навсегда.
            assertNotNull(head.header("x-amz-version-id"), "HEAD обязан назвать версию")
        }
    }
}
