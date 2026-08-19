package io.github.youndie.bochka.app

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `?lifecycle` целиком: три метода, пять отказов и заголовок `x-amz-expiration`.
 *
 * Форма — `docs/spec/s3-service-2.json`, `BucketLifecycleConfiguration` (`:2127`) и
 * `LifecycleRule` (`:7896`). Тела запросов сняты с botocore, а не сочинены: половина проверок
 * здесь про то, чем документ **приезжает**, а не про то, чем он выглядит в документации.
 */
class LifecycleApiTest {
    @Test
    fun `бакет без правил отвечает отказом с именем, а не пустым документом`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer = s3.send("GET", "/photos", query = "lifecycle")

            assertEquals(404, answer.status, answer.text)
            assertTrue("NoSuchLifecycleConfiguration" in answer.text, answer.text)
        }
    }

    @Test
    fun `правила кладутся, читаются и снимаются`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val put = s3.send("PUT", "/photos", query = "lifecycle", body = TWO_RULES.toByteArray())
            assertEquals(200, put.status, put.text)

            val read = s3.send("GET", "/photos", query = "lifecycle")
            assertEquals(200, read.status, read.text)
            assertTrue("<ID>test1/</ID>" in read.text, read.text)
            assertTrue("<Days>31</Days>" in read.text, read.text)
            assertTrue("<Days>120</Days>" in read.text, read.text)
            // Префикс уехал тем же членом, каким приехал: `test_lifecycle_get:8451` сравнивает
            // правила целиком, и правило с `<Filter>` вместо `<Prefix>` — уже другое правило.
            assertTrue("<Prefix>test1/</Prefix>" in read.text, read.text)
            assertFalse("<Filter>" in read.text, read.text)

            assertEquals(204, s3.send("DELETE", "/photos", query = "lifecycle").status)
            assertEquals(404, s3.send("GET", "/photos", query = "lifecycle").status)
            // И ещё раз, по пустому месту: `test_lifecycle_delete:8462` пинит `204` с обеих
            // сторон — до постановки правил и после снятия.
            assertEquals(204, s3.send("DELETE", "/photos", query = "lifecycle").status)
        }
    }

    @Test
    fun `правило без идентификатора получает его на записи и держит на чтении`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            val body =
                "<LifecycleConfiguration><Rule><Expiration><Days>31</Days></Expiration>" +
                    "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"

            assertEquals(200, s3.send("PUT", "/photos", query = "lifecycle", body = body.toByteArray()).status)

            val first = s3.send("GET", "/photos", query = "lifecycle").text
            assertTrue("<ID>" in first, first)
            // Тот же самый при повторном чтении, а не новый каждый раз: идентификатор придуман
            // один раз и лежит в сохранённом документе.
            assertEquals(first, s3.send("GET", "/photos", query = "lifecycle").text)
        }
    }

    @Test
    fun `сломанный документ и неисполнимый документ отвергаются разными кодами`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            fun refusal(body: String) = s3.send("PUT", "/photos", query = "lifecycle", body = body.toByteArray())

            // `Status: enabled` — документ не является документом (`ExpirationStatus`, `:4881`).
            val status = refusal(rule("<Expiration><Days>2</Days></Expiration>", status = "enabled"))
            assertEquals(400, status.status, status.text)
            assertTrue("MalformedXML" in status.text, status.text)

            // Всё остальное разбирается и не может быть исполнено.
            val unworkable =
                listOf(
                    "ноль дней" to rule("<Expiration><Days>0</Days></Expiration>"),
                    "длинный ID" to rule("<Expiration><Days>2</Days></Expiration>", id = "a".repeat(256)),
                    "дата не в полночь" to rule("<Expiration><Date>1970-08-22T19:08:21Z</Date></Expiration>"),
                    "переход между классами хранения" to
                        rule(
                            "<Expiration><Date>2023-09-27T00:00:00Z</Date></Expiration>" +
                                "<Transition><Date>2030-01-01T00:00:00Z</Date>" +
                                "<StorageClass>GLACIER</StorageClass></Transition>",
                        ),
                )
            for ((what, body) in unworkable) {
                val answer = refusal(body)
                assertEquals(400, answer.status, "$what: ${answer.text}")
                assertTrue("InvalidArgument" in answer.text, "$what: ${answer.text}")
            }

            // И ни один из отказов не оставил после себя настройку.
            assertEquals(404, s3.send("GET", "/photos", query = "lifecycle").status)
        }
    }

    @Test
    fun `два правила с одним идентификатором — отказ`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            val body =
                "<LifecycleConfiguration>" +
                    "<Rule><ID>rule1</ID><Expiration><Days>1</Days></Expiration>" +
                    "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule>" +
                    "<Rule><ID>rule1</ID><Expiration><Days>2</Days></Expiration>" +
                    "<Prefix>test2/</Prefix><Status>Enabled</Status></Rule>" +
                    "</LifecycleConfiguration>"

            val answer = s3.send("PUT", "/photos", query = "lifecycle", body = body.toByteArray())

            assertEquals(400, answer.status, answer.text)
            assertTrue("InvalidArgument" in answer.text, answer.text)
        }
    }

    @Test
    fun `пустой фильтр приезжает самозакрывающимся элементом и принимается`() {
        // Ровно то тело, которое botocore кладёт на провод для `Filter: {}`
        // (`test_lifecycle_set_empty_filter:9349`). До M23 сервер отвечал на него `MalformedXML`,
        // потому что читатель XML отвергал `<x/>` — а другой формы у стандартного клиента нет.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            val body =
                "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                    "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>" +
                    "<Filter /><Status>Enabled</Status></Rule></LifecycleConfiguration>"

            assertEquals(200, s3.send("PUT", "/photos", query = "lifecycle", body = body.toByteArray()).status)
        }
    }

    @Test
    fun `x-amz-expiration отвечает на запись и на чтение`() {
        // `test_lifecycle_expiration_header_put:9162` и `…_head:9174`. Форма заголовка —
        // `expiry-date="…", rule-id="…"`, и кейс разбирает её регуляркой, то есть проверяет
        // именно её, а не наличие чего-нибудь.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send("PUT", "/photos", query = "lifecycle", body = rule().toByteArray())

            val written = s3.put("photos", "days1/foo", "bar")
            val header = written.header("x-amz-expiration")
            assertNotNull(header, "заголовка нет на записи")
            assertTrue(Regex("""expiry-date="(.+)", rule-id="rule1"""").containsMatchIn(header), header)
            // Полночь UTC, а не «сутки от сейчас»: округление — часть того, что S3 обещает.
            assertTrue(header.contains("00:00:00 GMT"), header)

            assertEquals(header, s3.send("HEAD", "/photos/days1/foo").header("x-amz-expiration"))
            assertEquals(header, s3.get("photos", "days1/foo").header("x-amz-expiration"))
        }
    }

    @Test
    fun `x-amz-expiration отсутствует, когда объект под правило не подходит`() {
        // Вторая половина `test_lifecycle_expiration_header_tags_head:9192`, и та, которую легко
        // не сделать: кейс ставит правило по тегу, читает заголовок, меняет тег в правиле и
        // требует, чтобы заголовка не стало.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "obj_key1", "body")
            s3.send("PUT", "/photos/obj_key1", query = "tagging", body = TAGGING.toByteArray())

            s3.send("PUT", "/photos", query = "lifecycle", body = taggedRule("key1", "tag1").toByteArray())
            assertNotNull(s3.send("HEAD", "/photos/obj_key1").header("x-amz-expiration"))

            s3.send("PUT", "/photos", query = "lifecycle", body = taggedRule("key2", "tag1").toByteArray())
            assertNull(s3.send("HEAD", "/photos/obj_key1").header("x-amz-expiration"))

            // И объект вне префикса тоже без заголовка, при том же правиле.
            s3.send("PUT", "/photos", query = "lifecycle", body = rule().toByteArray())
            s3.put("photos", "elsewhere/foo", "bar")
            assertNull(s3.send("HEAD", "/photos/elsewhere/foo").header("x-amz-expiration"))
        }
    }

    @Test
    fun `укороченный день виден в заголовке, а не только в обходе`() {
        // Единица «дня» приходит в заголовок из той же настройки, по которой удаляет обход. Если
        // бы заголовок считался сутками всегда, сервер обещал бы один срок и удалял по другому —
        // и увидеть это можно было бы только по пропавшему объекту.
        S3Fixture(lifecycleDay = Duration.ofSeconds(10)).use { s3 ->
            s3.createBucket("photos")
            s3.send("PUT", "/photos", query = "lifecycle", body = rule().toByteArray())

            val header = s3.put("photos", "days1/foo", "bar").header("x-amz-expiration")

            assertNotNull(header)
            // Десять секунд от «сейчас» — это сегодня, а не полночь через сутки.
            assertFalse(header.contains("00:00:00 GMT"), header)
        }
    }

    private companion object {
        val TWO_RULES =
            "<LifecycleConfiguration>" +
                "<Rule><ID>test1/</ID><Expiration><Days>31</Days></Expiration>" +
                "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule>" +
                "<Rule><ID>test2/</ID><Expiration><Days>120</Days></Expiration>" +
                "<Prefix>test2/</Prefix><Status>Enabled</Status></Rule>" +
                "</LifecycleConfiguration>"

        val TAGGING =
            "<Tagging><TagSet><Tag><Key>key1</Key><Value>tag1</Value></Tag>" +
                "<Tag><Key>key5</Key><Value>tag5</Value></Tag></TagSet></Tagging>"

        fun rule(
            what: String = "<Expiration><Days>1</Days></Expiration>",
            id: String = "rule1",
            status: String = "Enabled",
            prefix: String = "days1/",
        ) = "<LifecycleConfiguration><Rule><ID>$id</ID>$what" +
            "<Prefix>$prefix</Prefix><Status>$status</Status></Rule></LifecycleConfiguration>"

        fun taggedRule(
            key: String,
            value: String,
        ) = "<LifecycleConfiguration><Rule><ID>rule1</ID><Expiration><Days>1</Days></Expiration>" +
            "<Filter><Tag><Key>$key</Key><Value>$value</Value></Tag></Filter>" +
            "<Status>Enabled</Status></Rule></LifecycleConfiguration>"
    }
}
