package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `reading an encrypted object with the wrong key is refused, and with 400`() {
        // **Здесь стояло 403, и это было рассуждение, а не факт.** Рассуждение звучало так: ключ,
        // который не открывает объект, — это отказ в доступе. Сьют говорит `400`
        // (`test_encryption_sse_c_other_key`, без пометки `fails_on_aws`), модель про код молчит,
        // и значит решает сьют.
        //
        // На второй взгляд он и прав: доступ здесь решает **подпись**, а она у этого запроса
        // верная. Ключ — параметр запроса, и параметр, который не может сделать свою работу, —
        // это плохой запрос, тот же ответ, что у неверной контрольной суммы. `403` отправил бы
        // клиента перевыпускать подпись, с которой всё в порядке.
        s3.createBucket("photos")
        s3.put("photos", "secret.txt", "hello", sseC())

        assertEquals(400, s3.get("photos", "secret.txt", sseC(otherKey, otherMd5)).status)
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
    fun `a multipart object is encrypted part by part and reads back whole`() {
        // M-189. У каждой части свой IV, и объект из них собирается конкатенацией шифротекстов —
        // значит на чтении шифр обязан переключаться на швах. Части здесь разного размера
        // специально: при одинаковых ошибка в арифметике границ не видна.
        s3.createBucket("photos")
        val first = "A".repeat(5 * 1024 * 1024)
        val second = "B".repeat(1024)

        val started = s3.send("POST", "/photos/big.bin", query = "uploads", headers = sseC())
        assertEquals(200, started.status, started.text)
        val uploadId = Regex("<UploadId>([^<]+)</UploadId>").find(started.text)!!.groupValues[1]

        val p1 =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                headers = sseC(),
                body = first.toByteArray(),
            )
        val p2 =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=2&uploadId=$uploadId",
                headers = sseC(),
                body = second.toByteArray(),
            )
        assertEquals(200, p1.status, p1.text)
        assertEquals(200, p2.status, p2.text)

        val completion =
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>${p1.header("ETag")}</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>${p2.header("ETag")}</ETag></Part>" +
                "</CompleteMultipartUpload>"
        val done = s3.send("POST", "/photos/big.bin", query = "uploadId=$uploadId", body = completion.toByteArray())
        assertEquals(200, done.status, done.text)

        val read = s3.get("photos", "big.bin", sseC())
        assertEquals(200, read.status, read.text)
        assertEquals(first + second, read.text)
    }

    @Test
    fun `a part that does not carry the upload's key is refused`() {
        // И отказ приходит **из screen**, до чтения тела: ответ после того, как часть уже поехала,
        // означает, что сервер закрывает соединение, пока клиент ещё пишет пять мебибайт. Обе
        // стороны ждут, и сьют показывает это таймаутом, а не отказом. Так и было, пока проверка
        // стояла в обработчике.
        s3.createBucket("photos")
        val started = s3.send("POST", "/photos/big.bin", query = "uploads", headers = sseC())
        val uploadId = Regex("<UploadId>([^<]+)</UploadId>").find(started.text)!!.groupValues[1]

        val wrong =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                headers = sseC(otherKey, otherMd5),
                body = "x".toByteArray(),
            )
        assertEquals(400, wrong.status)

        val none =
            s3.send(
                "PUT",
                "/photos/big.bin",
                query = "partNumber=1&uploadId=$uploadId",
                body = "x".toByteArray(),
            )
        assertEquals(400, none.status)
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

    @Test
    fun `отправленная дважды часть получает тот же ETag`() {
        // M-190а. IV у каждой отправки свой, поэтому ETag по шифротексту у одних и тех же байтов
        // выходил разный — а `ceph/s3-tests` (`test_multipart_sse_c_get_part`) записывает ETag
        // **первой** отправки, посылает часть повторно и завершает загрузку старым значением.
        // Кейс не помечен как падающий на AWS, то есть там повторная отправка детерминирована.
        //
        // Это не про один кейс сьюта: клиент, повторивший успешную отправку части, иначе получает
        // отказ на завершении, а причина — случайное число внутри сервера.
        val b = "photos"
        s3.createBucket(b)
        val upload = s3.send("POST", "/$b/big.bin", query = "uploads", headers = sseC())
        val id = upload.text.substringAfter("<UploadId>").substringBefore("</UploadId>")
        val part = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }

        val first = s3.send("PUT", "/$b/big.bin", query = "partNumber=1&uploadId=$id", headers = sseC(), body = part)
        val again = s3.send("PUT", "/$b/big.bin", query = "partNumber=1&uploadId=$id", headers = sseC(), body = part)

        assertEquals(200, first.status, first.text)
        assertEquals(200, again.status, again.text)
        assertEquals(first.header("ETag"), again.header("ETag"), "те же байты под тем же ключом — тот же ETag")
    }

    @Test
    fun `ETag зашифрованного объекта не выдаёт его содержимого`() {
        // Листинг отдаёт ETag и ключа не требует, поэтому ETag, равный MD5 открытого текста, дал бы
        // любому, кто может листать бакет, способ подтвердить догадку о содержимом. Отсюда HMAC
        // ключом клиента: детерминированный для того, у кого ключ, и непрозрачный для всех прочих.
        val b = "photos"
        s3.createBucket(b)
        val body = "содержимое, о котором можно догадаться".toByteArray()
        val plainMd5 =
            java.security.MessageDigest
                .getInstance("MD5")
                .digest(body)
                .joinToString("") { "%02x".format(it) }

        s3.put(b, "secret.txt", String(body), headers = sseC())
        val listed = s3.send("GET", "/$b", query = "list-type=2")

        assertTrue("secret.txt" in listed.text, listed.text)
        assertTrue(plainMd5 !in listed.text, "листинг не должен раздавать MD5 открытого текста")

        // И тот же объект без шифра — обычный MD5, потому что там его и ждут.
        s3.put(b, "open.txt", String(body))
        assertEquals("\"$plainMd5\"", s3.get(b, "open.txt").header("ETag"))
    }

    @Test
    fun `POST-форма шифрует и даёт тот же ETag, что обычная запись`() {
        // Третий путь, который шифрует, и до M-190а у него не было ни одного теста вовсе — то есть
        // правку ETag там никто бы не проверил. Равенство с обычной записью проверяет сразу две
        // вещи: что форма прошла через тот же MAC, и что MAC зависит только от байтов и ключа.
        val b = "photos"
        s3.createBucket(b)
        val body = "то же самое, двумя дорогами".toByteArray()

        val put = s3.put(b, "by-put.bin", String(body), headers = sseC())
        val posted =
            s3.postForm(
                b,
                s3.signedPolicy(
                    buildString {
                        val open = { field: String -> """["starts-with","${'$'}$field",""]""" }
                        append("""{"expiration":"2099-01-01T00:00:00Z","conditions":[""")
                        append("""{"bucket":"$b"},""")
                        append(open("key"))
                        for (name in sseC()) append(",").append(open(name.first))
                        append("]}")
                    },
                ) + listOf("key" to "by-form.bin") + sseC(),
                body,
            )

        assertEquals(200, put.status, put.text)
        assertEquals(204, posted.status, posted.text)
        assertEquals(put.header("ETag"), s3.send("HEAD", "/$b/by-form.bin", headers = sseC()).header("ETag"))
    }

    @Test
    fun `часть вне диапазона у зашифрованного объекта — InvalidPart`() {
        val b = "photos"
        s3.createBucket(b)
        val upload = s3.send("POST", "/$b/big.bin", query = "uploads", headers = sseC())
        val id = upload.text.substringAfter("<UploadId>").substringBefore("</UploadId>")
        val part = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val one = s3.send("PUT", "/$b/big.bin", query = "partNumber=1&uploadId=$id", headers = sseC(), body = part)
        val two =
            s3.send(
                "PUT",
                "/$b/big.bin",
                query = "partNumber=2&uploadId=$id",
                headers = sseC(),
                body = "хвост".toByteArray(),
            )
        val completion =
            "<CompleteMultipartUpload>" +
                "<Part><PartNumber>1</PartNumber><ETag>${one.header("ETag")}</ETag></Part>" +
                "<Part><PartNumber>2</PartNumber><ETag>${two.header("ETag")}</ETag></Part>" +
                "</CompleteMultipartUpload>"
        // Заголовки ключа и на завершении — так их шлёт сьют (`**get_args`), и это единственное,
        // чем его запрос отличается от очевидного.
        val done =
            s3.send("POST", "/$b/big.bin", query = "uploadId=$id", headers = sseC(), body = completion.toByteArray())
        assertEquals(200, done.status, done.text)

        // **Без ключа**, как это делает сьют: номер части проверяется раньше, чем требуется ключ.
        val outOfRange = s3.send("GET", "/$b/big.bin", query = "partNumber=5")

        assertEquals(400, outOfRange.status, outOfRange.text)
        assertTrue("InvalidPart" in outOfRange.text, outOfRange.text)
    }
}
