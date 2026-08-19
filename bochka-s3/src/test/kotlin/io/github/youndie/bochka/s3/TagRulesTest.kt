package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Пределы набора тегов: `s3-service-2.json:1463` («maximum number of tags to 10 tags per object»)
 * и страница ограничений тегов — 128 символов на ключ, 256 на значение.
 */
class TagRulesTest {
    @Test
    fun `десять тегов можно, одиннадцать нельзя`() {
        assertNull(TagRules.check((1..10).associate { "k$it" to "v" }))
        assertEquals(TagRules.Rejection.TOO_MANY, TagRules.check((1..11).associate { "k$it" to "v" }))
    }

    @Test
    fun `предел на ключ и на значение — это предел, а не запрет`() {
        // `test_put_max_kvsize_tags:12087` кладёт ровно 128 и 256 и ждёт успеха; соседние два
        // кейса добавляют по одному символу и ждут отказа. Проверять надо обе стороны границы:
        // проверка «больше или равно» прошла бы половину сьюта и завалила вторую.
        assertNull(TagRules.check(mapOf("a".repeat(128) to "b".repeat(256))))
        assertEquals(TagRules.Rejection.KEY_TOO_LONG, TagRules.check(mapOf("a".repeat(129) to "b")))
        assertEquals(TagRules.Rejection.VALUE_TOO_LONG, TagRules.check(mapOf("a" to "b".repeat(257))))
    }

    @Test
    fun `считаются символы, а не байты`() {
        // «128 Unicode characters» — и разница видна только вне ASCII, то есть только там, где
        // никто не проверяет. Сто двадцать восемь эмодзи вне BMP — это 512 байт UTF-8 и 256
        // единиц UTF-16, и оба числа дали бы отказ там, где S3 отвечает успехом.
        val emoji = "😀".repeat(128)
        assertEquals(256, emoji.length, "суррогатные пары: длина в UTF-16 вдвое больше")
        assertNull(TagRules.check(mapOf(emoji to "v")))
        assertEquals(TagRules.Rejection.KEY_TOO_LONG, TagRules.check(mapOf(emoji + "😀" to "v")))
    }

    @Test
    fun `пустое значение можно, пустой ключ нельзя`() {
        // Пустое значение — законный тег: `x-amz-tagging: foo=bar&bar` именно такой и шлёт.
        assertNull(TagRules.check(mapOf("bar" to "")))
        assertEquals(TagRules.Rejection.EMPTY_KEY, TagRules.check(mapOf("" to "v")))
    }
}
