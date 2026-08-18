package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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

    private fun tagging(vararg pairs: Pair<String, String>) =
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
}
