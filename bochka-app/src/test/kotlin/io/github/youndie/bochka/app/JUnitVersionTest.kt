package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test as JupiterTest

/**
 * The version of JUnit named in the catalog is the version the tests run on.
 *
 * It was not, and nothing said so. `kotlin("test")` resolves to `kotlin-test-junit5`, which carries
 * a Jupiter of its own — 5.10.1 for Kotlin 2.4.10 — so five of the six modules ran their tests on
 * that while `gradle/libs.versions.toml` said 5.14.4 and explained the choice in a comment above it.
 * A number that names something other than what runs is worse than no number: the comment beside it
 * is then an argument about a version nobody is using, and a bump of it moves nothing.
 *
 * The fix is one line in the root build — an enforced platform on `junit-bom` — and this is what
 * keeps it. One witness rather than one per module, because there is one line to lose: if the
 * platform is dropped, `kotlin-test-junit5`'s own Jupiter comes back everywhere at once, here
 * included.
 */
class JUnitVersionTest {
    @Test
    fun `the tests run on the JUnit the catalog names`() {
        val expected = assertNotNull(System.getProperty("bochka.expectedJunit"), "the build did not pass the version")

        // The jar the running Jupiter came from, which is the only thing that can answer this: the
        // API's own `Package` carries no implementation version in these artefacts.
        val jar =
            JupiterTest::class.java.protectionDomain.codeSource.location.path
                .substringAfterLast('/')

        assertTrue(
            jar.contains(expected),
            "the catalog says JUnit $expected and the tests are running on $jar",
        )
    }

    @Test
    fun `only one Jupiter engine is on the classpath`() {
        // Two engines answer the same tests, and which of them a run used is not written anywhere.
        // Raising the catalog without the platform put exactly that on `bochka-junit`: the Jupiter
        // it declares beside the Jupiter Kotlin brought.
        val engines =
            JUnitVersionTest::class.java.classLoader
                .getResources("META-INF/services/org.junit.platform.engine.TestEngine")
                .toList()
                .map { it.toString() }
                .filter { it.contains("jupiter-engine") }

        assertEquals(1, engines.size, "more than one Jupiter engine can run these tests: $engines")
    }
}
