package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The listing walk: prefixes, delimiters, pages and the order they come in (M-48…M-50).
 *
 * The rule the order comes from is `s3-service-2.json`, `ListObjectsV2`, "Sorting order of returned
 * objects" — lexicographical by key name, which for a byte string is unsigned by byte (§1.5).
 */
class ObjectStoreListingTest {
    private val dir: Path = Files.createTempDirectory("bochka-listing")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private suspend fun ObjectStore.write(vararg keys: String) {
        for (key in keys) {
            put("b", ObjectKey.of(key), Metadata.EMPTY) { out ->
                val bytes = key.toByteArray()
                out.write(bytes, 0, bytes.size)
            }
        }
    }

    private fun ObjectStore.Page.keyNames() = keys.map { it.first.toString() }

    private fun ObjectStore.Page.prefixNames() = commonPrefixes.map { String(it) }

    private fun store() = ObjectStore(dir, ObjectStore.Durability.NONE).also { it.createBucket("b") }

    @Test
    fun `a delimiter rolls keys up into common prefixes`() =
        runTest {
            store().use { s ->
                s.write("asdf", "boo/bar", "boo/baz/xyzzy", "cquux/thud", "cquux/bla")

                // ceph/s3-tests, test_bucket_list_delimiter_basic: the keys that hold no delimiter
                // stay keys, everything else collapses to its first segment.
                val page = s.list("b", delimiter = "/".toByteArray())
                assertEquals(listOf("asdf"), page.keyNames())
                assertEquals(listOf("boo/", "cquux/"), page.prefixNames())
            }
        }

    @Test
    fun `a prefix bounds the walk and the delimiter groups what is left`() =
        runTest {
            store().use { s ->
                s.write("foo/bar", "foo/baz/xyzzy", "quux/thud", "asdf")

                // test_bucket_list_delimiter_prefix: the delimiter is looked for *after* the
                // prefix, so `foo/baz/` groups on the second slash and not the first.
                val page = s.list("b", prefix = "foo/".toByteArray(), delimiter = "/".toByteArray())
                assertEquals(listOf("foo/bar"), page.keyNames())
                assertEquals(listOf("foo/baz/"), page.prefixNames())
            }
        }

    @Test
    fun `a group is jumped over, not walked through`() =
        runTest {
            // The point of M-49 and the reason the index is ordered rather than hashed. Ten
            // thousand objects under one prefix have to cost one entry to list, not ten thousand.
            //
            // Measured by counting reads rather than by timing: a stopwatch on a shared machine
            // measures the machine (booblik, measurement 13). What is counted is how many index
            // entries the walk touched, which a walk-through cannot fake.
            store().use { s ->
                for (i in 0 until 10_000) s.write("crowded/%05d".format(i))
                s.write("after")

                val page = s.list("b", delimiter = "/".toByteArray())
                assertEquals(listOf("after"), page.keyNames())
                assertEquals(listOf("crowded/"), page.prefixNames())
                assertTrue(
                    page.entriesRead <= 4,
                    "listing 10 000 objects under one prefix read ${page.entriesRead} index entries",
                )
            }
        }

    @Test
    fun `pages join up without a gap and without a repeat`() =
        runTest {
            store().use { s ->
                s.write(*(0 until 25).map { "k%02d".format(it) }.toTypedArray())

                val seen = mutableListOf<String>()
                var after: ByteArray? = null
                var pages = 0
                var more = true
                while (more) {
                    val page = s.list("b", startAfter = after, maxKeys = 7)
                    seen += page.keyNames()
                    after = page.nextAfter
                    more = page.isTruncated
                    pages++
                }

                assertEquals(4, pages)
                assertEquals((0 until 25).map { "k%02d".format(it) }, seen)
            }
        }

    @Test
    fun `a page truncated on a group resumes past the whole group`() =
        runTest {
            // The failure this exists for: a rolled-up prefix sorts *before* every key under it,
            // so resuming at it naively rolls the same group up again and the walk never ends.
            store().use { s ->
                s.write("a/1", "a/2", "b/1", "b/2", "c/1")

                val first = s.list("b", delimiter = "/".toByteArray(), maxKeys = 1)
                assertEquals(listOf("a/"), first.prefixNames())
                assertTrue(first.isTruncated)

                val second = s.list("b", delimiter = "/".toByteArray(), startAfter = first.nextAfter, maxKeys = 1)
                assertEquals(listOf("b/"), second.prefixNames())

                val third = s.list("b", delimiter = "/".toByteArray(), startAfter = second.nextAfter, maxKeys = 1)
                assertEquals(listOf("c/"), third.prefixNames())
                assertFalse(third.isTruncated)
                assertNull(third.nextAfter)
            }
        }

    @Test
    fun `a common prefix counts against max-keys like a key does`() =
        runTest {
            store().use { s ->
                s.write("a/1", "b", "c/1")

                val page = s.list("b", delimiter = "/".toByteArray(), maxKeys = 2)
                assertEquals(listOf("b"), page.keyNames())
                assertEquals(listOf("a/"), page.prefixNames())
                assertEquals(2, page.size)
                assertTrue(page.isTruncated)
            }
        }

    @Test
    fun `max-keys of zero is an answer, not an empty loop`() =
        runTest {
            store().use { s ->
                s.write("a", "b")
                val page = s.list("b", maxKeys = 0)
                assertEquals(emptyList(), page.keyNames())
                assertFalse(page.isTruncated, "a page of nothing is not a page with more to come")
            }
        }

    @Test
    fun `the order is by bytes, including outside the basic plane`() =
        runTest {
            store().use { s ->
                // §1.5, measured: `String.compareTo` puts the emoji first, because UTF-16 encodes
                // it as a surrogate pair that starts below U+FF01. In UTF-8 it does not.
                s.write("！", "😀", "z")
                assertEquals(listOf("z", "！", "😀"), s.list("b").keyNames())
            }
        }

    @Test
    fun `a delimiter that is not one byte still groups`() =
        runTest {
            store().use { s ->
                // The delimiter is an arbitrary string in the model, not a character.
                s.write("aXXb", "aXXc", "ad")
                val page = s.list("b", delimiter = "XX".toByteArray())
                assertEquals(listOf("ad"), page.keyNames())
                assertEquals(listOf("aXX"), page.prefixNames())
            }
        }

    @Test
    fun `a group of high bytes ends the walk rather than wrapping`() =
        runTest {
            store().use { s ->
                // There is no byte string greater than one that is all 0xFF, so seeking past such
                // a group has no answer. Returning to the start would loop for ever.
                s.put("b", ObjectKey(byteArrayOf(0xFF.toByte(), 0xFF.toByte())), Metadata.EMPTY) { }
                val page = s.list("b", delimiter = byteArrayOf(0xFF.toByte()))
                assertEquals(1, page.commonPrefixes.size)
                assertFalse(page.isTruncated)
            }
        }
}
