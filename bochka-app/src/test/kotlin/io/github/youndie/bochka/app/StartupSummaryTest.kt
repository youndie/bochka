package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.RecordLog
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The line a slow start has to explain itself with (M-293).
 *
 * What is checked here is not the wording but the arithmetic and the silence: the two parts have
 * to add up to the total, and the sentence about a torn tail must not appear when there was none.
 * A start line that always mentions a torn tail is one nobody reads by the second week.
 */
class StartupSummaryTest {
    private fun clean(records: Long) =
        RecordLog.Recovery(
            records = records,
            acceptedBytes = 4096,
            fileBytes = 4096,
            stoppedBy = RecordLog.Stop.CLEAN,
        )

    @Test
    fun `the parts add up to the total`() {
        val line = StartupSummary.of(totalMillis = 900, recoveryMillis = 640, recovery = clean(12_345))

        assertContains(line, "started in 900 ms")
        assertContains(line, "640 ms reading 12345 index records")
        assertContains(line, "260 ms everything else")
    }

    @Test
    fun `a clean start says nothing about a torn tail`() {
        val line = StartupSummary.of(totalMillis = 120, recoveryMillis = 10, recovery = clean(3))

        assertFalse("torn" in line, "a clean start mentioned a torn tail: $line")
    }

    @Test
    fun `a discarded tail is named, because it is what the last stop left`() {
        val torn =
            RecordLog.Recovery(
                records = 40,
                acceptedBytes = 4000,
                fileBytes = 4096,
                stoppedBy = RecordLog.Stop.TORN_WRITE,
            )

        val line = StartupSummary.of(totalMillis = 200, recoveryMillis = 30, recovery = torn)

        assertContains(line, "96 bytes was discarded")
        assertContains(line, "the last stop was not a clean one")
    }
}
