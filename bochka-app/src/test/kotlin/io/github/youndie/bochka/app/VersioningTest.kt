package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Версии на проводе (M-104, M-106, M-108).
 *
 * Формы ответов — из `s3-service-2.json`: `PutObjectOutput.members.VersionId` с
 * `location: "header", locationName: "x-amz-version-id"`, `DeleteObjectOutput.members.DeleteMarker`
 * с `x-amz-delete-marker`, и `GetObjectRequest.members.VersionId` как query-параметр `versionId`.
 *
 * Проверяется здесь то, чего не видит ни один тест на одну операцию: **что осталось после**.
 * Версионирование целиком состоит из отношений между записями, и утверждение вида «PUT ответил
 * 200» проходит одинаково и когда версии есть, и когда их нет.
 */
class VersioningTest {
    private fun S3Fixture.enable(bucket: String) {
        createBucket(bucket)
        send(
            "PUT",
            "/$bucket",
            query = "versioning",
            body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
        )
    }

    @Test
    fun `a bucket without versioning names no version at all`() {
        // Заголовок на каждом ответе сказал бы клиенту, что бакет версионирует, когда он не
        // версионирует. Отсутствие заголовка здесь — это утверждение, а не пропуск.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val put = s3.put("photos", "a.txt", "первый")

            assertEquals(200, put.status)
            assertNull(put.header("x-amz-version-id"))
            assertNull(s3.get("photos", "a.txt").header("x-amz-version-id"))
        }
    }

    @Test
    fun `two writes to a versioning bucket leave two versions, and the newer one is current`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")

            val first = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")
            val second = s3.put("photos", "a.txt", "второй").header("x-amz-version-id")

            assertNotNull(first)
            assertNotNull(second)
            assertNotEquals(first, second, "две записи — две версии, а не одна переписанная")
            assertEquals("второй", s3.get("photos", "a.txt").text)
            assertEquals("первый", s3.send("GET", "/photos/a.txt", query = "versionId=$first").text)
            assertEquals("второй", s3.send("GET", "/photos/a.txt", query = "versionId=$second").text)
        }
    }

    @Test
    fun `deleting lays a tombstone rather than removing anything`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val version = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")

            val deleted = s3.send("DELETE", "/photos/a.txt")

            assertEquals(204, deleted.status)
            assertEquals("true", deleted.header("x-amz-delete-marker"))
            assertNotNull(deleted.header("x-amz-version-id"))
            // Ключ пропал, байты — нет: это и есть разница между удалением и надгробием.
            val read = s3.get("photos", "a.txt")
            assertEquals(404, read.status)
            assertEquals("true", read.header("x-amz-delete-marker"), "иначе клиент не узнает, что объект вернём")
            assertEquals("первый", s3.send("GET", "/photos/a.txt", query = "versionId=$version").text)
        }
    }

    @Test
    fun `removing the tombstone brings the key back`() {
        // Ради этого надгробие и отдаёт свой versionId в ответе на DELETE: другого способа узнать
        // его у клиента нет.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            val marker = s3.send("DELETE", "/photos/a.txt").header("x-amz-version-id")

            val removed = s3.send("DELETE", "/photos/a.txt", query = "versionId=$marker")

            assertEquals(204, removed.status)
            assertEquals("true", removed.header("x-amz-delete-marker"))
            assertEquals("первый", s3.get("photos", "a.txt").text)
        }
    }

    @Test
    fun `reading a tombstone by its id is 405, not 404`() {
        // Разные вопросы — разные ответы. `404` сказал бы, что версии нет, и клиент перестал бы
        // пытаться её удалить — то есть перестал бы пытаться вернуть объект.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            val marker = s3.send("DELETE", "/photos/a.txt").header("x-amz-version-id")

            val read = s3.send("GET", "/photos/a.txt", query = "versionId=$marker")

            assertEquals(405, read.status, read.text)
            assertEquals("true", read.header("x-amz-delete-marker"))
        }
    }

    @Test
    fun `a listing shows the current version and skips a tombstoned key`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            s3.put("photos", "a.txt", "второй")
            s3.put("photos", "b.txt", "бэ")

            s3.send("DELETE", "/photos/b.txt")
            val listing = s3.send("GET", "/photos", query = "list-type=2")

            assertTrue("<Key>a.txt</Key>" in listing.text, listing.text)
            assertTrue("<Key>b.txt</Key>" !in listing.text, "надгробие не листается: ${listing.text}")
            // Два PUT в один ключ — одна строка листинга, а не две: листинг про объекты, а не
            // про версии.
            assertEquals(1, Regex("<Key>").findAll(listing.text).count(), listing.text)
        }
    }

    @Test
    fun `deleting one version by id leaves the others, and the newest of them is current`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val first = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")
            val second = s3.put("photos", "a.txt", "второй").header("x-amz-version-id")

            assertEquals(204, s3.send("DELETE", "/photos/a.txt", query = "versionId=$second").status)

            assertEquals("первый", s3.get("photos", "a.txt").text, "текущей стала оставшаяся")
            assertEquals(404, s3.send("GET", "/photos/a.txt", query = "versionId=$second").status)
            assertEquals("первый", s3.send("GET", "/photos/a.txt", query = "versionId=$first").text)
        }
    }

    @Test
    fun `suspending keeps the old versions and stops making new ones`() {
        // Приостановленный бакет — не «невключённый»: версии, сделанные пока он был включён,
        // остаются доступными по имени.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val versioned = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")

            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = "<VersioningConfiguration><Status>Suspended</Status></VersioningConfiguration>".toByteArray(),
            )
            s3.put("photos", "a.txt", "второй")
            s3.put("photos", "a.txt", "третий")

            assertEquals("третий", s3.get("photos", "a.txt").text)
            assertEquals("первый", s3.send("GET", "/photos/a.txt", query = "versionId=$versioned").text)
            // Записи в приостановленный бакет складываются в одну версию `null`, а не копятся.
            assertEquals("третий", s3.send("GET", "/photos/a.txt", query = "versionId=null").text)
        }
    }

    @Test
    fun `a copy names the version it copies from`() {
        // M-141. До этого версия из `x-amz-copy-source` срезалась и выбрасывалась: клиент просил
        // старую, получал текущую и **никакого признака**, что его запрос переиначили.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val first = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")
            s3.put("photos", "a.txt", "второй")

            s3.send(
                "PUT",
                "/photos/copy.txt",
                headers = listOf("x-amz-copy-source" to "/photos/a.txt?versionId=$first"),
            )

            assertEquals("первый", s3.get("photos", "copy.txt").text)
        }
    }

    @Test
    fun `a batch delete says which tombstones it made`() {
        // M-139. Единственное место, где пакетная форма отдаёт имя надгробия: без него клиент,
        // удаливший тысячу ключей, не может отменить ни одного.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "тело")
            val body = "<Delete><Object><Key>a.txt</Key></Object></Delete>".toByteArray()
            val md5 =
                java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest
                        .getInstance("MD5")
                        .digest(body),
                )

            val answer =
                s3.send("POST", "/photos", query = "delete", headers = listOf("Content-MD5" to md5), body = body)

            assertTrue("<DeleteMarker>true</DeleteMarker>" in answer.text, answer.text)
            assertTrue("<DeleteMarkerVersionId>" in answer.text, answer.text)
        }
    }

    @Test
    fun `GetObjectAttributes reads the version it was given`() {
        // M-142.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            val first = s3.put("photos", "a.txt", "первый").header("x-amz-version-id")
            s3.put("photos", "a.txt", "подлиннее второй")

            val attributes =
                s3.send(
                    "GET",
                    "/photos/a.txt",
                    query = "attributes&versionId=$first",
                    headers =
                        listOf("x-amz-object-attributes" to "ObjectSize"),
                )

            assertTrue("<ObjectSize>12</ObjectSize>" in attributes.text, attributes.text)
        }
    }
}
