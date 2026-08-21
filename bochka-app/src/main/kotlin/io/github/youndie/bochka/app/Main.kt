package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.GcProfile
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.http.HttpServer
import io.github.youndie.bochka.s3.LifecycleSweep
import io.github.youndie.bochka.s3.Lifecycles
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.sigv4.Credentials
import io.github.youndie.bochka.s3.sigv4.KeyScope
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import java.nio.file.Path
import java.time.Duration
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
        val configuration =
            try {
                Configuration.load()
            } catch (e: Configuration.Refused) {
                // Printed and then exited, not thrown: a stack trace in a container log buries the
                // one line that says which setting is misspelt.
                System.err.println("bochka will not start: ${e.message}")
                kotlin.system.exitProcess(2)
            }

        val port = configuration.int(Configuration.Key.PORT) ?: 9000
        val address = configuration[Configuration.Key.BIND_ADDRESS]!!
        val dataDir = configuration[Configuration.Key.DATA_DIR]?.let(Path::of) ?: createTempDirectory("bochka")
        val region = configuration[Configuration.Key.REGION]!!

        // Two by default, because the compatibility suite needs two and a server built around one
        // key cannot run half of it (research, §1.11.1). A list rather than a fixed pair, because
        // the suite wants three — main, alt and tenant.
        val credentials =
            Credentials(
                configuration
                    .list(Configuration.Key.KEYS)
                    .associate { pair ->
                        val colon = pair.indexOf(':')
                        require(colon > 0) { "keys look like id:secret, got '$pair'" }
                        pair.substring(0, colon).trim() to pair.substring(colon + 1).trim()
                    }.ifEmpty { mapOf("bochkaadmin" to "bochkasecret", "bochkaalt" to "bochkaaltsecret") },
                KeyScope.parse(configuration.list(Configuration.Key.KEY_SCOPES)),
            )

        // A directory somebody else is using is a refusal like a misspelt setting, not a crash: the
        // one moment this message is read is a second server started by mistake, and a stack trace
        // there says "bochka is broken" rather than "you already have one" (M-224).
        val store =
            try {
                ObjectStore(
                    root = dataDir,
                    maxObjects = configuration.int(Configuration.Key.MAX_OBJECTS) ?: ObjectStore.ceilingForHeap(),
                )
            } catch (e: ObjectStore.DirectoryInUse) {
                System.err.println("bochka will not start: ${e.message}")
                kotlin.system.exitProcess(2)
            } catch (e: ObjectStore.JournalFromNewerVersion) {
                // The rollback message (M-222). A stack trace here reads as "bochka is broken" to
                // somebody who has just rolled back and wants to know whether they lost data.
                System.err.println("bochka will not start: ${e.message}")
                kotlin.system.exitProcess(2)
            }
        val lifecycleDay = Duration.ofSeconds(configuration.long(Configuration.Key.LIFECYCLE_DAY_SECONDS) ?: 86400)
        if (!lifecycleDay.isPositive) {
            System.err.println("bochka will not start: lifecycle.day.seconds must be positive, not $lifecycleDay")
            kotlin.system.exitProcess(2)
        }
        // Layer two of the access model, and it is announced rather than merely read (M28). A
        // deployment that has it on is one where an unsigned request can be answered with bytes,
        // and nobody should have to diff a values file to find that out — the startup dump prints
        // every setting, and this line says what the setting means.
        val anonymous = configuration[Configuration.Key.ANONYMOUS] == "1"
        if (anonymous) {
            println("anonymous access: ON — an unsigned request is answered by the acl, not refused outright")
        }

        val handler =
            S3Handler(
                store = store,
                verifier = SignatureVerifier(credentials, region = region),
                router = S3Router(virtualHostSuffixes = configuration.list(Configuration.Key.VIRTUAL_HOST_SUFFIXES)),
                accelRedirect = configuration[Configuration.Key.ACCEL_REDIRECT]?.trimEnd('/'),
                lifecycleDay = lifecycleDay,
                anonymous = anonymous,
            )

        val logged = LoggingHandler(handler, enabled = configuration[Configuration.Key.LOG] == "1")
        val server = HttpServer(logged, bindAddress = address, port = port)
        println("bochka listening on $address:${server.boundPort}, data in $dataDir")
        println("configuration:")
        println(configuration.describe())
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
        // Beside the ceiling because it is the same fact from the other end: the ceiling is derived
        // from `Runtime.maxMemory()`, and that is a property of the collector (M-156). A line that
        // says which one, and a louder one when this process is outside what was measured (M-157) —
        // a note and not a refusal, because here the server can still do what it says, only worse.
        println(GcProfile.describe())
        GcProfile.beyondWhatWasMeasured()?.let { System.err.println("NOTE: $it") }

        startHousekeeping(store, configuration.long(Configuration.Key.HOUSEKEEPING_MINUTES) ?: 60)
        startLifecycle(store, lifecycleDay)
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
    private fun startHousekeeping(
        store: ObjectStore,
        minutes: Long,
    ) {
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

    /**
     * Обход правил жизненного цикла — отдельным потоком от уборки, и вот почему.
     *
     * Уборка занимается своим хозяйством и опаздывать ей можно: сирота, подобранная через час,
     * ничем не отличается от подобранной сразу. Здесь опоздание видно снаружи — объект, которому
     * вышел срок, всё ещё отвечает на `GET`, — поэтому период не настраивается отдельно, а
     * выводится из длительности «дня»: десятая его часть, но не чаще раза в секунду и не реже
     * раза в час. У суток это час; у пятисекундного «дня», которым пользуется тест, — секунда.
     *
     * Отдельная настройка была бы четвёртым способом сказать то же самое и первым способом
     * рассогласовать: «день» в секунду при обходе раз в час означает правила, которые не
     * исполняются, и ни одна из двух настроек по отдельности не выглядит неверной.
     */
    private fun startLifecycle(
        store: ObjectStore,
        day: Duration,
    ) {
        val sweep = LifecycleSweep(store, Lifecycles(store), day)
        val period = day.dividedBy(10).coerceIn(Duration.ofSeconds(1), Duration.ofHours(1))
        println("lifecycle: a day lasts ${day.toSeconds()}s, sweeping every ${period.toSeconds()}s")

        Thread {
            while (true) {
                runCatching {
                    val report = sweep.sweep()
                    if (!report.empty) println("lifecycle: $report")
                }.onFailure { println("lifecycle sweep failed: $it") }
                Thread.sleep(period.toMillis())
            }
        }.apply {
            isDaemon = true
            name = "bochka-lifecycle"
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
}
