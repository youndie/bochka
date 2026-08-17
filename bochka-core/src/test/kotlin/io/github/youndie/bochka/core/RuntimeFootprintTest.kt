package io.github.youndie.bochka.core

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The footprint is a constraint, so it has to be checked rather than configured and hoped for.
 *
 * The build injects the profile it applied as `bochka.expectedJvmArgs`; this reads what the JVM
 * actually started with and compares. Both travel the same way — as arguments to the forked test
 * JVM — which is deliberate: a delivery failure loses the expectation too, and that case is a
 * failure here rather than a check with nothing to compare against.
 *
 * Without this, a footprint that stops being delivered stops constraining anything, and the only
 * symptom is that tests which should have failed on an allocation now pass.
 */
class RuntimeFootprintTest {
    /**
     * `-Pbochka.jvmArgs=…` replaces the profile on purpose — to answer "what does it cost". Under
     * an override these checks describe a configuration nobody declared, so they step aside; the
     * first one does not, because the override is still delivered the same way.
     */
    private val overridden = System.getProperty("bochka.footprintOverridden") == "true"

    @Test
    fun `the runtime profile reaches the jvm the tests run in`() {
        val expected =
            System.getProperty("bochka.expectedJvmArgs")
                ?: fail(
                    "bochka.expectedJvmArgs is not set: the build did not deliver the runtime " +
                        "profile to this JVM. Actual arguments: ${actualArguments()}",
                )

        val actual = actualArguments()
        val missing = expected.split(" ").filter(String::isNotEmpty).filterNot(actual::contains)

        assertTrue(
            missing.isEmpty(),
            "the runtime profile did not reach this JVM. Missing: $missing. Actual: $actual",
        )
    }

    @Test
    fun `the heap is small enough for the hot path to have no room to allocate`() {
        if (overridden) return
        // The concrete figure lives in the build file and is provisional until M-64 measures the
        // index; what is not provisional is that there is a bound at all, and that it is small
        // enough for a test that starts producing garbage to fail rather than to slow down.
        //
        // 128 and not 512: Gradle's own default for a test JVM is `-Xmx512m`, so a bound of 512
        // passes whether or not the profile was delivered. Caught by deleting the `jvmArgs` line
        // and watching which assertions went red — this one did not, which is the only way a check
        // that checks nothing ever gets found.
        val maxHeapMib = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        assertTrue(maxHeapMib <= 128, "max heap is $maxHeapMib MiB, which no longer constrains anything")
    }

    @Test
    fun `the collector is the one the numbers will be measured under`() {
        if (overridden) return
        val collectors = ManagementFactory.getGarbageCollectorMXBeans().map { it.name }
        // SerialGC reports exactly these two. Any other collector means the profile was replaced,
        // and a replaced profile is a different process than the one being measured.
        assertEquals(
            listOf("Copy", "MarkSweepCompact"),
            collectors,
            "expected SerialGC; got $collectors",
        )
    }

    private fun actualArguments(): List<String> = ManagementFactory.getRuntimeMXBean().inputArguments
}
