package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A write that runs out of disk leaves an orphan, never a reference to bytes that are not there
 * (M-265).
 *
 * Р12 says the order is file, barrier, index record, and the crash tests prove it for "the process
 * was killed". They cannot prove it for "the write came back short", because a `SIGKILL` stops
 * everything at once while `ENOSPC` stops one call and lets the rest of the process carry on —
 * which is the case where a half-written file could still get an index record pointing at it.
 *
 * What must hold afterwards is the weakest thing worth asking: the key the failed write was for
 * does not exist, the store still answers for the keys that do, and whatever the failure left on
 * disk is collectable rather than permanent. Anything stronger — a particular file count, a
 * particular name — would be asserting the shape of the implementation rather than the promise.
 *
 * Runs only under `ci/enospc.sh`, which prepares a volume that ends; see [EnospcStandTest] for why
 * the skip is not a silent gate.
 */
class EnospcObjectWriteTest {
    private val prepared: Path? = System.getenv("BOCHKA_ENOSPC_DIR")?.let(Path::of)

    @Test
    fun `a put that runs out of space leaves no key and no dangling reference`() =
        runTest {
            val directory = prepared ?: return@runTest
            val home = Files.createDirectories(directory.resolve("store"))

            ObjectStore(home, ObjectStore.Durability.FSYNC).use { store ->
                store.createBucket("photos")
                store.put("photos", ObjectKey.of("small"), Metadata()) { out ->
                    out.write("before".toByteArray(), 0, 6)
                }

                // Big enough that the volume cannot hold it: the stand is single-digit mebibytes and
                // this is sixty-four. Written in blocks rather than one array so the failure lands
                // in the middle of the write, which is the case being asked about — a single huge
                // buffer could fail before the file was created at all, and that proves less.
                val block = ByteArray(1 shl 20)
                val failure =
                    try {
                        store.put("photos", ObjectKey.of("too-big"), Metadata()) { out ->
                            repeat(64) { out.write(block, 0, block.size) }
                        }
                        fail("the volume accepted 64 MiB; this stand is not constraining anything")
                    } catch (e: IOException) {
                        e
                    }

                assertTrue(
                    failure.message?.contains("No space left on device") == true,
                    "the write failed for some other reason than a full disk: ${failure.message}",
                )

                // The promise. A key that was never committed must not exist, and the one that was
                // must still read back — a store that answers "gone" for both would also pass a
                // check that only asked about the first.
                assertNull(store.get("photos", ObjectKey.of("too-big")), "a failed write left a key behind")
                assertEquals(
                    6L,
                    store.get("photos", ObjectKey.of("small"))?.size,
                    "the key written before the failure stopped reading back",
                )

                // And what the failure did leave is collectable. `0` as the age, because the
                // interesting question is whether the sweep recognises it at all — waiting an hour
                // would only prove the clock works.
                assertTrue(
                    store.sweepOrphans(olderThanMillis = 0) >= 1,
                    "the partial file from the failed write is not something the sweep collects",
                )
            }

            // Reopened, because the index record is the half that outlives the process: a store that
            // looks right in memory and wrong on restart is the failure Р12 exists to prevent.
            ObjectStore(home, ObjectStore.Durability.FSYNC).use { reopened ->
                assertNull(reopened.get("photos", ObjectKey.of("too-big")), "the key came back after a restart")
                assertEquals(6L, reopened.get("photos", ObjectKey.of("small"))?.size)
            }

            Files.createDirectories(directory.resolve("exercised"))
            Files.writeString(directory.resolve("exercised").resolve("object-write"), "ENOSPC in stage\n")

            // The volume is shared with the other tests on it, and it is small on purpose. A test
            // that keeps its bytes leaves the next one failing to create a directory, which reads
            // as a stand that does not work rather than as a neighbour that did not tidy up.
            removeTree(home)
        }

    private fun removeTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
