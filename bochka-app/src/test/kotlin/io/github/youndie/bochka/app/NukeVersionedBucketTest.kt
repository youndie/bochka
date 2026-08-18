package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Уборка версионированного бакета — то, что чужой сьют делает **после каждого** теста.
 *
 * `nuke_prefixed_buckets` листает версии, удаляет каждую по имени и сносит бакет; если после
 * этого что-то остаётся, удаление бакета отвечает `409`, и фикстура заходит на второй круг.
 * Такой цикл выглядит снаружи не как ошибка, а как зависший сервер — 24 кейса упёрлись
 * в шестидесятисекундный таймаут именно так.
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
            // Как это делает фикстура: сначала обычное удаление, которое кладёт надгробия.
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
            assertTrue("<Version>" !in left && "<DeleteMarker>" !in left, "осталось: $left")
            assertEquals(204, s3.send("DELETE", "/photos").status, "бакет не сносится — фикстура пойдёт на второй круг")
        }
    }

    @Test
    fun `more than one page of versions can be paged through and deleted`() {
        // `test_bucket_list_delimiter_not_skip_special:683` кладёт 1004 ключа, и уборка после него
        // листает версии страницами. Тест на трёх ключах этого не видит: разница ровно в том,
        // что вторая страница резюмируется по паре маркеров, и ошибка там стоит целого сьюта —
        // бакет не пустеет, `DeleteBucket` отвечает `BucketNotEmpty`, и каждый следующий кейс
        // падает в своей фикстуре.
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

            assertEquals(1004, deleted, "уборка обязана увидеть все версии, а не первую страницу")
            assertEquals(204, s3.send("DELETE", "/photos").status, "бакет не пустеет — фикстура встанет")
        }
    }

    @Test
    fun `one pass of the cleanup empties a versioned bucket`() {
        // Уборка чужого сьюта листает версии **один раз** и удаляет каждую по имени. Мой первый
        // тест ходил кругами до пяти раз и потому не увидел бы разницы: если после одного прохода
        // что-то остаётся, `DeleteBucket` отвечает `BucketNotEmpty`, и следующий кейс падает
        // в своей фикстуре — это и есть механизм, которым один тест валит весь прогон.
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
            assertEquals(3, ids.size, "листинг версий обязан показать все три: $body")
            for ((key, version) in ids) s3.send("DELETE", "/photos/$key", query = "versionId=$version")

            assertEquals(204, s3.send("DELETE", "/photos").status, "одного прохода должно хватить")
        }
    }
}
