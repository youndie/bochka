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
        VIRTUAL_HOST_SUFFIXES("virtual.host.suffixes", null, "domains under which a leading label is a bucket name"),
        LOG("log", "0", "1 to print a line per request"),
        HOUSEKEEPING_MINUTES("housekeeping.minutes", "60", "how often to compact and sweep; 0 to never"),
        MAX_OBJECTS("max.objects", null, "the ceiling on objects; derived from the heap if unset"),
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
            "  %-22s %s".format(key.property, get(key) ?: "(unset)")
        }

    companion object {
        /** Names that are ours to interpret; anything else starting with `BOCHKA_` is a mistake. */
        private val KNOWN_ENVIRONMENT = Key.entries.associateBy { it.environment }
        private val KNOWN_PROPERTIES = Key.entries.associateBy { it.property }

        /**
         * Variables that begin with `BOCHKA_` and are deliberately not settings.
         *
         * Every one of them is here because something outside this file already owns the name —
         * the measurement harness, the build. Listing them beats loosening the rule.
         */
        private val NOT_SETTINGS =
            setOf(
                "BOCHKA_CONFIG",
                "BOCHKA_MEASURE_DIR",
                "BOCHKA_MEASURE_BYTES",
                "BOCHKA_MEASURE_REPEATS",
                "BOCHKA_MEASURE_KEYS",
                "BOCHKA_MEASURE_GENERATIONS",
                "BOCHKA_S3TESTS_K",
                "BOCHKA_FAILED_OUT",
            )

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
                if (!name.startsWith("BOCHKA_") || name in NOT_SETTINGS) continue
                val key =
                    KNOWN_ENVIRONMENT[name]
                        ?: throw Refused(unknown(name, KNOWN_ENVIRONMENT.keys, "in the environment"))
                values[key.property] = value
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
