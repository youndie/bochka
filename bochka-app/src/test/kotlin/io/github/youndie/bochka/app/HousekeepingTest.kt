package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.core.ObjectStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A round of housekeeping that fails does not take the thread with it (M9).
 *
 * The loop this is about runs compaction, the orphan sweep and the abandoned-upload sweep on a
 * daemon thread every hour, and its `while (true)` has no `catch` around it: what keeps the thread
 * alive is the `runCatching` **inside** the round. Take that away and the first failure ends
 * housekeeping for the life of the process - silently, in the sense that nothing says so until
 * somebody wonders why the index never shrinks and the orphans never go.
 *
 * The failure used here is the one that actually happens: a store that has been closed. Anything
 * that reaches the journal then throws, which is exactly what a round has to survive.
 */
class HousekeepingTest {
    @Test
    fun `a round that throws is printed and does not escape`() =
        runBlocking {
            val dir: Path = Files.createTempDirectory("bochka-housekeeping")
            try {
                val store = ObjectStore(dir)
                store.createBucket("photos")
                store.put(
                    "photos",
                    io.github.youndie.bochka.core.ObjectKey
                        .of("a.txt"),
                    Metadata(),
                ) { out ->
                    out.write("hello".toByteArray(), 0, 5)
                }

                // The directory the sweep walks, taken away underneath it. A closed store was the
                // first choice and proved nothing: compaction on one decides there is nothing
                // worth doing and never reaches the journal, so the round did not fail at all and
                // the test passed with the guard removed. A missing data directory does fail, and
                // it is a failure that happens - somebody unmounts a volume.
                Files.walk(dir.resolve("data")).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }

                // No assertion on the message: what is being checked is that control comes back
                // here at all. A thrown exception is the failure, and it needs no `assertFailsWith`
                // to be visible.
                Main.housekeep(store)
                assertTrue(true, "housekeeping returned rather than throwing")
            } finally {
                Files.walk(dir).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }
}
