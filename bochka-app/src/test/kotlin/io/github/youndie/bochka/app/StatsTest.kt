package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the store is holding, visible before the refusal rather than through it (M-291).
 *
 * The ceiling is published and the `507` at it is honest, and neither helps somebody who first
 * hears about them on a Tuesday afternoon. `GET /-/stats` answers with the numbers an operator
 * watches — and the numbers move, which is the half a test has to check: a constant is easy to
 * print and says nothing.
 */
class StatsTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    @Test
    fun `the numbers are there and they move`() {
        val before = s3.send("GET", "/-/stats")
        assertEquals(200, before.status, before.text)
        assertContains(before.text, "objects=0")
        assertContains(before.text, "object-ceiling=")
        assertContains(before.text, "compaction-last=never")
        assertContains(before.text, "orphans-last-sweep=never")

        s3.createBucket("photos")
        s3.put("photos", "a.txt", "content")

        val after = s3.send("GET", "/-/stats")
        assertContains(after.text, "objects=1", message = "the count did not move after a write")
        // The log grew with the two records the writes made, and the live set with them: a log
        // size on its own is not a fact about anything, which is why both are printed.
        val logBytes =
            after.text
                .lineSequence()
                .first { it.startsWith("log-bytes=") }
                .substringAfter('=')
                .toLong()
        assertTrue(logBytes > 0, "the log is reported as empty after two writes")
        assertContains(after.text, "log-records-live=2")
    }

    @Test
    fun `the numbers are not for anybody who asks`() {
        // The health path is open, because an orchestrator has to reach it and it says one word.
        // How many objects a store holds is a different kind of answer, and it is signed like
        // everything else: the operator has a key already, and a stranger does not need the count.
        val open = s3.unsigned("GET", "/-/stats")

        assertEquals(403, open.status, "an unsigned caller was told how much the store is holding")
        assertEquals(200, s3.unsigned("GET", "/-/healthy").status, "the health path stopped being open")
    }
}
