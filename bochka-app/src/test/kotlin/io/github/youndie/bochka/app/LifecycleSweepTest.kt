package io.github.youndie.bochka.app

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Обход, который действительно удаляет.
 *
 * Ради этого файла и заводились правила: конфигурация, которую сервер хранит и отдаёт, но не
 * применяет, — это `PutBucketPolicy` из «чего не делать», и клиент узнаёт о ней счётом за
 * хранение, а не ошибкой.
 *
 * **Ни одного `sleep`.** «День» правила — настройка, тест ставит её в миллисекунду и зовёт обход
 * руками; сервер зовёт тот же обход фоновым потоком. Тест, ждущий времени, либо медленный, либо
 * мигающий, обычно и то и другое.
 */
class LifecycleSweepTest {
    @Test
    fun `истекает то, что под правилом, и только оно`() {
        instant { s3 ->
            s3.createBucket("photos")
            for (key in listOf("expire1/foo", "expire1/bar", "keep2/foo", "expire3/foo")) {
                s3.put("photos", key, "x")
            }
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>"))

            val report = s3.sweepLifecycle(later())

            assertEquals(2, report.objects, report.toString())
            assertEquals(404, s3.get("photos", "expire1/foo").status)
            assertEquals(404, s3.get("photos", "expire1/bar").status)
            assertEquals(200, s3.get("photos", "keep2/foo").status)
            assertEquals(200, s3.get("photos", "expire3/foo").status)
        }
    }

