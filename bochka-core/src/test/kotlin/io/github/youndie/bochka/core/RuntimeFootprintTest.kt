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

        // An empty declaration is not a profile, and until this line it read as one: with
        // `-Pbochka.jvmArgs=" "` the list becomes empty, the expectation becomes empty too,
        // nothing is missing from it, and this check - the one named after the property - passes
        // while constraining nothing. The two checks under it step aside under an override by
        // design, so seven of the eight assertions in this file and its neighbour went green on a
        // JVM running with no profile at all.
        //
        // The marker is dropped before counting, and that is not tidiness: under an override the
        // build appends `-Dbochka.footprintOverridden=true` to the list, so an empty override is
        // not an empty expectation - it is an expectation containing exactly the flag that says
        // the other checks may stand down. The first version of this guard compared the whole
        // string and passed.
        val declared =
            expected
                .split(" ")
                .filter(String::isNotEmpty)
                .filterNot { it == "-Dbochka.footprintOverridden=true" }
        assertTrue(
            declared.isNotEmpty(),
            "the build declared an empty runtime profile: an override replaces the profile, and " +
                "replacing it with nothing is not a configuration this project describes",
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
