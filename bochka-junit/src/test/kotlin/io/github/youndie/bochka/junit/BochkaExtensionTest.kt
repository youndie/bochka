package io.github.youndie.bochka.junit

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extension is checked the one way it can be checked at all: **by two tests in a row**.
 *
 * A single test cannot tell "it was reset between tests" from "there was never anything there"; the
 * order here is stated explicitly, because the first test's assertion is about what the second
 * sees.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BochkaExtensionTest {
    companion object {
        @JvmField
        @RegisterExtension
        val bochka = BochkaExtension()

        /** The port the first test saw: the second's assertion is that it did not change. */
        private var port = 0
    }

    @Test
    @Order(1)
    fun `the first test leaves an object and a port behind`() {
        bochka.bochka.put("photos", "a.txt", "первый".toByteArray())

        assertEquals(1, bochka.bochka.objectCount)
        assertTrue(bochka.endpoint.startsWith("http://127.0.0.1:"))
        port = bochka.bochka.port
    }

    @Test
    @Order(2)
    fun `the second sees a clean store and the same server`() {
        assertEquals(0, bochka.bochka.objectCount, "the first test's object should have disappeared")
        assertEquals(emptyList(), bochka.bochka.bucketNames)
        assertEquals(port, bochka.bochka.port, "the same server: the start is paid for once per class")
    }
}
