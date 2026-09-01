package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A compaction that runs out of disk leaves the index it was rewriting (M-267).
 *
 * Compaction is the one operation that touches the whole index at once, and what protects it is
 * that the new log is built under another name and moved over the old one in a single atomic
 * rename. `ObjectStoreCompactionCrashTest` proves that across a `SIGKILL`, which stops everything
 * at once. `ENOSPC` is the other shape of the same moment: the failure is inside one call, the
 * process carries on, and the store has to still be a store afterwards.
 *
 * **The rename is not where a full volume stops a compaction, and that is the finding.** The task
 * was written as "the `ATOMIC_MOVE` did not happen", but a rename allocates nothing — the volume
 * ends much earlier, while the replacement log is being written under its temporary name. So the
 * move is never reached, which is the same outcome by a different road: the old log was never
 * closed and never touched.
 *
 * Asked in that order — before the reopen, then after it. Before, because a store whose log was
 * closed by the failed compaction would answer reads from memory and refuse the next write; after,
 * because whatever the failure left on disk must be ignored rather than read.
 *
 * Runs only under `ci/enospc.sh`; see [EnospcStandTest] for why the skip is not a silent gate.
 */
class EnospcCompactionTest {
    private val prepared: Path? = System.getenv("BOCHKA_ENOSPC_DIR")?.let(Path::of)

    @Test
    fun `a compaction that runs out of space loses no live record`() =
        runTest {
            val directory = prepared ?: return@runTest
            val home = Files.createDirectories(directory.resolve("compaction-store"))
            val filler = directory.resolve("compaction-filler")
            val acknowledged = mutableMapOf<String, String>()
            try {
                // Enough keys that the replacement log needs several blocks rather than one. The
                // first version of this test wrote three, and three records fit in whatever a
                // filler happens to leave behind: it starved the compaction on one machine and not
                // on the other, which is a test that reports the machine.
                val keys = (1..64).map { "key-$it" }
                ObjectStore(home, ObjectStore.Durability.FSYNC).use { store ->
                    store.createBucket("photos")
                    for (key in keys) {
                        store.put("photos", ObjectKey.of(key), Metadata()) { out ->
                            out.write(key.toByteArray(), 0, key.length)
                        }
                    }
                    // The eTag rather than the size: it is what the client was told, and two keys
                    // that came back the right length are not the same as two keys that came back.
                    for (key in keys) {
                        acknowledged[key] = assertNotNull(store.get("photos", ObjectKey.of(key))).eTag
                    }

                    // Outside the store's own tree, so the sweep has nothing to say about it.
                    fillTheVolume(filler)

                    val failure =
                        try {
                            store.compact()
                            null
                        } catch (e: IOException) {
                            e
                        }
                    val caught =
                        assertNotNull(
                            failure,
                            "a compaction rewrote the whole index on a volume with nothing left on it",
                        )
                    assertTrue(
                        caught.message?.contains("No space left on device") == true,
                        "the compaction failed for some other reason than a full disk: ${caught.message}",
                    )

                    // Still the store it was. Asked here rather than only after a restart because
                    // the in-memory index would answer these reads even from a store whose log the
                    // failed compaction had closed — the write below is what tells the two apart.
                    for ((key, eTag) in acknowledged) {
                        assertEquals(
                            eTag,
                            store.get("photos", ObjectKey.of(key))?.eTag,
                            "$key was acknowledged before the compaction and did not survive it",
                        )
                    }

                    Files.deleteIfExists(filler)

                    // The old log is open and appendable: `compact` closes it only once the
                    // replacement is complete, so a failure before that leaves a working store
                    // rather than one that answers reads and refuses writes.
                    store.put("photos", ObjectKey.of("after"), Metadata()) { out ->
                        out.write("after".toByteArray(), 0, 5)
                    }
                }

                // Whatever the failure left under the temporary name has to be ignored by the next
                // start rather than read: the store's state is one file, and it is the one the
                // rename never replaced.
                ObjectStore(home, ObjectStore.Durability.FSYNC).use { reopened ->
                    for ((key, eTag) in acknowledged) {
                        assertEquals(
                            eTag,
                            reopened.get("photos", ObjectKey.of(key))?.eTag,
                            "$key survived the failed compaction and not the restart after it",
                        )
                    }
                    assertNotNull(
                        reopened.get("photos", ObjectKey.of("after")),
                        "the write accepted after the failed compaction did not survive the restart",
                    )
                }

                Files.createDirectories(directory.resolve("exercised"))
                Files.writeString(
                    directory.resolve("exercised").resolve("compaction"),
                    "ENOSPC during compaction, leftover under the temporary name: " +
                        "${Files.exists(home.resolve("index.log.compacting"))}\n",
                )
            } finally {
                // The volume is shared and small: a test that keeps its bytes leaves the next one
                // reporting a full disk that is this test's doing rather than the stand's.
                Files.deleteIfExists(filler)
                if (Files.exists(home)) {
                    Files.walk(home).use { walk ->
                        walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                }
            }
        }
}
