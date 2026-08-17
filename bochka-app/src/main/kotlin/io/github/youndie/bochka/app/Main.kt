package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.http.HttpServer
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.sigv4.Credentials
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * Runs bochka.
 *
 * Configuration is environment only, and deliberately thin: this exists so that a real client can
 * be pointed at a real socket (M3). Properties, validation-at-startup and the runtime profile baked
 * into a start script are M11 — a configuration system invented now would be shaped by what the
 * live-client harness happens to need this week.
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = env("BOCHKA_PORT")?.toIntOrNull() ?: 9000
        val address = env("BOCHKA_BIND_ADDRESS") ?: "127.0.0.1"
        val dataDir = env("BOCHKA_DATA_DIR")?.let(Path::of) ?: createTempDirectory("bochka")
        val region = env("BOCHKA_REGION") ?: "us-east-1"

        // Two by default, because the compatibility suite needs two and a server built around one
        // key cannot run half of it (research, §1.11.1).
        // `BOCHKA_KEYS=id:secret,id2:secret2`. A list rather than a fixed pair, because the
        // compatibility suite wants three — main, alt and tenant — and a server shaped around one
        // key cannot run half of what it is going to be measured by (research, §1.11.1).
        val credentials =
            Credentials(
                env("BOCHKA_KEYS")
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.associate { pair ->
                        val colon = pair.indexOf(':')
                        require(colon > 0) { "BOCHKA_KEYS entries look like id:secret, got '$pair'" }
                        pair.substring(0, colon).trim() to pair.substring(colon + 1).trim()
                    }
                    ?: mapOf("bochkaadmin" to "bochkasecret", "bochkaalt" to "bochkaaltsecret"),
            )

        val store = ObjectStore(dataDir)
        val handler =
            S3Handler(
                store = store,
                verifier = SignatureVerifier(credentials, region = region),
                router = S3Router(virtualHostSuffixes = env("BOCHKA_VIRTUAL_HOST_SUFFIXES")?.split(",") ?: emptyList()),
            )

        val logged = LoggingHandler(handler, enabled = env("BOCHKA_LOG") == "1")
        val server = HttpServer(logged, bindAddress = address, port = port)
        println("bochka listening on $address:${server.boundPort}, data in $dataDir")
        // What the log said when it was opened, rather than a silent start: a run that discarded a
        // torn tail is a fact about the last shutdown, and the only place it is ever visible.
        with(store.recovery) {
            println(
                "index: $records records, $acceptedBytes bytes accepted, stopped by $stoppedBy" +
                    if (discardedBytes > 0) ", $discardedBytes bytes discarded" else "",
            )
        }
        println("access keys: ${credentials.ids.sorted().joinToString(", ")}")
        println("object ceiling: ${store.maxObjects} (${ObjectStore.BYTES_PER_OBJECT} bytes of index each)")

        startHousekeeping(store)
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        Thread.currentThread().join()
    }

    /**
     * The three things nobody asks for and that nothing else will ever do.
     *
     * Compaction (M-63), because recovery is proportional to the log and not to what is in it —
     * measured at 3.5 seconds against 0.76 for the same half-million objects. Orphan collection
     * (Р12), because the write order's one bad outcome is a file nobody points at. Abandoned
     * uploads (M-57), because a client that stops calling says nothing, and its parts stay.
     *
     * A daemon thread on a plain interval rather than a scheduler: there is one job, it is allowed
     * to be late, and a thread that dies with the process is the correct lifetime for it. Failures
     * are printed and the loop continues — housekeeping that could take the server down with it
     * would be a worse trade than housekeeping that skipped a round.
     */
    private fun startHousekeeping(store: ObjectStore) {
        val minutes = env("BOCHKA_HOUSEKEEPING_MINUTES")?.toLongOrNull() ?: 60
        if (minutes <= 0) {
            println("housekeeping: disabled")
            return
        }
        println("housekeeping: every $minutes min")

        // Once at startup, so a store that was killed mid-life does not carry the mess until the
        // first interval elapses.
        housekeep(store)
        Thread {
            while (true) {
                Thread.sleep(minutes * 60_000)
                housekeep(store)
            }
        }.apply {
            isDaemon = true
            name = "bochka-housekeeping"
            start()
        }
    }

    private fun housekeep(store: ObjectStore) {
        runCatching {
            store.compactIfWorthwhile()?.let {
                println("compacted the index: ${it.bytesBefore} -> ${it.bytesAfter} bytes, ${it.records} records")
            }
            val orphans = store.sweepOrphans()
            val abandoned = store.sweepUploads()
            if (orphans > 0 || abandoned > 0) {
                println("swept $orphans orphaned files and $abandoned abandoned uploads")
            }
        }.onFailure { println("housekeeping failed: $it") }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}
