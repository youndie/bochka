package io.github.youndie.bochka.junit

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Расширение проверяется тем единственным способом, каким его вообще можно проверить: **двумя
 * тестами подряд**.
 *
 * Одиночный тест не отличит «сбросили между тестами» от «ничего и не было»; порядок здесь задан
 * явно, потому что утверждение первого теста — про то, что видит второй.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BochkaExtensionTest {
    companion object {
        @JvmField
        @RegisterExtension
        val bochka = BochkaExtension()

        /** Порт, увиденный первым тестом: утверждение второго — про то, что он не менялся. */
        private var port = 0
    }

    @Test
    @Order(1)
    fun `первый тест оставляет объект и порт`() {
        bochka.bochka.put("photos", "a.txt", "первый".toByteArray())

        assertEquals(1, bochka.bochka.objectCount)
        assertTrue(bochka.endpoint.startsWith("http://127.0.0.1:"))
        port = bochka.bochka.port
    }

    @Test
    @Order(2)
    fun `второй видит чистый стор, но тот же сервер`() {
        assertEquals(0, bochka.bochka.objectCount, "объект первого теста должен был исчезнуть")
        assertEquals(emptyList(), bochka.bochka.bucketNames)
        assertEquals(port, bochka.bochka.port, "сервер тот же: платим за старт один раз на класс")
    }
}
