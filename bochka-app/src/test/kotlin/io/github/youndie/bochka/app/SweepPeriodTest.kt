package io.github.youndie.bochka.app

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How often the lifecycle sweep runs is derived, not configured (M-174).
 *
 * A second setting beside the length of a "day" would be a fourth way of saying the same thing and
 * the first way of making the two disagree: a day of one second swept once an hour means rules
 * that are simply not carried out, and neither setting looks wrong on its own. So the period is a
 * tenth of the day, held between a second and an hour.
 *
 * The first test below is the one that matters. Both examples the code's own paragraph names —
 * twenty-four hours and the five seconds a test uses — land on a clamp, so a check made of them
 * alone stays green for every divisor from five to twenty-four: measured, with the tenth replaced
 * by a fifth, only the ten-minute day went red.
 */
class SweepPeriodTest {
    @Test
    fun `a day of ten minutes is swept every minute`() {
        // Between the clamps, where the tenth is the only thing deciding the answer.
        assertEquals(Duration.ofSeconds(60), Main.sweepPeriod(Duration.ofMinutes(10)))
    }

    @Test
    fun `a full day is held at an hour rather than swept every 2 point 4`() {
        assertEquals(Duration.ofHours(1), Main.sweepPeriod(Duration.ofSeconds(86400)))
    }

    @Test
    fun `the shortened day a test uses is held at a second`() {
        assertEquals(Duration.ofSeconds(1), Main.sweepPeriod(Duration.ofSeconds(5)))
    }
}
