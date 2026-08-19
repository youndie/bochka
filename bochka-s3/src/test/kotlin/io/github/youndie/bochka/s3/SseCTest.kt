package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Три заголовка SSE-C и то, чем они обязаны быть (M-185, M-187).
 *
 * Форма — `docs/spec/s3-service-2.json:11815`. Отдельно и подробно проверяется смещение: у
 * счётчикового режима любой байт расшифровывается без предыдущих, и **на этом держится `Range`**.
 * Ошибка здесь не видна ни на одном тесте, читающем объект целиком, — и это ровно тот случай,
 * когда «работает» и «верно» расходятся молча.
 */
class SseCTest {
    private val key = "pO3upElrwuEXSoFwCfnZPdSsmt/xWeFa0N9KgDijwVs="
    private val keyMd5 = "DWygnHRtgiJ77HCm+1rvHw=="

    private fun headers(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    @Test
    fun `no headers at all is not encryption and not an error`() {
        assertNull(SseC.of(headers()))
    }

    @Test
    fun `the three headers together are one value, and the key is not kept as text`() {
        val sse =
            SseC.of(
                headers(
                    SseC.ALGORITHM_HEADER to "AES256",
                    SseC.KEY_HEADER to key,
                    SseC.KEY_MD5_HEADER to keyMd5,
                ),
            )!!

        assertEquals("AES256", sse.algorithm)
        assertEquals(keyMd5, sse.keyMd5)
        assertEquals(32, sse.keyBytes.size)
    }

    @Test
    fun `a partial set is refused rather than read as unencrypted`() {
        // Алгоритм без ключа — это не «без шифрования», это клиент, который считает, что шифрует.
        val refused =
            assertFailsWith<SseC.Refused> {
                SseC.of(headers(SseC.ALGORITHM_HEADER to "AES256"))
            }
        assertTrue(refused.detail.contains(SseC.KEY_HEADER), refused.detail)
    }

    @Test
    fun `an md5 that does not describe the key is refused`() {
        assertFailsWith<SseC.Refused> {
            SseC.of(
                headers(
                    SseC.ALGORITHM_HEADER to "AES256",
                    SseC.KEY_HEADER to key,
                    SseC.KEY_MD5_HEADER to "arxBRt5DAlxT3Ci7L/mahw==",
                ),
            )
        }
    }

    @Test
    fun `an algorithm nobody named is refused by name`() {
        val refused =
            assertFailsWith<SseC.Refused> {
                SseC.of(
                    headers(
                        SseC.ALGORITHM_HEADER to "AES128",
                        SseC.KEY_HEADER to key,
                        SseC.KEY_MD5_HEADER to keyMd5,
                    ),
                )
            }
        assertTrue(refused.detail.contains("AES128"), refused.detail)
    }

    @Test
    fun `a key of the wrong length is refused, and the length is in the message`() {
        val short =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(16))
        val md5 =
            java.util.Base64
                .getEncoder()
                .encodeToString(
                    java.security.MessageDigest
                        .getInstance("MD5")
                        .digest(ByteArray(16)),
                )

        val refused =
            assertFailsWith<SseC.Refused> {
                SseC.of(
                    headers(
                        SseC.ALGORITHM_HEADER to "AES256",
                        SseC.KEY_HEADER to short,
                        SseC.KEY_MD5_HEADER to md5,
                    ),
                )
            }
        assertTrue(refused.detail.contains("16"), refused.detail)
    }

    @Test
    fun `decryption from an offset gives the same bytes as decryption from the start`() {
        // Свойство, на котором держится `Range`: байт номер N расшифровывается без байтов до него.
        // Проверяется на смещениях, которые не кратны блоку, потому что кратные работают и у
        // неверной реализации — она просто пропускает целые блоки.
        val sse =
            SseC(
                SseC.AES256,
                java.util.Base64
                    .getDecoder()
                    .decode(key),
                keyMd5,
            )
        val iv = SseC.newIv()
        val plain = ByteArray(1000) { (it % 251).toByte() }

        val encrypted = sse.cipherAt(iv, 0).doFinal(plain)
        assertEquals(plain.size, encrypted.size, "counter mode does not change the length")

        for (offset in listOf(0, 1, 15, 16, 17, 100, 999)) {
            val tail = encrypted.copyOfRange(offset, encrypted.size)
            val decrypted = sse.cipherAt(iv, offset.toLong()).doFinal(tail)
            assertContentEquals(plain.copyOfRange(offset, plain.size), decrypted, "at offset $offset")
        }
    }

    @Test
    fun `a different key gives different bytes`() {
        val sse =
            SseC(
                SseC.AES256,
                java.util.Base64
                    .getDecoder()
                    .decode(key),
                keyMd5,
            )
        val other = SseC(SseC.AES256, ByteArray(32) { 7 }, "whatever")
        val iv = SseC.newIv()
        val plain = "hello".toByteArray()

        val mine = sse.cipherAt(iv, 0).doFinal(plain)
        val theirs = other.cipherAt(iv, 0).doFinal(plain)

        assertTrue(!mine.contentEquals(theirs))
    }
}
