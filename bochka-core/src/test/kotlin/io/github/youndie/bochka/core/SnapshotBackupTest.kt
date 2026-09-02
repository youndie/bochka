package io.github.youndie.bochka.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A copy of the data directory taken under load, opened as a store (M-289).
 *
 * The claim M45 starts from is that a snapshot of the directory is exactly the state a `SIGKILL`
 * leaves, so any filesystem snapshot is a valid backup. That is true of an **atomic** snapshot —
 * one moment for the whole directory — and this test is about what happens without one, because
 * `cp -r` is what people reach for and it is not atomic: it copies one file at a time while the
 * writer keeps going.
 *
 * **The order decides, and it decides completely.** Every object is written file first, `fsync`,
 * then the index record (Р12). So:
 *
 * * copy the **index first** and the data after, and every record in the copy refers to a file
 *   that existed before that record, hence before the copy of the data — the file is there;
 * * copy the **data first** and the index after, and the index in the copy names files created
 *   after the copier walked past them. The copy opens, the keys are there, and the bytes are not.
 *
 * The second is not a hypothetical: it is what a straightforward `cp -r` does when it happens to
 * reach the index last, and it produces a backup that looks whole until somebody reads from it.
 */
class SnapshotBackupTest {
    @Test
    fun `a copy that takes the index first reads back whole`() {
        withWriter { source, copy ->
            copyIndexThenData(source, copy)
        }.let { survivors ->
            assertTrue(survivors > 0, "nothing at all was in the copy")
        }
    }

    @Test
    fun `a copy that takes the data first can name bytes it did not copy`() {
        // The finding, asserted rather than warned about: this ordering produces a store whose
        // keys point at files that are not in the copy. The pause is what makes it deterministic —
        // a real `cp -r` hits the same window by luck, which is worse, not better.
        val dangling =
            runCatching {
                withWriter { source, copy ->
                    copyDataThenIndex(source, copy)
                }
            }
        val message = dangling.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            dangling.isFailure && message.contains("points at bytes that are not in the copy"),
            "a copy that took the data first came back whole, which the write order says it cannot: $message",
        )
    }

    /**
     * Runs the writer, makes a copy with [copy], and checks everything the copy claims to hold.
     *
     * Returns how many objects the copy held. Throws when a key in the copy points at a file the
     * copy does not have — which is the failure both orderings are being asked about.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun withWriter(copy: (Path, Path) -> Unit): Int {
        val source = Files.createTempDirectory("bochka-snapshot-source")
        val destination = Files.createTempDirectory("bochka-snapshot-copy")
        val process = spawnWriter(source)
        try {
            awaitWriting(process)
            copy(source, destination)
            process.destroyForcibly()
            process.waitFor()

            return ObjectStore(destination, ObjectStore.Durability.NONE).use { store ->
                val keys = store.list(CrashWriter.BUCKET, maxKeys = Int.MAX_VALUE).keys
                for ((key, stored) in keys) {
                    val path = store.pathOf(stored)
                    check(Files.exists(path)) { "$key points at bytes that are not in the copy" }
                    assertEquals(
                        CrashWriter.contentOf(key),
                        String(Files.readAllBytes(path)),
                        "$key came back with somebody else's bytes",
                    )
                }
                // And the copy is a store rather than a museum: it takes a write, from the boundary
                // its own recovery chose.
                runBlocking {
                    store.put(CrashWriter.BUCKET, ObjectKey.of("after-the-copy"), Metadata.EMPTY) { out ->
                        val bytes = "still works".toByteArray()
                        out.write(bytes, 0, bytes.size)
                    }
                }
                keys.size
            }
        } finally {
            process.destroyForcibly()
            source.deleteRecursively()
            destination.deleteRecursively()
        }
    }

    /** The index first, then the bytes: what the write order says makes a copy safe. */
    private fun copyIndexThenData(
        source: Path,
        destination: Path,
    ) {
        copyEntry(source, destination, "index.log")
        Thread.sleep(PAUSE_MILLIS)
        copyTree(source.resolve("data"), destination.resolve("data"))
    }

    /** The bytes first, then the index: what `cp -r` may do, and what it costs. */
    private fun copyDataThenIndex(
        source: Path,
        destination: Path,
    ) {
        copyTree(source.resolve("data"), destination.resolve("data"))
        Thread.sleep(PAUSE_MILLIS)
        copyEntry(source, destination, "index.log")
    }

    private fun copyEntry(
        source: Path,
        destination: Path,
        name: String,
    ) {
        Files.createDirectories(destination)
        Files.copy(source.resolve(name), destination.resolve(name), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copyTree(
        from: Path,
        to: Path,
    ) {
        if (!Files.exists(from)) return
        Files.walk(from).use { walk ->
            walk.forEach { path ->
                val target = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    // Best effort by design: a file the writer is creating right now may vanish
                    // between the walk and the copy, and a copier that dies on that would be
                    // reporting the race rather than the backup.
                    runCatching { Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
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

    private fun awaitWriting(process: Process) {
        val reader = process.inputStream.bufferedReader()
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            val line = reader.readLine() ?: break
            val written = line.removePrefix(CrashWriter.PROGRESS).trim().toLongOrNull() ?: continue
            if (written >= MINIMUM_BEFORE_COPY) return
        }
        error("the writer never reported $MINIMUM_BEFORE_COPY objects; alive=${process.isAlive}")
    }

    private companion object {
        /** Enough that the copy has something to be wrong about. */
        const val MINIMUM_BEFORE_COPY = 40L

        /** Long enough for the writer to add objects between the two halves of the copy. */
        const val PAUSE_MILLIS = 300L
    }
}
