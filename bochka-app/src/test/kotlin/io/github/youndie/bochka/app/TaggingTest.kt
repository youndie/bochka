package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Теги бакета и объекта (M-91, M-92).
 *
 * Формы — `docs/spec/s3-service-2.json`: `Tagging` (`:13301`) содержит `TagSet` (`:13294`),
 * элемент которого — `Tag` с обязательными `Key` и `Value` (`:13272`). Заголовок `x-amz-tagging`
 * на загрузке — `:3158`.
 *
 * Единственное место, где здесь есть решение, а не хранение: **отсутствующий набор — это `404`
 * с кодом `NoSuchTagSet`, а не пустой документ.** Клиент читает эти два ответа по-разному, и
 * `test_set_bucket_tagging:7148` проверяет именно код.
 */
class TaggingTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private fun tagging(vararg pairs: Pair<String, String>) = tagging(pairs.toList())

    private fun tagging(pairs: List<Pair<String, String>>) =
        pairs
            .joinToString(
                "",
                prefix = "<Tagging><TagSet>",
                postfix = "</TagSet></Tagging>",
            ) { "<Tag><Key>${it.first}</Key><Value>${it.second}</Value></Tag>" }
            .toByteArray()

    @Test
    fun `бакет без тегов отвечает NoSuchTagSet, а не пустым набором`() {
        s3.createBucket("photos")

        val answer = s3.send("GET", "/photos", query = "tagging")

        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchTagSet")
    }

    @Test
    fun `теги бакета кладутся, читаются и снимаются`() {
        s3.createBucket("photos")

        assertEquals(200, s3.send("PUT", "/photos", query = "tagging", body = tagging("Hello" to "World")).status)

        val read = s3.send("GET", "/photos", query = "tagging")
        assertEquals(200, read.status, read.text)
        assertContains(read.text, "<Key>Hello</Key>")
        assertContains(read.text, "<Value>World</Value>")

        assertEquals(204, s3.send("DELETE", "/photos", query = "tagging").status)
        assertEquals(404, s3.send("GET", "/photos", query = "tagging").status, "снятый набор снова отсутствует")
    }

    @Test
    fun `набор тегов заменяется целиком, а не дополняется`() {
        // `PutBucketTagging` — это не «добавить тег»: он кладёт весь набор. Дополнение оставило бы
        // клиенту способ накопить теги и ни одного — их убрать.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "tagging", body = tagging("a" to "1", "b" to "2"))
        s3.send("PUT", "/photos", query = "tagging", body = tagging("c" to "3"))

        val read = s3.send("GET", "/photos", query = "tagging").text

        assertContains(read, "<Key>c</Key>")
        assertEquals(1, Regex("<Tag>").findAll(read).count(), "должен остаться ровно один тег: $read")
    }

    @Test
    fun `теги переживают перезапуск, потому что они состояние бакета`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "tagging", body = tagging("Hello" to "World"))

        s3.store.close()
        val reopened =
            io.github.youndie.bochka.core
                .ObjectStore(s3.root)
        val document = reopened.bucketSubresource("photos", "tagging")
        reopened.close()

        assertContains(String(document!!), "<Key>Hello</Key>")
    }

    @Test
    fun `объект без тегов отвечает пустым набором, а не 404`() {
        // И это **не** то же, что у бакета, хотя операция называется так же. У объекта тегов может
        // не быть, но сам объект есть, и S3 отвечает пустым `TagSet`; `404` тут значил бы, что нет
        // объекта. Одинаковое имя операции, разные ответы — поэтому проверяется отдельно.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "x")

        val answer = s3.send("GET", "/photos/a.txt", query = "tagging")

        assertEquals(200, answer.status, answer.text)
        assertContains(answer.text, "<TagSet")
    }

    @Test
    fun `теги объекта кладутся, читаются и снимаются`() {
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "x")

        assertEquals(200, s3.send("PUT", "/photos/a.txt", query = "tagging", body = tagging("k" to "v")).status)
        assertContains(s3.send("GET", "/photos/a.txt", query = "tagging").text, "<Key>k</Key>")

        assertEquals(204, s3.send("DELETE", "/photos/a.txt", query = "tagging").status)
        val empty = s3.send("GET", "/photos/a.txt", query = "tagging")
        assertEquals(200, empty.status)
        assertEquals(0, Regex("<Tag>").findAll(empty.text).count())
    }

    @Test
    fun `теги приезжают заголовком при загрузке и считаются при чтении`() {
        // `x-amz-tagging: a=1&b=2` — форма запроса, а не документа (`:3158`). Клиент, положивший
        // объект одним запросом, иначе вынужден делать второй.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "x", listOf("x-amz-tagging" to "a=1&b=2"))

        assertContains(s3.send("GET", "/photos/a.txt", query = "tagging").text, "<Key>a</Key>")
        assertEquals("2", s3.get("photos", "a.txt").header("x-amz-tagging-count"))
    }

    @Test
    fun `тегов объекта не бывает без объекта`() {
        s3.createBucket("photos")

        val answer = s3.send("GET", "/photos/missing.txt", query = "tagging")

        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchKey")
    }

    @Test
    fun `x-amz-tagging без значения — это тег с пустым значением, а не поломка`() {
        // `test_put_obj_with_tags:12281` шлёт `foo=bar&bar` и ждёт двух тегов, у второго значение
        // пустое. Форма `key` без `=` законна: у тега значение необязательно.
        s3.createBucket("photos")

        val put = s3.put("photos", "a.txt", "body", headers = listOf("x-amz-tagging" to "foo=bar&bar"))

        assertEquals(200, put.status, put.text)
        val read = s3.send("GET", "/photos/a.txt", query = "tagging")
        assertContains(read.text, "<Key>bar</Key><Value></Value>")
        assertContains(read.text, "<Key>foo</Key><Value>bar</Value>")
    }

    @Test
    fun `испорченный x-amz-tagging — это ответ, а не оборванное соединение`() {
        // Отказ обязан быть отказом. `screen` читает заголовки до тела, и брошенное оттуда
        // исключение уходило мимо цикла запроса: клиент получал закрытый сокет и диагностировал
        // сеть. У `handle` такая защита была с самого начала, у `screen` — нет.
        s3.createBucket("photos")

        val put = s3.put("photos", "a.txt", "body", headers = listOf("x-amz-tagging" to "a=%ZZ"))

        assertTrue(put.status in 400..599, "ожидался ответ, пришёл ${put.status}")
        assertContains(put.text, "<Error>")
    }

    @Test
    fun `одиннадцать тегов — InvalidTag, и объект остаётся без тегов`() {
        // `test_put_excess_tags:12072`. Код здесь важнее статуса: `MalformedXML` отправил бы
        // клиента искать ошибку в своём сериализаторе, а документ был безупречен — неверен
        // набор, который он описывает.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "body")

        val answer = s3.send("PUT", "/photos/a.txt", query = "tagging", body = tagging((1..11).map { "$it" to "$it" }))

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "InvalidTag")
        // Вторая половина кейса: отказ, оставивший после себя набор, — это не отказ.
        assertContains(s3.send("GET", "/photos/a.txt", query = "tagging").text, "<TagSet></TagSet>")
    }

    @Test
    fun `длина ключа и значения проверяется с обеих сторон границы`() {
        // `test_put_max_kvsize_tags:12087` требует успеха на 128 и 256,
        // `test_put_excess_key_tags:12108` и `test_put_excess_val_tags:12130` — отказа на 129 и 257.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "body")

        fun put(
            key: String,
            value: String,
        ) = s3.send("PUT", "/photos/a.txt", query = "tagging", body = tagging(listOf(key to value)))

        assertEquals(200, put("k".repeat(128), "v".repeat(256)).status)

        val longKey = put("k".repeat(129), "v")
        assertEquals(400, longKey.status, longKey.text)
        assertContains(longKey.text, "InvalidTag")

        val longValue = put("k", "v".repeat(257))
        assertEquals(400, longValue.status, longValue.text)
        assertContains(longValue.text, "InvalidTag")
    }

    @Test
    fun `набор из заголовка проверяется тоже, и до тела`() {
        // Тот же предел с другой стороны: одиннадцать тегов в `x-amz-tagging` до M-176 просто
        // становились одиннадцатью тегами объекта, потому что считал их только разбор документа.
        // Отказ виден из заголовков, значит и стоить он должен ноль (§1.2).
        s3.createBucket("photos")

        val answer =
            s3.put(
                "photos",
                "a.txt",
                "body",
                headers = listOf("x-amz-tagging" to (1..11).joinToString("&") { "k$it=v" }),
            )

        assertEquals(400, answer.status, answer.text)
        assertContains(answer.text, "InvalidTag")
        assertEquals(404, s3.get("photos", "a.txt").status, "объект не должен был появиться")
    }

    @Test
    fun `теги отвечают в порядке ключа, каким бы способом их ни положили`() {
        // Набор неупорядочен, а документ — нет. Одно и то же множество, положенное заголовком и
        // документом, обязано читаться одинаково.
        s3.createBucket("photos")
        s3.put("photos", "header.txt", "body", headers = listOf("x-amz-tagging" to "foo=1&bar=2"))
        s3.put("photos", "document.txt", "body")
        s3.send("PUT", "/photos/document.txt", query = "tagging", body = tagging(listOf("foo" to "1", "bar" to "2")))

        val fromHeader = s3.send("GET", "/photos/header.txt", query = "tagging").text
        val fromDocument = s3.send("GET", "/photos/document.txt", query = "tagging").text

        assertEquals(fromHeader, fromDocument)
        assertTrue(fromHeader.indexOf("<Key>bar</Key>") < fromHeader.indexOf("<Key>foo</Key>"), fromHeader)
    }
}