    @Test
    fun `выключенное правило не делает ничего`() {
        instant { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "expire1/foo", "x")
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>", status = "Disabled"))

            assertTrue(s3.sweepLifecycle(later()).empty)
            assertEquals(200, s3.get("photos", "expire1/foo").status)
        }
    }

    @Test
    fun `срок, который ещё не вышел, не наступает от одного вызова обхода`() {
        // Обратная сторона: с «днём» в час правило «через сутки» не срабатывает, сколько обход
        // ни зови. Без этого теста предыдущий доказывал бы только то, что обход что-то удаляет.
        S3Fixture(lifecycleDay = Duration.ofHours(1)).use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "expire1/foo", "x")
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>"))

            assertTrue(s3.sweepLifecycle(later()).empty)
            assertEquals(200, s3.get("photos", "expire1/foo").status)
        }
    }

    @Test
    fun `в версионированном бакете срок кладёт надгробие, а не стирает версию`() {
        // Срок — это «считать удалённым», а не «стереть». Версия остаётся под надгробием и
        // достаётся по имени, ровно как после обычного `DELETE`.
        instant { s3 ->
            s3.createBucket("photos")
            s3.versioned("photos")
            s3.put("photos", "expire1/foo", "x")
            s3.rules(rule("rule1", "expire1/", "<Expiration><Days>1</Days></Expiration>"))

            assertEquals(1, s3.sweepLifecycle(later()).objects)

            val read = s3.get("photos", "expire1/foo")
            assertEquals(404, read.status)
            assertEquals("true", read.header("x-amz-delete-marker"))
            val versions = s3.send("GET", "/photos", query = "versions").text
            assertEquals(1, Regex("<DeleteMarker>").findAll(versions).count(), versions)
            assertEquals(1, Regex("<Version>").findAll(versions).count(), versions)
        }
    }

    @Test
    fun `неактуальные версии истекают, а текущая остаётся`() {
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

            val report = s3.sweepLifecycle(later())

            assertEquals(3, report.versions, report.toString())
            assertEquals(0, report.objects, report.toString())
            assertEquals("v3", s3.get("photos", "myobject_").text)
            assertEquals(1, Regex("<Version>").findAll(s3.send("GET", "/photos", query = "versions").text).count())
        }
    }

    @Test
    fun `NewerNoncurrentVersions оставляет названное число свежих`() {
        // Считается от текущей вниз: при десяти версиях и `NewerNoncurrentVersions: 5` остаются
        // текущая и пять следующих, а четыре нижние уходят — `test_lifecycle_expiration_newer_noncurrent:8854`.
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

            assertEquals(4, s3.sweepLifecycle(later()).versions)

            val versions = s3.send("GET", "/photos", query = "versions").text
            assertEquals(6, Regex("<Version>").findAll(versions).count(), versions)
        }
    }

    @Test
    fun `надгробие уходит только когда под ним ничего не осталось`() {
        // Порядок внутри одного обхода: сначала неактуальные версии, потом надгробие. Пока под
        // ним есть версия, оно не одиноко, и правило про одинокое надгробие к нему неприменимо —
        // `test_lifecycle_deletemarker_expiration:9361` проверяет именно эту последовательность.
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
            // Под надгробием ещё лежит версия — трогать нечего.
            assertTrue(s3.sweepLifecycle(later()).empty)
            assertEquals(2, Regex("<(Version|DeleteMarker)>").findAll(versions(s3)).count(), versions(s3))

            // Появилось правило про неактуальные версии — и в одном обходе уходят обе: сначала
            // версия, потом осиротевшее надгробие.
            s3.rules(
                rule(
                    "rule1",
                    "test1/",
                    "<NoncurrentVersionExpiration><NoncurrentDays>1</NoncurrentDays>" +
                        "</NoncurrentVersionExpiration>" +
                        "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>",
                ),
            )
            val report = s3.sweepLifecycle(later())

            assertEquals(1, report.versions, report.toString())
            assertEquals(1, report.markers, report.toString())
            assertTrue("<Version>" !in versions(s3), versions(s3))
            assertTrue("<DeleteMarker>" !in versions(s3), versions(s3))
        }
    }

    @Test
    fun `размер сравнивается, и правило по размеру не трогает соседа`() {
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

            assertEquals(1, s3.sweepLifecycle(later()).objects)
            assertEquals(200, s3.get("photos", "myobject_small").status)
            assertEquals(404, s3.get("photos", "myobject_big").status)
        }
    }

    @Test
    fun `брошенная многочастная загрузка отменяется по префиксу`() {
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

            assertEquals(1, s3.sweepLifecycle(later()).uploads)

            val listed = s3.send("GET", "/photos", query = "uploads").text
            assertTrue("test2/b" in listed, listed)
            assertTrue("test1/a" !in listed, listed)
        }
    }

    @Test
    fun `правило с условием по размеру не отменяет незаписанную загрузку`() {
        // У начатой загрузки нет ни байтов, ни тегов. `ObjectSizeLessThan: 2000` подошёл бы ей
        // как «ноль меньше двух тысяч» — то есть загрузка была бы отменена по условию, которого
        // про неё никто не проверял. Правило, называющее размер или тег, к загрузкам не
        // применяется вовсе.
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

            assertEquals(0, s3.sweepLifecycle(later()).uploads)
            assertTrue("test1/a" in s3.send("GET", "/photos", query = "uploads").text)
        }
    }

    @Test
    fun `версия под legal hold переживает свой срок`() {
        // Отрицательный тест, и он здесь главный: замок — обещание, которое сильнее срока.
        // Правило, снимающее удержанную версию, было бы худшим способом потерять данные —
        // тихим, отложенным и записанным в конфигурации как «хотели сами».
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
            // Удержание ставится на **старую** версию, по имени: без `versionId` оно легло бы на
            // текущую, а её это правило и не трогает — тест был бы зелёным, ничего не проверив.
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

            // Обход прошёл, старая версия — под удержанием и осталась. Заодно: отказ по одной
            // версии не останавливает обход, он просто её не трогает.
            s3.sweepLifecycle(later())

            assertEquals(200, s3.get("photos", "held/a").status)
            assertEquals(2, Regex("<Version>").findAll(versions(s3)).count(), versions(s3))
        }
    }

    private fun versions(s3: S3Fixture) = s3.send("GET", "/photos", query = "versions").text

    /**
     * Фикстура, у которой «день» длится миллисекунду: всё, что имеет срок, наступает сразу.
     *
     * Не `Duration.ZERO`: ноль означал бы «истекло в момент записи», и тест перестал бы отличать
     * сработавшее правило от правила, применённого к чему угодно.
     */
    private fun instant(body: (S3Fixture) -> Unit) = S3Fixture(lifecycleDay = Duration.ofMillis(1)).use(body)

    /**
     * Момент, в который обход смотрит на срок, — явный, а не «сейчас».
     *
     * «День» здесь длится миллисекунду, поэтому при часах по умолчанию истечение решается тем,
     * сколько времени прошло между записью объекта и обходом. Это свойство машины, а не кода:
     * `LifecycleSweepTest` упал в CI на дереве, зелёном пять прогонов подряд здесь. Секунда вперёд
     * — это тысяча «дней», то есть срок заведомо вышел у всего, у чего он вообще есть, и ни одна
     * проверка «правило не сработало» от этого не ослабевает: там срабатывает или не срабатывает
     * **правило**, а не часы.
     */
    private fun later(): Instant = Instant.now().plusSeconds(1)

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
