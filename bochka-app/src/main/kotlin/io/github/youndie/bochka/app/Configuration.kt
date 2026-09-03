package io.github.youndie.bochka.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Everything bochka can be told, and nothing else.
 *
 * ## Why an unknown key stops the process
 *
 * A misspelt setting that is ignored is the worst possible outcome: the server starts, reports
 * itself healthy, and runs with a default the operator believes they replaced. `BOCHKA_DATA_DIR`
 * written as `BOCHKA_DATADIR` means the objects are in a temporary directory, and nothing about
 * the running process says so. So an unrecognised `BOCHKA_*` name is a refusal to start, with the
 * name printed and the nearest real one suggested.
 *
 * The cost of that is real and worth stating: a `BOCHKA_*` variable set in the environment for
 * some other purpose will stop this server. That is the trade, and it is the right way round —
 * the failure is loud, immediate and says exactly what to remove.
 *
 * ## Two sources, one shape
 *
 * A properties file named by `BOCHKA_CONFIG`, and the environment. The environment wins, because
 * that is the direction every deployment expects: the file is the deployment's, the variables are
 * this run's. A key is spelled `data.dir` in the file and `BOCHKA_DATA_DIR` in the environment,
 * and the mapping between them is mechanical rather than a table somebody maintains.
 */
