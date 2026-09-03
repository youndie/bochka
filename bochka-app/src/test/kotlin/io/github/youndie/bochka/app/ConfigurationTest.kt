package io.github.youndie.bochka.app

import java.nio.file.Files
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

    /**
     * Keys can arrive in a file instead of the environment, and until now nothing said so (M-262).
     *
     * `BOCHKA_KEYS` in the environment is readable by anyone who can run `docker inspect` or read
     * `/proc/PID/environ`, and neither is a privilege the secret was handed out for. `BOCHKA_CONFIG`
     * was already the way out — it just had no test and no sentence in the README, which is the
     * same as not having it: an escape nobody is told about is not one.
     *
     * Written as a characterisation rather than as a fix, and this is said out loud because the
     * milestone asked for `BOCHKA_KEYS_FILE`: the mechanism existed under another name, so adding
     * a second one would have been a new spelling for a path that already worked.
     */
    @Test
    fun `the keys can come out of a file rather than the environment`() {
        val file = Files.createTempFile("bochka-config", ".properties")
        Files.writeString(file, "keys=fromfile:secret\nregion=eu-west-1\n")

        val configuration = Configuration.load(environment = emptyMap(), configPath = file.toString())

        assertEquals("fromfile:secret", configuration[Configuration.Key.KEYS])
        assertEquals("eu-west-1", configuration[Configuration.Key.REGION])
        Files.deleteIfExists(file)
    }

    @Test
    fun `a name the file does not know is refused by name rather than ignored`() {
        // The other half, and the one that makes the test above worth having: a file whose settings
        // are read and silently dropped is worse than no file, because the process then runs on
        // defaults while its operator believes otherwise. This is the same rule the environment
        // half already follows.
        val file = Files.createTempFile("bochka-config", ".properties")
        Files.writeString(file, "keyz=typo:secret\n")

        val refused =
            assertFailsWith<Configuration.Refused> {
                Configuration.load(environment = emptyMap(), configPath = file.toString())
            }

        assertContains(refused.message, "keyz")
        Files.deleteIfExists(file)
    }

    @Test
    fun `the keys can come out of a file, which is how a Secret arrives`() {
        // M-296. The chart used to pass the keys as an environment variable, which puts every
        // secret the deployment accepts into `/proc/PID/environ`. Mounting the Secret and pointing
        // `BOCHKA_CONFIG` at it does not work - that reads properties, and `id:secret` makes the
        // key id a property name - so the file holds the format this server already publishes.
        val file = Files.createTempFile("bochka-keys", ".txt")
        // With the trailing newline a mounted Secret really has. It survives either way - `list`
        // trims every pair - and it is written here because a fixture without it would be a
        // fixture nobody deploys.
        Files.writeString(file, "one:first,two:second\n")

        val configuration = of("BOCHKA_KEYS_FILE" to file.toString())

        assertEquals(listOf("one:first", "two:second"), configuration.list(Configuration.Key.KEYS))
    }

    @Test
    fun `keys in two places at once are a refusal rather than a precedence rule`() {
        val file = Files.createTempFile("bochka-keys", ".txt")
        Files.writeString(file, "one:first")

        val refused =
            assertFailsWith<Configuration.Refused> {
                of("BOCHKA_KEYS" to "two:second", "BOCHKA_KEYS_FILE" to file.toString())
            }

        assertContains(refused.message, "BOCHKA_KEYS_FILE")
    }

    @Test
    fun `a keys file that cannot be read stops the start rather than falling back`() {
        // Falling back would start the server on the two published built-in pairs while the
        // operator believes it is running on theirs, which is the failure this whole file exists
        // to prevent.
        val refused = assertFailsWith<Configuration.Refused> { of("BOCHKA_KEYS_FILE" to "/nowhere/keys.txt") }

        assertContains(refused.message, "cannot be read")
    }

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
