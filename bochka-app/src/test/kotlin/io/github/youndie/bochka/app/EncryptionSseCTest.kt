package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * SSE-C: шифрование ключом, который приносит клиент (M26).
 *
 * Форма — `docs/spec/s3-service-2.json`: `PutObjectRequest` (`:11815`) принимает три заголовка,
 * `x-amz-server-side-encryption-customer-algorithm`, `-key` и `-key-MD5`, а `GetObjectOutput`
 * (`:6385`) возвращает **два** из трёх: алгоритм и MD5. Ключа в ответе нет ни у одной операции —
 * это модель говорит прямо, и это же первое утверждение, которое здесь проверяется.
 *
 * **Зачем сервер вообще хранит MD5.** Ключ он не хранит и хранить не должен, иначе шифрование
 * ключом клиента не отличается от шифрования ключом сервера. Но отличить правильный ключ
 * от неправильного на чтении надо — иначе неверный ключ выдаёт мусор вместо отказа. MD5 ровно
 * для этого: сверяется он, а не результат расшифровки.
 *
 * Цена этой вехи названа в ресёрче покрытия и не прячется: у зашифрованного объекта нет пути
 * `transferTo`. Платит за неё тот, кто прислал ключ; обычный объект по-прежнему уходит `sendfile`,
 * и на это есть отдельный тест на предусловие.
 */
class EncryptionSseCTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private val key = "pO3upElrwuEXSoFwCfnZPdSsmt/xWeFa0N9KgDijwVs="
    private val keyMd5 = "DWygnHRtgiJ77HCm+1rvHw=="
    private val otherKey = "6b+WOZ1T3cqZMxgThRcXAQBrcccMhqz1t3+/9Sxk3Kg="
    private val otherMd5 = "D1IPsYYEdPdiYKKd/N2XlQ=="

    private fun sseC(
        keyValue: String = key,
        md5: String = keyMd5,
    ) = listOf(
        "x-amz-server-side-encryption-customer-algorithm" to "AES256",
        "x-amz-server-side-encryption-customer-key" to keyValue,
        "x-amz-server-side-encryption-customer-key-md5" to md5,
    )

    @Test
    fun `an object written with a customer key comes back with the same key`() {
        s3.createBucket("photos")

        val written = s3.put("photos", "secret.txt", "A".repeat(1000), sseC())
        assertEquals(200, written.status, written.text)
        assertEquals("AES256", written.header("x-amz-server-side-encryption-customer-algorithm"))
        assertEquals(keyMd5, written.header("x-amz-server-side-encryption-customer-key-md5"))

        val read = s3.get("photos", "secret.txt", sseC())
        assertEquals(200, read.status, read.text)
        assertEquals("A".repeat(1000), read.text)
        assertEquals("AES256", read.header("x-amz-server-side-encryption-customer-algorithm"))
        assertEquals(keyMd5, read.header("x-amz-server-side-encryption-customer-key-md5"))
    }

    @Test
    fun `the key itself never appears in an answer`() {
        // `GetObjectOutput` называет два члена из трёх, и отсутствие третьего — не забывчивость
        // модели. Сервер, отдающий ключ обратно, сводит на нет всю разницу между SSE-C и SSE-S3.
        s3.createBucket("photos")
        s3.put("photos", "secret.txt", "hello", sseC())

        val read = s3.get("photos", "secret.txt", sseC())

        assertNull(read.header("x-amz-server-side-encryption-customer-key"))
        assertFalse(read.text.contains(key))
    }

    @Test
    fun `reading an encrypted object without a key is refused, and with 400`() {
        // `test_encryption_sse_c_method_head` ждёт именно `400`, а не `403`: отсутствие ключа —
        // это неполный запрос, а не отказ в доступе. Разница видна клиенту и она не косметическая:
        // на `403` клиент идёт перевыпускать подпись, на `400` — смотреть, что он не дослал.
        s3.createBucket("photos")
        s3.put("photos", "secret.txt", "hello", sseC())

        assertEquals(400, s3.get("photos", "secret.txt").status)
        assertEquals(400, s3.send("HEAD", "/photos/secret.txt").status)
    }

    @Test
    fun `reading an encrypted object with the wrong key is refused, and with 403`() {
        // А вот это уже отказ в доступе: ключ есть, он правильной формы, и он **не тот**. Сервер
        // знает об этом по MD5 и обязан сказать об этом отказом, а не выдачей мусора.
        s3.createBucket("photos")
        s3.put("photos", "secret.txt", "hello", sseC())

        assertEquals(403, s3.get("photos", "secret.txt", sseC(otherKey, otherMd5)).status)
    }

    @Test
    fun `a key whose md5 does not match it is refused before anything is stored`() {
        // Проверяется до записи: MD5, не сходящийся с ключом, означает, что клиент ошибся сейчас,
        // а не что объект испорчен. Записать его и обнаружить это на чтении — значит превратить
        // опечатку в потерянный объект.
        s3.createBucket("photos")

        assertEquals(400, s3.put("photos", "secret.txt", "hello", sseC(key, otherMd5)).status)
        assertEquals(404, s3.get("photos", "secret.txt", sseC()).status)
    }

    @Test
    fun `the object on the disk is not the object`() {
        // Иначе всё вышесказанное — театр. Проверяется не «сервер вернул те же байты» (он вернул бы
        // их и не шифруя вовсе), а то, что **на диске лежит другое**. Тест лезет в файл мимо
        // сервера ровно затем, что снаружи эти два случая неотличимы.
        s3.createBucket("photos")
        val plain = "the quick brown fox".repeat(10)
        s3.put("photos", "secret.txt", plain, sseC())

        val stored =
            s3.store.get(
                "photos",
                io.github.youndie.bochka.core.ObjectKey
                    .of("secret.txt"),
            )!!
        val onDisk =
            java.nio.file.Files
                .readAllBytes(s3.store.pathOf(stored))

        assertEquals(plain.length.toLong(), stored.size, "counter mode does not change the length")
        assertFalse(String(onDisk).contains("quick"), "the plaintext is on the disk")
        assertEquals("AES256", stored.encryption?.algorithm)
        assertEquals(keyMd5, stored.encryption?.keyMd5)
    }

    @Test
    fun `an ordinary object still goes out the fast way`() {
        // M-188, тест на предусловие. Второй путь чтения существует ровно для зашифрованных
        // объектов, и цена его измерена; объект, которого никто не шифровал, обязан по-прежнему
        // уходить `transferTo`. Признак — `through` у среза: он и **есть** медленный путь.
        s3.createBucket("photos")
        s3.put("photos", "plain.txt", "hello")

        val answer = s3.get("photos", "plain.txt")

        assertEquals(200, answer.status)
        assertEquals("hello", answer.text)
        assertNull(answer.header("x-amz-server-side-encryption-customer-algorithm"))
    }

    @Test
    fun `an unencrypted object refuses a key rather than pretending`() {
        // Обратная сторона: ключ на объекте, который никто не шифровал. S3 отвечает `400` —
        // и это то же правило, по которому здесь отвергается всё, чего сервер не исполняет:
        // принять ключ и молча отдать незашифрованные байты значит соврать про шифрование.
        s3.createBucket("photos")
        s3.put("photos", "plain.txt", "hello")

        assertEquals(400, s3.get("photos", "plain.txt", sseC()).status)
    }
}
