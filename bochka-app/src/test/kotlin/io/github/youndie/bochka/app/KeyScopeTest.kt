package io.github.youndie.bochka.app

import io.github.youndie.bochka.s3.sigv4.KeyScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Область ключа (M19) — то, что на самом деле спрашивают под словом «права».
 *
 * Почти всё здесь — про **отрицательное**, и это не стиль, а необходимость: ценность такой
 * настройки ровно в том, чего она не даёт сделать. Положительных проверок мало, и полагаться на
 * них нельзя — ключ, который «всё умеет», проходит их все и в режиме `ro` тоже.
 *
 * Внешнее число веха не двигает: у S3 такого понятия нет, и в сьюте на него тестов нет.
 */
class KeyScopeTest {
    private fun readOnly() = KeyScope(KeyScope.Mode.RO)

    private fun scopedTo(vararg buckets: String) = KeyScope(KeyScope.Mode.RW, buckets.toSet())

    @Test
    fun `a read-only key reads and lists`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "a.txt", "тело")
        }
        S3Fixture(scope = readOnly()).use { s3 ->
            // Бакет и объект кладём в обход HTTP: ключ этого фикстура уже не умеет, и это ровно
            // то, что проверяет следующий тест.
            s3.store.createBucket("photos")

            assertEquals(200, s3.send("GET", "/photos", query = "list-type=2").status)
            assertEquals(200, s3.send("GET", "/").status)
            assertEquals(404, s3.get("photos", "a.txt").status, "читать можно, но объекта нет")
        }
    }

    @Test
    fun `a read-only key does not write, delete or finish an upload`() {
        S3Fixture(scope = readOnly()).use { s3 ->
            s3.store.createBucket("photos")

            assertEquals(403, s3.put("photos", "a.txt", "тело").status)
            assertEquals(403, s3.send("DELETE", "/photos/a.txt").status)
            assertEquals(403, s3.send("PUT", "/other").status, "создание бакета — тоже запись")
            assertEquals(403, s3.send("POST", "/photos/a.txt", query = "uploads").status)
            assertEquals(403, s3.send("POST", "/photos", query = "delete").status)
        }
    }

    @Test
    fun `a refused write leaves nothing in the store`() {
        // Отказ принимается в `screen`, то есть по одним заголовкам (§1.2) — соединение при этом
        // закрывается, потому что тело осталось непрочитанным.
        //
        // Тело здесь маленькое **намеренно**. Первая версия слала четыре мебибайта, чтобы «дока-
        // зать», что их не читают, и повесила прогон: сервер отвечает и закрывает соединение,
        // клиент в этот момент ещё пишет, и обе стороны ждут друг друга. То, что отказ приходит
        // до тела, проверяется устройством `screen`, а не размером запроса; здесь проверяется
        // только то, что записи не случилось.
        S3Fixture(scope = readOnly()).use { s3 ->
            s3.store.createBucket("photos")

            val answer = s3.put("photos", "a.txt", "тело")

            assertEquals(403, answer.status)
            assertEquals(0, s3.store.objectCount)
        }
    }

    @Test
    fun `a bucket outside the scope does not exist rather than being refused`() {
        // Невидимость важнее отказа: `AccessDenied` подтверждает, что имя занято, и это уже
        // рассказ о чужом бакете. Поэтому `NoSuchBucket`.
        S3Fixture(scope = scopedTo("photos")).use { s3 ->
            s3.store.createBucket("photos")
            s3.store.createBucket("secrets")

            assertEquals(404, s3.send("GET", "/secrets", query = "list-type=2").status)
            assertEquals(404, s3.get("secrets", "a.txt").status)
            assertEquals(200, s3.send("GET", "/photos", query = "list-type=2").status)
        }
    }

    @Test
    fun `a bucket outside the scope is not in the list either`() {
        S3Fixture(scope = scopedTo("photos")).use { s3 ->
            s3.store.createBucket("photos")
            s3.store.createBucket("secrets")

            val listing = s3.send("GET", "/").text

            assertTrue("photos" in listing, listing)
            assertTrue("secrets" !in listing, "список показал чужой бакет: $listing")
        }
    }

    @Test
    fun `a key nobody narrowed keeps everything`() {
        // Настройка, которая только сужает, не может запереть владельца снаружи собственного
        // стора — поэтому отсутствие записи означает полный доступ, а не пустой.
        S3Fixture().use { s3 ->
            assertEquals(200, s3.createBucket("photos").status)
            assertEquals(200, s3.put("photos", "a.txt", "тело").status)
        }
    }

    @Test
    fun `the scope format is parsed, and a bad one is refused at startup`() {
        assertEquals(
            mapOf(
                "backup" to KeyScope(KeyScope.Mode.RO, setOf("photos", "reports")),
                "app" to KeyScope(KeyScope.Mode.RW),
            ),
            KeyScope.parse(listOf("backup=ro@photos|reports", "app=rw")),
        )
        // Падать при старте, а не молча раздавать не тот доступ: настройку с опечаткой лучше
        // увидеть в отказе подняться.
        assertFailsWith<IllegalArgumentException> { KeyScope.parse(listOf("backup=readonly")) }
        assertFailsWith<IllegalArgumentException> { KeyScope.parse(listOf("backup")) }
    }
}