class Configuration private constructor(
    private val values: Map<String, String>,
) {
    class Refused(
        override val message: String,
    ) : RuntimeException(message)

    /**
     * A setting, its default and what it is for.
     *
     * The documentation lives here rather than in a README because it is what the process prints
     * when it refuses to start, and a README does not reach the person reading a container log.
     */
    enum class Key(
        val property: String,
        val default: String?,
        val what: String,
    ) {
        PORT("port", "9000", "the port to listen on"),
        BIND_ADDRESS("bind.address", "127.0.0.1", "the address to bind; 0.0.0.0 to accept from anywhere"),
        DATA_DIR("data.dir", null, "where objects and the index live; a temporary directory if unset"),
        REGION("region", "us-east-1", "the region name this deployment answers with"),
        KEYS("keys", null, "access keys as id:secret,id2:secret2; two defaults if unset"),
        KEYS_FILE(
            "keys.file",
            null,
            "a file holding what keys would hold; for a Secret mounted rather than put in the environment",
        ),
        KEY_SCOPES(
            "key.scopes",
            null,
            "narrow a key: id=ro, id=rw@bucket|bucket; a key not named here keeps everything",
        ),
        VIRTUAL_HOST_SUFFIXES("virtual.host.suffixes", null, "domains under which a leading label is a bucket name"),
        ANONYMOUS(
            "anonymous",
            "0",
            "1 to let an unsigned request through to the ACL; off, an unsigned request is 403 whatever the ACL says",
        ),
        LOG("log", "0", "1 to print a line per request"),
        HEAD_TIMEOUT_SECONDS(
            "head.timeout.seconds",
            "20",
            "how long a request head may take to arrive before 408; a slow client, not a large one",
        ),
        MAX_CONNECTIONS(
            "max.connections",
            null,
            "how many connections may be live at once before 503; derived from the heap if unset",
        ),
        BODY_IDLE_TIMEOUT_SECONDS(
            "body.idle.timeout.seconds",
            "60",
            "how long a body may go quiet between reads before 408; a gap, not a total upload time",
        ),
        HOUSEKEEPING_MINUTES("housekeeping.minutes", "60", "how often to compact and sweep; 0 to never"),
        LIFECYCLE_DAY_SECONDS(
            "lifecycle.day.seconds",
            "86400",
            "how long a lifecycle rule's day lasts; shorten it to test rules without waiting one",
        ),
        MAX_OBJECTS("max.objects", null, "the ceiling on objects; derived from the heap if unset"),
        ACCEL_REDIRECT(
            "accel.redirect",
            null,
            "hand whole-object reads to the terminator in front by this internal prefix; off if unset",
        ),
        ;

        /** `data.dir` is `BOCHKA_DATA_DIR`. Mechanical, so that neither list can drift from the other. */
        val environment: String get() = "BOCHKA_" + property.uppercase().replace('.', '_')
    }

    operator fun get(key: Key): String? = values[key.property] ?: key.default

    fun int(key: Key): Int? = get(key)?.trim()?.toIntOrNull()

    fun long(key: Key): Long? = get(key)?.trim()?.toLongOrNull()

    fun list(key: Key): List<String> =
        get(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** What the process prints at startup, so a log says what it is running with. */
    fun describe(): String =
        Key.entries.joinToString("\n") { key ->
            val value = get(key)
            "  %-22s %s".format(key.property, (if (key == Key.KEYS) redact(value) else value) ?: "(unset)")
        }

    companion object {
        /**
         * Keeps the shape of a value whose content has no business being in a log.
         *
         * `id:secret,id2:secret2` becomes `id:***,id2:***`. The ids stay, because "which keys does
         * this process accept" is the question this line is read for; the secret goes, because the
         * line is printed on every start into whatever collects container logs, and a `Secret` is
         * where a value is **stored**, not where it stays.
         *
         * An entry with no colon is hidden whole. Nothing says which half of it was meant to be the
         * secret, and a redaction that guesses is a redaction that leaks on the malformed input —
         * which is the input most likely to be typed by hand.
         */
        private fun redact(value: String?): String? =
            value?.split(',')?.joinToString(",") { pair ->
                val colon = pair.indexOf(':')
                if (colon <= 0) "***" else pair.substring(0, colon) + ":***"
            }

        /** Names that are ours to interpret; anything else starting with `BOCHKA_` is a mistake. */
        private val KNOWN_ENVIRONMENT = Key.entries.associateBy { it.environment }
        private val KNOWN_PROPERTIES = Key.entries.associateBy { it.property }

        /**
         * Variables that begin with `BOCHKA_` and are deliberately not settings.
         *
         * Every one of them is here because something outside this file already owns the name —
         * the measurement harness, the build, the start script this distribution ships with.
         *
         * `BOCHKA_APP_OPTS` is the expensive one. Gradle's generated start script assembles
         * `$DEFAULT_JVM_OPTS $JAVA_OPTS $BOCHKA_APP_OPTS` from the application's own name, so it is
         * documented by the distribution itself — and refusing it meant the documented way of
         * passing a JVM option to this distribution stopped it with exit code 2. The shell reads it
         * before there is a JVM to read a setting, so there is nothing here to interpret.
         */
        private val NOT_SETTINGS =
            setOf(
                "BOCHKA_CONFIG",
                "BOCHKA_APP_OPTS",
                "BOCHKA_S3TESTS_K",
                "BOCHKA_FAILED_OUT",
            )

        /**
         * A whole namespace that belongs to the measurement harness rather than to the server.
         *
         * A prefix and not eight exact names, because the list was eight exact names and the
         * collector measurement added four more in one commit. Whoever adds the thirteenth would
         * have had to know this file existed, and nothing would have told them: the harness runs
         * without a server in the process, so the refusal appears somewhere else entirely.
         */
        private const val HARNESS_PREFIX = "BOCHKA_MEASURE_"

        fun load(
            environment: Map<String, String> = System.getenv(),
            configPath: String? = environment["BOCHKA_CONFIG"],
        ): Configuration {
            val values = LinkedHashMap<String, String>()

            if (configPath != null) {
                val path = Path.of(configPath)
                if (!Files.isReadable(path)) throw Refused("BOCHKA_CONFIG points at $path, which cannot be read")
                val properties = Properties()
                Files.newBufferedReader(path).use(properties::load)
                for (name in properties.stringPropertyNames()) {
                    KNOWN_PROPERTIES[name] ?: throw Refused(unknown(name, KNOWN_PROPERTIES.keys, "in $path"))
                    values[name] = properties.getProperty(name)
                }
            }

            for ((name, value) in environment) {
                if (!name.startsWith("BOCHKA_") || name in NOT_SETTINGS || name.startsWith(HARNESS_PREFIX)) continue
                val key =
                    KNOWN_ENVIRONMENT[name]
                        ?: throw Refused(unknown(name, KNOWN_ENVIRONMENT.keys, "in the environment"))
                values[key.property] = value
            }

            // The keys, read from a file rather than taken from the environment (M-296).
            //
            // The chart used to pass them as `BOCHKA_KEYS`, which puts every secret this
            // deployment accepts into `/proc/PID/environ` — readable by anything that can see the
            // process, and copied into whatever collects container metadata. Mounting the Secret
            // instead was the obvious fix and did not work: `BOCHKA_CONFIG` reads a properties
            // file, and a Secret holds `id:secret,id2:secret2`, where the colon makes the key id a
            // property name that `KNOWN_PROPERTIES` refuses. So the server learns the format it
            // already publishes, from a file.
            //
            // Both at once is a refusal rather than a precedence rule: two sources for one value
            // means a rotation that changes one of them silently changes nothing, and which half
            // won would have to be remembered by whoever reads the log a year later.
            val keysFile = values[Key.KEYS_FILE.property]?.trim()?.takeIf { it.isNotEmpty() }
            if (keysFile != null) {
                if (values[Key.KEYS.property]?.isNotBlank() == true) {
                    throw Refused(
                        "both ${Key.KEYS.environment} and ${Key.KEYS_FILE.environment} are set; " +
                            "the keys have to come from one place",
                    )
                }
                val path = Path.of(keysFile)
                if (!Files.isReadable(path)) {
                    throw Refused("${Key.KEYS_FILE.environment} points at $path, which cannot be read")
                }
                // Trimmed for the log rather than for the keys: a mounted Secret ends with a
                // newline, and `list` already trims every pair it splits out, so removing the
                // trim changes no behaviour a test can see. What it does change is the line
                // `describe` prints at startup, which would otherwise carry a stray newline.
                values[Key.KEYS.property] = Files.readString(path).trim()
            }

            return Configuration(values.filterValues { it.isNotBlank() })
        }

        /**
         * The refusal, with the nearest real name in it.
         *
         * A message that says only "unknown setting" leaves the operator to diff two lists by eye;
         * the whole value of stopping is in saying which one they meant.
         */
        private fun unknown(
            name: String,
            known: Collection<String>,
            where: String,
        ): String {
            val nearest = known.minByOrNull { distance(it.lowercase(), name.lowercase()) }
            return buildString {
                append("unknown setting '$name' $where.")
                if (nearest != null) append(" Did you mean '$nearest'?")
                append("\nKnown settings:\n")
                append(
                    Key.entries.joinToString("\n") { "  %-24s %-22s %s".format(it.environment, it.property, it.what) },
                )
            }
        }

        /** Plain edit distance; enough to spot a typo, and it runs once, at startup. */
        private fun distance(
            a: String,
            b: String,
        ): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    current[j] =
                        minOf(
                            previous[j] + 1,
                            current[j - 1] + 1,
                            previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                        )
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}
