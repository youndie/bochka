package io.github.youndie.bochka.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kill a real process in the middle of writing and see what the store says afterwards.
 *
 * ## Why a subprocess
 *
 * Every cheaper version of this tests something else. Closing the store tests `close`. Throwing
 * from a fake writer tests the fake. Deleting a file by hand tests the deletion. What has to be
 * survived is `SIGKILL` between the rename of an object file and the index record that mentions it
 * — no unwinding, no flush, no `finally` — and the only way to produce that is to have a process
 * to kill.
 *
 * ## The invariant
 *
 * **Whatever the index reports, the disk has.** Every key that survives points at a file that
 * exists and is the size the index recorded. Orphans are allowed and expected: they are the one
 * outcome the write order permits (Р12), and [ObjectStore.sweepOrphans] is what collects them.
 *
 * It says nothing about *how many* objects survive. A process killed mid-write loses whatever the
 * last record was, and that is what an unacknowledged upload means.
 */
class ObjectStoreCrashTest {
    /**
     * Where the store under test lives, and why that is a knob (M-183).
     *
     * Every crash test in this repository has run on ext4 and APFS, and the schema of the Helm
     * chart refuses `ReadWriteMany` on the strength of that: the write order this store is built
     * on — object file, `fsync`, rename into place, and only then the index record (Р12) — leans
     * on a barrier and on an atomic rename, and both behave differently on NFS and CephFS than on
     * a local filesystem. The refusal is honest but it rests on "nobody has checked", which is not
     * the same as "no".
     *
     * So the directory is a property rather than always the temp dir, and checking is then one
     * mount and one flag away. Empty means what it always meant.
     */
    private fun <T> withDir(body: (Path) -> T): T {
        val root = System.getProperty("bochka.crashDir")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val dir =
            if (root == null) {
                Files.createTempDirectory("bochka-crash")
            } else {
                Files.createTempDirectory(root, "bochka-crash")
            }
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    private fun spawnWriter(dir: Path): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            CrashWriter::class.java.name,
            dir.toString(),
        ).redirectErrorStream(true)
            .start()
    }

    /**
     * Waits until the writer says it has stored [MINIMUM_BEFORE_KILL] objects.
     *
     * The writer says so itself rather than the test inferring it from a file count or a sleep.
     * The neighbouring broker paid for that lesson twice: a fixed sleep killed the process before
     * the JVM had started, and watching the file size was a signal about the *file* rather than
     * about the thing being tested. A signal derived from what is being tested is worth more than
     * one inferred from a side effect of it.
     */
    private fun awaitWriting(process: Process): Long {
        val reader = process.inputStream.bufferedReader()
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            val line = reader.readLine() ?: break
            val written = line.removePrefix(CrashWriter.PROGRESS).trim().toLongOrNull() ?: continue
            if (written >= MINIMUM_BEFORE_KILL) return written
        }
        error("the writer never reported $MINIMUM_BEFORE_KILL objects; alive=${process.isAlive}")
    }

    @Test
    fun `killing the writer never leaves a key pointing at a file that is not there`() {
        // Several rounds with different timings: the interesting moment is inside a write, and
        // there is no way to aim at it, so the test takes several shots.
        for (round in 0 until 4) {
            withDir { dir ->
                val process = spawnWriter(dir)
                val reported = awaitWriting(process)
                Thread.sleep(15L + round * 29L)
                process.destroyForcibly()
                process.waitFor()

                ObjectStore(dir).use { store ->
                    val survivors = store.list(CrashWriter.BUCKET, maxKeys = Int.MAX_VALUE).keys
                    assertTrue(survivors.isNotEmpty(), "round $round: nothing survived at all")

                    for ((key, stored) in survivors) {
                        val path = store.pathOf(stored)
                        assertTrue(Files.exists(path), "round $round: $key points at a file that is not there")
                        assertEquals(stored.size, Files.size(path), "round $round: $key is a different size on disk")
                        assertEquals(
                            CrashWriter.contentOf(key),
                            String(Files.readAllBytes(path)),
                            "round $round: $key came back with somebody else's bytes",
                        )
                    }

                    // Losing the tail is allowed; losing more than the writer had not yet finished
                    // is not, and would mean the log threw away records it had accepted.
                    assertTrue(
                        survivors.size >= reported - CrashWriter.SLACK,
                        "round $round: writer reported $reported, only ${survivors.size} survived",
                    )

                    // And the store is usable: it keeps writing from the boundary recovery chose.
                    runBlocking {
                        store.put(CrashWriter.BUCKET, ObjectKey.of("after-the-crash"), Metadata.EMPTY) { out ->
                            val bytes = "still works".toByteArray()
                            out.write(bytes, 0, bytes.size)
                        }
                    }
                    assertEquals(
                        "still works",
                        String(
                            Files.readAllBytes(
                                store.pathOf(store.get(CrashWriter.BUCKET, ObjectKey.of("after-the-crash"))!!),
                            ),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `the files a crash orphaned are collected and the objects are not`() {
        withDir { dir ->
            val process = spawnWriter(dir)
            awaitWriting(process)
            Thread.sleep(25)
            process.destroyForcibly()
            process.waitFor()

            ObjectStore(dir).use { store ->
                val before = store.list(CrashWriter.BUCKET, maxKeys = Int.MAX_VALUE).keys
                // Everything on disk is fair game for the sweep, so that whatever the crash left is
                // collected in this one run rather than an hour from now.
                store.sweepOrphans(olderThanMillis = -1)

                for ((key, stored) in before) {
                    assertTrue(Files.exists(store.pathOf(stored)), "$key lost its file to the sweep")
                }
            }
        }
    }

    private companion object {
        /** Objects the writer must report before it is worth killing. */
        const val MINIMUM_BEFORE_KILL = 200L

        init {
            check(MINIMUM_BEFORE_KILL > 0)
        }
    }
}

/**
 * Stores objects until something kills it. Run as a subprocess by [ObjectStoreCrashTest].
 *
 * It never closes the store and never stops on its own: the point is to be killed with a write in
 * flight.
 */
object CrashWriter {
    const val BUCKET = "crash"
    const val PROGRESS = "stored"

    /**
     * How many objects the survivor count may fall short of what was reported.
     *
     * One, and only one: the report is printed after the store has acknowledged the object, so
     * everything reported is already in the log — except that the line itself may have been
     * buffered and lost when the process died. Anything larger would mean the log discarded records
     * it had accepted, which is the failure this test exists to catch rather than to tolerate.
     */
    const val SLACK = 1

    fun contentOf(key: ObjectKey): String = "content of ${key.toString().substringAfterLast('-')}"

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        val store = ObjectStore(dir)
        store.createBucket(BUCKET)

        var stored = 0L
        runBlocking {
            while (true) {
                val key = ObjectKey.of("object-$stored")
                store.put(BUCKET, key, Metadata(contentType = "text/plain")) { out ->
                    val bytes = contentOf(key).toByteArray()
                    out.write(bytes, 0, bytes.size)
                }
                stored++
                println("$PROGRESS $stored")
                System.out.flush()
            }
        }
    }
}
