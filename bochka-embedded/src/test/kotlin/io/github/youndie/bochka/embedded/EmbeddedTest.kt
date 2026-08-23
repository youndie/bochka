package io.github.youndie.bochka.embedded

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * То, ради чего этот модуль вообще берут в чужой проект (M15).
 *
 * Веха не двигает процент `ceph/s3-tests` ни на один кейс — там нет тестов на удобство, — поэтому
 * приёмка у неё здесь: работающий сценарий, а не число.
 *
 * Запросы здесь неподписанные, и по HTTP проверяется ровно то, что до подписи и доходит:
 * заготовленный отказ. Всё остальное утверждается через API самого двойника — иначе тест,
 * упавший на подписи, ничего не скажет ни про сброс, ни про заготовку. Как сервер отвечает
 * на подписанный запрос, проверено в `bochka-app` настоящим клиентом на настоящем сокете.
 */
class EmbeddedTest {
    private fun head(
        bochka: Bochka,
        path: String,
    ): Int {
        val client = HttpClient.newHttpClient()
        val request =
            HttpRequest
                .newBuilder(URI.create(bochka.endpoint + path))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        return client.send(request, BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `сброс забывает всё, не перезапуская сервер`() {
        // M-95, и это первое, что спрашивают, когда тестов становится больше десятка. Перезапуск
        // на каждый тест стоит нового стора и нового сокета; сброс стоит очистки структур.
        Bochka.start().use { bochka ->
            bochka.put("photos", "a.txt", "первый".toByteArray())
            bochka.put("photos", "b.txt", "второй".toByteArray())
            assertEquals(2, bochka.objectCount)
            val portBefore = bochka.port

            bochka.reset()

            assertEquals(0, bochka.objectCount)
            assertEquals(portBefore, bochka.port, "порт не меняется: сброс — это не перезапуск")
            assertEquals(emptyList(), bochka.bucketNames, "бакеты уходят вместе с объектами")
        }
    }

    @Test
    fun `после сброса можно класть заново, и место на диске не растёт бесконечно`() {
        Bochka.start().use { bochka ->
            repeat(3) { round ->
                bochka.put("photos", "a.txt", "раунд $round".toByteArray())
                assertEquals(1, bochka.objectCount)
                bochka.reset()
            }
            bochka.put("photos", "a.txt", "последний".toByteArray())

            assertEquals(1, bochka.objectCount)
            // Сброс не должен оставлять файлы: тест, гоняющий тысячу раундов, иначе заполнит диск
            // тем, на что никто не смотрит.
            //
            // `.lock` исключён вместе с журналом и по той же причине: это не хранилище, а заявка
            // на каталог (M-224), и она обязана пережить сброс — стор в этот момент открыт, и
            // удалить её значило бы впустить второй процесс в середину чужой жизни.
            val files =
                bochka.dataDirectory
                    .toFile()
                    .walkTopDown()
                    .filter { it.isFile && it.name != "index.log" && it.name != ".lock" }
                    .count()
            assertTrue(files <= 1, "после сброса остались файлы: $files")
        }
    }

    @Test
    fun `заготовка кладётся до первого запроса`() {
        // M-98: тест начинается с состояния, а не с десяти вызовов SDK ради него.
        Bochka.start().use { bochka ->
            bochka.put("fixtures", "hello.txt", "привет".toByteArray())

            assertEquals(1, bochka.objectCount)
            assertEquals(listOf("fixtures"), bochka.bucketNames, "бакет заводится под объект сам")
        }
    }

    @Test
    fun `управляемый отказ отвечает на следующий запрос и снимается сам`() {
        // M-99, и это единственное, в чём мок структурно сильнее настоящего сервера: заставить
        // клиент пережить `503` иначе нечем. Клиентский код, который никто не может уронить,
        // на повторах не проверен.
        Bochka.start().use { bochka ->
            bochka.failNext(503)

            assertEquals(503, head(bochka, "/anything"))
            assertTrue(head(bochka, "/anything") != 503, "отказ был один и снялся сам")
        }
    }

    @Test
    fun `отказ можно задать на несколько запросов подряд`() {
        Bochka.start().use { bochka ->
            bochka.failNext(500, times = 2)

            assertEquals(500, head(bochka, "/anything"))
            assertEquals(500, head(bochka, "/anything"))
            assertTrue(head(bochka, "/anything") != 500)
        }
    }

    @Test
    fun `статус, которому двойник не может назвать код, отвергается`() {
        // M-231, и это то же правило, по которому сервер отвергает непринимаемую политику: ответ,
        // принятый и названный неверно, узнаётся не ошибкой, а тестом, который прошёл зря.
        // `404` двойник назвать не может — отказ вводится до разбора запроса, и `NoSuchBucket`
        // от `NoSuchKey` он в этот момент не отличает.
        Bochka.start().use { bochka ->
            val refusal = assertFailsWith<IllegalArgumentException> { bochka.failNext(404) }

            assertTrue(refusal.message!!.contains("404"), refusal.message!!)
            assertTrue(refusal.message!!.contains("503"), "the message names what can be ordered")
        }
    }

    @Test
    fun `сброс снимает и заготовленные отказы`() {
        // Иначе отказ, заведённый в одном тесте, срабатывает в следующем — и ищут его там, где
        // его не заводили.
        Bochka.start().use { bochka ->
            bochka.failNext(503, times = 5)
            bochka.reset()

            assertTrue(head(bochka, "/anything") != 503)
        }
    }
}
