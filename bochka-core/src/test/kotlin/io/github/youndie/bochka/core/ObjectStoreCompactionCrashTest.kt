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
 * M-65: kill a process in the middle of rewriting the index and see what is left.
 *
 * Compaction is the one operation that touches the whole index at once, so it is also the one with
 * the most to lose. What must hold across a `SIGKILL` at any moment of it is that **every object
 * the store had acknowledged is still there afterwards** — not most of them, not the ones written
 * before the compaction started, all of them.
 *
 * The design that has to earn that is the rename: the new log is built under another name and
 * moved over the old one in one step. A kill before the move leaves the old log, complete; a kill
 * after it leaves the new log, complete. What must never exist is a moment where the file called
 * `index.log` is half of one and half of the other, and the only way to know that it does not is
 * to kill a real process repeatedly and look.
 */
class ObjectStoreCompactionCrashTest {
    @Test
    fun `no acknowledged object is lost to a kill during compaction`() {
        // Several rounds at different moments: compaction of this size takes milliseconds, so any
        // single delay would land in the same place every time and prove one instant safe.
        for (round in 0 until 6) {
            withDir { dir ->
                val process = spawn(dir)
                val reported = awaitCompactions(process, atLeast = 2)
                Thread.sleep(3L + round * 7L)
                process.destroyForcibly()
                process.waitFor()

                ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
                    val survivors = store.list(CompactingWriter.BUCKET, maxKeys = Int.MAX_VALUE).keys
                    assertTrue(
                        survivors.size >= reported - CompactingWriter.SLACK,
                        "round $round: the writer had $reported objects, ${survivors.size} came back",
                    )
                    for ((key, stored) in survivors) {
                        val path = store.pathOf(stored)
                        assertTrue(Files.exists(path), "round $round: $key points at a file that is not there")
                        assertEquals(
                            CompactingWriter.contentOf(key),
                            String(Files.readAllBytes(path)),
                            "round $round: $key came back with somebody else's bytes",
                        )
                    }

                    // And a store that survived a kill mid-compaction can still be compacted.
                    store.compact()
                    assertEquals(survivors.size, store.objectCount)
                }
            }
        }
    }

    @Test
    fun `a half-written compaction left behind is ignored, not read`() {
        withDir { dir ->
            runBlocking {
                ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
                    store.createBucket("b")
                    store.put("b", ObjectKey.of("kept"), Metadata.EMPTY) { out ->
                        out.write("value".toByteArray(), 0, 5)
                    }
                }
            }
            // Exactly what a kill before the rename leaves: a temporary file with plausible
            // contents and a name nothing looks for.
            Files.write(dir.resolve("index.log.compacting"), ByteArray(4096) { 0x7F })

            ObjectStore(dir, ObjectStore.Durability.NONE).use { reopened ->
                assertEquals(1, reopened.objectCount)
                assertEquals(RecordLog.Stop.CLEAN, reopened.recovery.stoppedBy)
                reopened.compact()
                assertEquals(1, reopened.objectCount)
            }
        }
    }

    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("bochka-compaction-crash")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    private fun spawn(dir: Path): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            CompactingWriter::class.java.name,
            dir.toString(),
        ).redirectErrorStream(true)
            .start()
    }

    /** Waits until the writer reports it has compacted at least [atLeast] times; returns its count. */
    private fun awaitCompactions(
        process: Process,
        atLeast: Int,
    ): Long {
        val reader = process.inputStream.bufferedReader()
        val deadline = System.nanoTime() + 60_000_000_000L
        var compactions = 0
        var stored = 0L
        while (System.nanoTime() < deadline) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith(CompactingWriter.COMPACTED) -> {
                    compactions++
                }

                line.startsWith(CompactingWriter.PROGRESS) -> {
                    stored = line.removePrefix(CompactingWriter.PROGRESS).trim().toLongOrNull() ?: stored
                }
            }
            if (compactions >= atLeast) return stored
        }
        error("the writer never reported $atLeast compactions")
    }
}

/**
 * Writes objects and compacts the index every so often, until something kills it.
 *
 * Both at once on purpose: a compaction with no writer racing it is a compaction of a quiet store,
 * and the moment worth killing is the one where a rewrite and an append want the same log.
 */
object CompactingWriter {
    const val BUCKET = "crash"
    const val PROGRESS = "stored"
    const val COMPACTED = "compacted"
    const val EVERY = 40

    /** The same one record of slack as the plain crash writer: the last line may have been buffered. */
    const val SLACK = 1

    fun contentOf(key: ObjectKey): String = "content of ${key.toString().substringAfterLast('-')}"

    @JvmStatic
    fun main(args: Array<String>) {
        val store = ObjectStore(Path.of(args[0]), ObjectStore.Durability.NONE)
        store.createBucket(BUCKET)

        var stored = 0L
        runBlocking {
            while (true) {
                val key = ObjectKey.of("object-$stored")
                store.put(BUCKET, key, Metadata.EMPTY) { out ->
                    val bytes = contentOf(key).toByteArray()
                    out.write(bytes, 0, bytes.size)
                }
                stored++
                println("$PROGRESS $stored")
                if (stored % EVERY == 0L) {
                    store.compact()
                    println(COMPACTED)
                }
                System.out.flush()
            }
        }
    }
}
