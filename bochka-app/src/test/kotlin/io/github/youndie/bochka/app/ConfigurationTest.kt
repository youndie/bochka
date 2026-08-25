package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the process prints about itself at startup, and what it refuses to treat as configuration
 * (M-141, M-142).
 *
 * Both tasks are about the same place from opposite sides: the configuration line in the log. The
 * first is about what it **contains** that it should not (a secret going into `kubectl logs` on
 * every start); the second is about a variable documented by the image's **own start script**
 * stopping the process with code 2 — that is, the standard way of passing JVM options to this
 * distribution breaking it.
 *
 * The assertions are negative: the value of both lies exactly in what must not happen, while a
 * positive assertion ("the configuration was read") passes with the secret in the log and without
 * it.
 */
class ConfigurationTest {
    private fun of(vararg environment: Pair<String, String>) =
        Configuration.load(environment = mapOf(*environment), configPath = null)

    @Test
    fun `the printed configuration does not carry the secret half of a key`() {
        val described = of("BOCHKA_KEYS" to "bochkaadmin:s3cr3t,alt:al7secret").describe()

        assertFalse(described.contains("s3cr3t"), "the secret is in the line every start prints:\n$described")
        assertFalse(described.contains("al7secret"), "the second secret is in it too:\n$described")
    }

    @Test
    fun `it still names the ids that will be accepted`() {
        val described = of("BOCHKA_KEYS" to "bochkaadmin:s3cr3t,alt:al7secret").describe()

        assertContains(described, "bochkaadmin")
        assertContains(described, "alt")
    }

    @Test
    fun `a pair with no colon is hidden whole rather than half`() {
        // Nothing says which half of `notapair` is the secret, so neither half is printed. The
        // shape of the mistake is still visible — one entry, unusable — which is what the operator
        // needs to see.
        val described = of("BOCHKA_KEYS" to "notapair").describe()

        assertFalse(described.contains("notapair"), described)
        assertContains(described, "keys")
    }

    @Test
    fun `no keys at all is still visible as unset`() {
        // The dangerous state, and the one this line exists for: unset means the two built-in pairs
        // published in this repository, and a redaction that made "unset" and "set" look alike
        // would hide exactly the start worth noticing.
        assertContains(of().describe(), "(unset)")
    }

    @Test
    fun `BOCHKA_APP_OPTS belongs to the start script, not to this`() {
        // The generated start script assembles `$DEFAULT_JVM_OPTS $JAVA_OPTS $BOCHKA_APP_OPTS`, so
        // the name is documented by the distribution itself. Refusing it means the documented way
        // of passing a JVM option to this distribution stops it with exit code 2.
        of("BOCHKA_APP_OPTS" to "-XX:+PrintFlagsFinal")
    }

    @Test
    fun `a measurement variable is not a setting either`() {
        // The harness owns the whole `BOCHKA_MEASURE_` namespace and grows new names in it — four
        // arrived with the collector measurement alone. A list of exact names would have to be
        // edited by whoever adds the fifth, and nothing would remind them.
        of("BOCHKA_MEASURE_SEED" to "/tmp/seed", "BOCHKA_MEASURE_GARBAGE_GIB" to "8")
    }

    @Test
    fun `an unknown name still stops the process`() {
        // The exemptions above are holes in the rule this class exists for, so the rule itself is
        // asserted beside them: a misspelt setting that is ignored is the worst outcome there is.
        val refused = assertFailsWith<Configuration.Refused> { of("BOCHKA_DATADIR" to "/srv") }

        assertContains(refused.message, "BOCHKA_DATADIR")
        assertContains(refused.message, "BOCHKA_DATA_DIR")
    }

    @Test
    fun `a measurement name that is a real setting is still a setting`() {
        // `BOCHKA_MEASURE_` is a prefix, not a wildcard over anything containing the word.
        assertEquals("0.0.0.0", of("BOCHKA_BIND_ADDRESS" to "0.0.0.0")[Configuration.Key.BIND_ADDRESS])
        assertTrue(of("BOCHKA_MEASURE_KEYS" to "5")[Configuration.Key.KEYS] == null)
    }
}
