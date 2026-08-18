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
}
