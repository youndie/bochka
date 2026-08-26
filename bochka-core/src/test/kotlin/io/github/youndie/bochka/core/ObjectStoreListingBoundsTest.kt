package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Listing where the bytes sit on the edges the walk is built around (M-254).
 *
 * The walk over the index does three things with byte arrays and each has a boundary nothing was
 * standing on: it steps past a rolled-up group by incrementing its last byte, it looks for the
 * delimiter inside a key, and it counts a page against `MaxKeys`. Six mutations of that arithmetic
 * survived, because every listing in the suite uses ASCII keys, a delimiter in the middle of them,
 * and a page that is either empty or nowhere near full.
 *
 * The keys here are bytes rather than text on purpose: `0xFF` is a legal byte in an S3 key, and it
 * is the one value the "step past this group" arithmetic cannot simply add one to.
 */
class ObjectStoreListingBoundsTest {
    private val dir: Path = Files.createTempDirectory("bochka-listing")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun open() = ObjectStore(dir, ObjectStore.Durability.NONE)

    private suspend fun ObjectStore.write(
        bucket: String,
        key: ByteArray,
    ) = put(bucket, ObjectKey(key), Metadata()) { out -> out.write(byteArrayOf(1), 0, 1) }

    @Test
    fun `a page is full when its keys and its rolled-up prefixes together reach the bound`() =
        runTest {
            // `MaxKeys` bounds what the answer holds, and a rolled-up prefix counts as one of them.
            // Counting only the keys hands back a page of four when three were asked for — which a
            // client paginating by that number then reads as the end of the bucket.
            open().use { store ->
                store.createBucket("photos")
                store.write("photos", "a".toByteArray())
                store.write("photos", "b/1".toByteArray())
                store.write("photos", "b/2".toByteArray())
                store.write("photos", "c".toByteArray())
                store.write("photos", "d/1".toByteArray())

                val page = store.list("photos", delimiter = "/".toByteArray(), maxKeys = 3)

                assertEquals(3, page.size, "two keys and a prefix are three of the three asked for")
                assertEquals(listOf("a", "c"), page.keys.map { it.first.toString() })
                assertEquals(listOf("b/"), page.commonPrefixes.map { String(it) })
                assertTrue(page.isTruncated, "there is a fourth thing and the client has to know")
            }
        }

    @Test
    fun `a group ending in the highest byte is stepped over rather than repeated`() =
        runTest {
            // Stepping past a rolled-up group means adding one to its last byte, and `0xFF` has no
            // next. The walk has to carry into the byte before it — and when there is no byte
            // before it either, the listing is finished rather than stuck.
            open().use { store ->
                store.createBucket("photos")
                val high = byteArrayOf(0x61, 0xFF.toByte())
                store.write("photos", high + byteArrayOf(0x01))
                store.write("photos", high + byteArrayOf(0x02))
                store.write("photos", byteArrayOf(0x62, 0x01))

                val page = store.list("photos", delimiter = byteArrayOf(0xFF.toByte()), maxKeys = 10)

                assertEquals(
                    listOf(byteArrayOf(0x62, 0x01).toList()),
                    page.keys.map { it.first.toByteArray().toList() },
                )
                assertEquals(
                    listOf(high.toList()),
                    page.commonPrefixes.map { it.toList() },
                    "the two keys under the high group roll into one prefix",
                )
            }
        }

    @Test
    fun `a key that is entirely the highest byte ends the walk instead of looping`() =
        runTest {
            // Every byte is `0xFF`, so there is nothing to carry into. The only two answers are
            // "the listing is over" and "walk this group again forever", and the second one is a
            // request that never returns.
            open().use { store ->
                store.createBucket("photos")
                store.write("photos", byteArrayOf(0xFF.toByte(), 0x01))
                store.write("photos", byteArrayOf(0xFF.toByte(), 0x02))

                val page = store.list("photos", delimiter = byteArrayOf(0xFF.toByte()), maxKeys = 10)

                assertEquals(1, page.commonPrefixes.size)
                assertEquals(emptyList(), page.keys.map { it.first.toString() })
            }
        }

    @Test
    fun `a delimiter at the very end of a key still makes a group`() =
        runTest {
            // The search for the delimiter has to reach the last position it can occupy. One short
            // of that, `a/` is a key rather than the group `a/`, and every client listing with a
            // delimiter sees a folder become a file.
            open().use { store ->
                store.createBucket("photos")
                store.write("photos", "a/".toByteArray())
                store.write("photos", "b".toByteArray())

                val page = store.list("photos", delimiter = "/".toByteArray(), maxKeys = 10)

                assertEquals(listOf("a/"), page.commonPrefixes.map { String(it) })
                assertEquals(listOf("b"), page.keys.map { it.first.toString() })
            }
        }

    @Test
    fun `a key is its own prefix`() =
        runTest {
            // A prefix exactly as long as the key it is compared with. Refusing the match there
            // drops the object from a listing that names it exactly — which is what a client does
            // when it checks whether one key exists without fetching it.
            open().use { store ->
                store.createBucket("photos")
                store.write("photos", "a.txt".toByteArray())
                store.write("photos", "a.txt.bak".toByteArray())
                store.write("photos", "b.txt".toByteArray())

                val page = store.list("photos", prefix = "a.txt".toByteArray(), maxKeys = 10)

                assertEquals(listOf("a.txt", "a.txt.bak"), page.keys.map { it.first.toString() })
            }
        }

    @Test
    fun `the walk reports how much of the index it read`() =
        runTest {
            // A tombstone is stepped over and never listed, so a page of two keys can cost three
            // entries. The number is how the layer above can tell a listing that is cheap from one
            // that is walking a bucket full of deleted versions.
            open().use { store ->
                store.createBucket("photos")
                store.setVersioning("photos", ObjectStore.Versioning.ENABLED)
                store.write("photos", "a".toByteArray())
                store.write("photos", "gone".toByteArray())
                store.delete("photos", ObjectKey.of("gone"))
                store.write("photos", "z".toByteArray())

                val page = store.list("photos", maxKeys = 10)

                assertEquals(listOf("a", "z"), page.keys.map { it.first.toString() })
                assertTrue(
                    page.entriesRead > page.size,
                    "the tombstone was read and not listed, so reading cost more than listing: " +
                        "${page.entriesRead} against ${page.size}",
                )
            }
        }
}
