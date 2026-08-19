package io.github.youndie.bochka.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Объявление сборщика и границы замеренного (M-156, M-157).
 *
 * Веха M22 решила **форму ответа** раньше, чем получила числа, и проверяется здесь именно она.
 * Молчаливый порог по `-Xmx` означал бы, что 511 и 513 МиБ ведут себя по-разному и ни один лог
 * об этом не говорит; меню профилей — это N обещаний вместо одного, каждое из которых надо было
 * измерить. Остаётся одна поставка плюс два свойства: решение **объявляется**, а выход за конверт
 * **говорит о себе** — и говорит громко, но не отказом.
 */
class GcProfileTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun `the shipped profile says nothing beyond naming itself`() {
        assertNull(GcProfile.beyondWhatWasMeasured("Serial", 494 * 1024 * 1024L))
        assertEquals("collector: Serial at 494 MiB of heap", GcProfile.describe("Serial", 494 * 1024 * 1024L))
    }

    @Test
    fun `a larger heap is a note about the pause, not a refusal`() {
        // The difference from the object ceiling is deliberate: going over that is a refusal to
        // start, because the server cannot then do what it says. Here it can, only worse.
        val said = assertNotNull(GcProfile.beyondWhatWasMeasured("Serial", 4 * gib))

        assertContains(said, "4096 MiB")
        assertContains(said, "7.56")
        assertContains(said, "not a refusal")
    }

    @Test
    fun `another collector is a note about the ceiling, which is a different failure`() {
        // The published ceiling is derived from Runtime.maxMemory(), and that is a property of the
        // collector: 455, 494 and 512 MiB at one -Xmx512M. Somebody sized a deployment by that
        // number, so the line says which number moved rather than that something is unusual.
        val said = assertNotNull(GcProfile.beyondWhatWasMeasured("G1", 512 * 1024 * 1024L))

        assertContains(said, "ceiling")
        assertContains(said, "494")
    }

    @Test
    fun `the collector is named before the heap is judged`() {
        // Both conditions can hold at once — a foreign collector on a large heap — and the one
        // reported is the one that moves a published number rather than a pause.
        assertContains(assertNotNull(GcProfile.beyondWhatWasMeasured("G1", 4 * gib)), "not the one")
    }

    @Test
    fun `this very process reports a collector by a name the check can use`() {
        // The gate runs under -XX:+UseSerialGC (build.gradle.kts), so the name this reads is the
        // one the distribution ships with. Without this the parsing of the bean names is asserted
        // nowhere and would break silently on a JDK that renames one.
        assertEquals("Serial", GcProfile.collector())
    }
}
