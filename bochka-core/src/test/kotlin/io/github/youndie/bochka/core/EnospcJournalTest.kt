package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A journal write that runs out of disk is a torn tail, and everything before it survives (M-266).
 *
 * The framing is body first, length last: until the header lands, recovery reads a zero where a
 * length would be and stops, treating everything earlier as whole. That has been proved by killing
 * a writer mid-write, which is not the same experiment — `SIGKILL` stops the process, so the
 * unwritten header is simply never attempted. `ENOSPC` stops the **write** and leaves the process
 * running, which is the case where a record could be half-described rather than not described.
 *
 * **The failure is aimed at the journal by choosing an operation that only writes the journal**, and
 * finding one took two attempts. A `put` writes an object file first, so it runs out of space there
 * and never reaches the record. A delete looked journal-only and is not: on a bucket without
 * versioning it also unlinks the object file, so it *frees* a block and the record that follows
 * always fits — four hundred of them were written on a full volume without one failing. Creating a
 * bucket is the operation that writes a record and touches nothing else.
 *
 * Runs only under `ci/enospc.sh`; see [EnospcStandTest] for why the skip is not a silent gate.
 */
class EnospcJournalTest {
    private companion object {
        const val HEADER_BYTES = 8
    }

    private val prepared: Path? = System.getenv("BOCHKA_ENOSPC_DIR")?.let(Path::of)

    @Test
    fun `a journal write that runs out of space does not happen, and the log before it survives`() =
        runTest {
            val directory = prepared ?: return@runTest
            val home = Files.createDirectories(directory.resolve("journal-store"))
            val filler = directory.resolve("filler")
            var created = 0
            try {
                val written = listOf("alpha", "beta", "gamma")
                ObjectStore(home, ObjectStore.Durability.FSYNC).use { store ->
                    store.createBucket("photos")
                    for (key in written) {
                        store.put("photos", ObjectKey.of(key), Metadata()) { out ->
                            out.write(key.toByteArray(), 0, key.length)
                        }
                    }

                    // Everything that is left, so the log has to ask for a block it cannot get. Written
                    // outside the store's own directory: a filler inside it would be swept as an orphan
                    // and the test would be measuring the sweep.
                    fillTheVolume(filler)

                    // Buckets until the log needs a block it cannot have. One record is a few dozen
                    // bytes and the block it lands in was already allocated, so the first attempts
                    // succeed — the loop is what reaches the boundary rather than assuming it.
                    var failure: IOException? = null
                    for (n in 1..2000) {
                        try {
                            store.createBucket("filler-bucket-$n")
                            created++
                        } catch (e: IOException) {
                            failure = e
                            break
                        }
                    }

                    val caught =
                        assertNotNull(
                            failure,
                            "$created records were written on a full volume without one running out of space",
                        )
                    assertTrue(
                        caught.message?.contains("No space left on device") == true,
                        "the journal write failed for some other reason than a full disk: ${caught.message}",
                    )

                    // Asked of the file, before the reopen that truncates it away, because this is
                    // the one property nothing else in this repository can see. Reversing the two
                    // writes in `RecordLog` — length first, body last — leaves every other test
                    // green, the crash tests included: the checksum catches a torn record either
                    // way. So until this line existed the framing was documented, believed, and
                    // pinned by nothing.
                    assertLengthWrittenLast(home.resolve("index.log"))

                    // Freed here rather than only in the `finally`, because everything after this
                    // point needs somewhere to write: closing the store, reopening it, and the
                    // marker the harness looks for. The first version kept the volume full until
                    // the end and failed writing its own marker, which reads as a broken test
                    // rather than as a volume that is full on purpose.
                    Files.deleteIfExists(filler)
                }

                // The whole question, asked after a restart, because the journal is what a restart reads.
                ObjectStore(home, ObjectStore.Durability.FSYNC).use { reopened ->
                    for (key in written) {
                        val stored =
                            assertNotNull(
                                reopened.get("photos", ObjectKey.of(key)),
                                "$key was committed before the failure and did not survive it",
                            )
                        assertEquals(key.length.toLong(), stored.size, "$key came back the wrong size")
                    }

                    // The record that failed is the point. `createBucket` puts the bucket in the
                    // map and only then writes, so at the moment of the failure the bucket existed
                    // in memory and not on disk — a reopen must not bring it back, or the log
                    // admitted to something it never stored. Everything the log did admit to has to
                    // be there, both halves asked together: a store that came back empty would pass
                    // the first check on its own.
                    for (n in 1..created) {
                        assertTrue(
                            reopened.hasBucket("filler-bucket-$n"),
                            "filler-bucket-$n was written before the failure and did not survive it",
                        )
                    }
                    assertTrue(
                        !reopened.hasBucket("filler-bucket-${created + 1}"),
                        "the record that ran out of space came back after a restart",
                    )
                }

                Files.createDirectories(directory.resolve("exercised"))
                Files.writeString(
                    directory.resolve("exercised").resolve("journal"),
                    "ENOSPC on the record log after $created records\n",
                )
            } finally {
                // In a `finally`, and that is not tidiness: the first version left the filler behind
                // when it failed, so the two neighbouring tests on the same volume failed as well
                // and reported a full disk that was this test's doing rather than the stand's. The
                // store goes too — four hundred small objects is most of a small volume.
                Files.deleteIfExists(filler)
                if (Files.exists(home)) {
                    Files.walk(home).use { walk ->
                        walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                }
            }
        }

    /**
     * Walks the log the way recovery does and asks what stands where the records stop: a zero
     * length, or the end of the file. Either means the tail announces nothing. A length in front of
     * bytes that are not there is the failure the framing exists to prevent, and it is what the
     * reversed order produces on a full volume — the header fits in the block already allocated, the
     * body does not.
     */
    private fun assertLengthWrittenLast(log: Path) {
        val bytes = Files.readAllBytes(log)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var position = 0
        while (position + HEADER_BYTES <= bytes.size) {
            val length = buffer.getInt(position)
            if (length == 0) return
            assertTrue(length > 0, "the log announces $length bytes at $position")
            val end = position + HEADER_BYTES + length
            assertTrue(
                end <= bytes.size,
                "the log ends inside a record that announced $length bytes at $position: " +
                    "the length reached the disk before the body it describes",
            )
            position = end
        }
    }
}
