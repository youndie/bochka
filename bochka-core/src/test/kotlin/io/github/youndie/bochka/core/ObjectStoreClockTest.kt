package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which clock the store's answers about time came from.
 *
 * Every stamp a bochka server makes -- `Last-Modified`, a bucket's creation, the moment an upload
 * began -- and every comparison it draws against one comes from [ObjectStore.clock]. That is a
 * claim about a seam, and a seam is invisible from outside as long as the clock behind it agrees
 * with the machine's: a store that ignored what it was handed and called `Instant.now()` answers
 * every existing test identically. So the clock here disagrees, by a quarter of a century.
 *
 * The second test is the one that is about a defect rather than about a seam. An upload's age is a
 * subtraction, and a subtraction has two ends: `startedAt`, which the store stamped, and a cutoff.
 * Read those from two different clocks and a client's upload is swept while it is still being
 * written -- which is what a host whose time is corrected by an hour does to a server that reads
 * the JVM on one side of the `>`.
 */
class ObjectStoreClockTest {
    private val dir: Path = Files.createTempDirectory("bochka-clock")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    /** Far enough from any machine's clock that no plausible skew could produce it. */
    private val frozen = Instant.parse("2001-02-03T04:05:06Z")

    private fun open() = ObjectStore(dir, ObjectStore.Durability.NONE, clock = { frozen })

    @Test
    fun `an object is stamped with the clock the store was handed`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                store.put("photos", ObjectKey.of("a.txt"), Metadata()) { out ->
                    val bytes = "hello".toByteArray()
                    out.write(bytes, 0, bytes.size)
                }

                assertEquals(frozen, store.get("photos", ObjectKey.of("a.txt"))!!.lastModified)
            }
        }

    @Test
    fun `an upload is aged by the clock that stamped it, not by the machine's`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                store.createUpload("photos", ObjectKey.of("big.bin"), Metadata())

                // A week by the store's clock has not passed: the upload was started a moment ago
                // by the only clock this store has. Aged against `System.currentTimeMillis()` it
                // began in 2001 and is swept on the spot, taking a client's parts with it.
                assertEquals(0, store.sweepUploads())
                assertEquals(1, store.uploads("photos").size)
            }
        }
}
