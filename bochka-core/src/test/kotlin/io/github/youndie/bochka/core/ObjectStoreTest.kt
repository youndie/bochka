package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectStoreTest {
    private val dir: Path = Files.createTempDirectory("bochka-store")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun store() = ObjectStore(dir)

    private suspend fun ObjectStore.put(
        bucket: String,
        key: String,
        content: String,
    ) = put(bucket, ObjectKey.of(key), Metadata(contentType = "text/plain")) { out ->
        val bytes = content.toByteArray()
        out.write(bytes, 0, bytes.size)
    }

    private fun ObjectStore.read(
        bucket: String,
        key: String,
    ): String? {
        val stored = get(bucket, ObjectKey.of(key)) ?: return null
        return String(Files.readAllBytes(pathOf(stored)))
    }

    @Test
    fun `an object comes back after a restart`() =
        runTest {
            store().use { s ->
                s.createBucket("photos")
                s.put("photos", "a.txt", "hello")
            }

            store().use { s ->
                assertTrue(s.hasBucket("photos"))
                assertEquals("hello", s.read("photos", "a.txt"))
                assertEquals(RecordLog.Stop.CLEAN, s.recovery.stoppedBy)
                assertEquals(2, s.recovery.records, "one bucket and one object")
            }
        }

    @Test
    fun `the key never appears in a path on disk`() =
        runTest {
            // The decision this store is built around (Р2). If a key ever reached the filesystem,
            // this is where it would show: two keys that a mac folds into one file, and one that
            // no filesystem would accept as a name.
            store().use { s ->
                s.createBucket("b")
                s.put("b", "Photo.JPG", "upper")
                s.put("b", "photo.jpg", "lower")
                // Written with escapes rather than as literals: the two spellings look identical
                // in a source file, and the first version of this test put the same key twice
                // without either the editor or the compiler saying so.
                s.put("b", "caf\u00E9.txt", "composed")
                s.put("b", "cafe\u0301.txt", "decomposed")
                s.put("b", "a/b", "file")
                s.put("b", "a/b/c", "under the file")

                assertEquals("upper", s.read("b", "Photo.JPG"))
                assertEquals("lower", s.read("b", "photo.jpg"))
                assertEquals("composed", s.read("b", "caf\u00E9.txt"))
                assertEquals("decomposed", s.read("b", "cafe\u0301.txt"))
                assertEquals("file", s.read("b", "a/b"))
                assertEquals("under the file", s.read("b", "a/b/c"))
            }

            val names =
                Files
                    .walk(
                        dir.resolve("data"),
                    ).filter(Files::isRegularFile)
                    .map { it.fileName.toString() }
                    .toList()
            assertEquals(6, names.size)
            // No name on disk carries anything from a key: they are UUIDs and nothing else.
            val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            assertTrue(names.all { uuid.matches(it) }, "names on disk: $names")
        }

    @Test
    fun `a reader that opened the old file keeps reading it after the key is replaced`() =
        runTest {
            // R2 and M-44, and until now this was held by the shape of the code rather than by a
            // check: every version is a file of its own, and the replaced one is unlinked only
            // after the index has stopped pointing at it, so a `GET` already streaming does not
            // see bytes change underneath it. Writing a version in place would keep every other
            // test in this file green and hand a reader half of each object.
            store().use { s ->
                s.createBucket("b")
                val first = s.put("b", "k", "one")

                FileChannel.open(s.pathOf(first), StandardOpenOption.READ).use { open ->
                    val second = s.put("b", "k", "two")
                    assertNotEquals(first.fileId, second.fileId, "a version must not reuse the file of the one before")

                    val buffer = ByteBuffer.allocate(16)
                    val read = open.read(buffer)
                    assertEquals("one", String(buffer.array(), 0, read), "the reader was handed the new bytes")
                }

                assertEquals("two", s.read("b", "k"))
            }
        }

    @Test
    fun `replacing a key deletes the file it replaced`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                val first = s.put("b", "k", "one")
                val second = s.put("b", "k", "two")

                assertEquals("two", s.read("b", "k"))
                assertFalse(Files.exists(s.pathOf(first)), "the replaced file must go")
                assertTrue(Files.exists(s.pathOf(second)))
            }
        }

    @Test
    fun `deleting removes the entry and the file`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                val stored = s.put("b", "k", "bye")

                assertTrue(s.delete("b", ObjectKey.of("k")).existed)
                assertNull(s.get("b", ObjectKey.of("k")))
                assertFalse(Files.exists(s.pathOf(stored)))
                assertFalse(s.delete("b", ObjectKey.of("k")).existed, "deleting twice reports nothing removed")
            }

            store().use { s -> assertNull(s.get("b", ObjectKey.of("k"))) }
        }

    @Test
    fun `a listing is ordered by the bytes of the key and stops at the prefix`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                s.createBucket("other")
                for (key in listOf("a/2", "a/10", "a/1", "b/1", "😀", "！")) s.put("b", key, key)
                s.put("other", "a/1", "elsewhere")

                val all = s.list("b", maxKeys = 100).keys.map { it.first.toString() }
                assertEquals(listOf("a/1", "a/10", "a/2", "b/1", "！", "😀"), all)

                val underA = s.list("b", "a/".toByteArray(), maxKeys = 100).keys.map { it.first.toString() }
                assertEquals(listOf("a/1", "a/10", "a/2"), underA, "the prefix must bound the walk")

                assertEquals(1, s.list("other", maxKeys = 100).size, "buckets do not leak into each other")
            }
        }

    @Test
    fun `a listing can continue from where the last page ended`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                for (i in 0 until 10) s.put("b", "k$i", "v")

                val first = s.list("b", maxKeys = 4)
                val second = s.list("b", maxKeys = 4, startAfter = first.nextAfter)

                assertEquals(listOf("k0", "k1", "k2", "k3"), first.keys.map { it.first.toString() })
                assertEquals(listOf("k4", "k5", "k6", "k7"), second.keys.map { it.first.toString() })
            }
        }

    @Test
    fun `a bucket with objects in it refuses to go`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                s.put("b", "k", "v")

                assertFalse(s.deleteBucket("b"))
                assertTrue(s.hasBucket("b"))

                s.delete("b", ObjectKey.of("k"))
                assertTrue(s.deleteBucket("b"))
            }
        }

    @Test
    fun `a file nobody points at is swept, and one in flight is not`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                s.put("b", "k", "kept")

                // What a crash between the file and its index record leaves behind.
                val orphan =
                    dir
                        .resolve("data")
                        .resolve("ff")
                        .resolve("ff")
                        .resolve("ffffffff-orphan")
                Files.createDirectories(orphan.parent)
                Files.write(orphan, "nobody points at me".toByteArray())
                Files.setLastModifiedTime(
                    orphan,
                    java.nio.file.attribute.FileTime
                        .fromMillis(0),
                )

                val fresh = orphan.resolveSibling("ffffffff-fresh")
                Files.write(fresh, "written a moment ago".toByteArray())

                assertEquals(1, s.sweepOrphans(), "only the old orphan")
                assertFalse(Files.exists(orphan))
                assertTrue(Files.exists(fresh), "an upload in flight looks exactly like an orphan")
                assertEquals("kept", s.read("b", "k"), "and the object itself is untouched")
            }
        }

    @Test
    fun `what the index promises, the disk has`() =
        runTest {
            // The invariant the write order exists for, checked the cheap way here and the
            // expensive way in ObjectStoreCrashTest.
            store().use { s ->
                s.createBucket("b")
                for (i in 0 until 50) s.put("b", "k$i", "value $i")
                for (i in 0 until 50 step 3) s.delete("b", ObjectKey.of("k$i"))
            }

            store().use { s ->
                for ((key, stored) in s.list("b", maxKeys = 1000).keys) {
                    val path = s.pathOf(stored)
                    assertTrue(Files.exists(path), "$key points at a file that is not there")
                    assertEquals(stored.size, Files.size(path), "$key has the wrong size on disk")
                }
                // 50 written, every third of them deleted: 0, 3, … 48 is seventeen keys.
                assertEquals(33, s.objectCount)
            }
        }

    @Test
    fun `an object with no content type and an empty body is still an object`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                val stored = s.put("b", ObjectKey.of("empty"), Metadata.EMPTY) { }

                assertEquals(0, stored.size)
                assertNull(stored.metadata.contentType)
                assertNotNull(s.get("b", ObjectKey.of("empty")))
                assertContentEquals(ByteArray(0), Files.readAllBytes(s.pathOf(stored)))
            }
        }

    @Test
    fun `a write into a bucket deleted while it was uploading is refused`() =
        runTest {
            // M-220, and it was found on a deployment rather than here.
            //
            // The bucket is checked before the body is read — that is the point of §1.2.2 — and
            // then the body takes as long as the client takes. Nothing looks again. `DeleteBucket`
            // succeeds meanwhile because the bucket **is** empty at that instant: the bytes are
            // staged and belong to nobody yet. Then the commit puts a version into a bucket that
            // no longer exists and the client is told `200` for bytes it will never read back,
            // which is the one answer a durability product may not give.
            store().use { s ->
                s.createBucket("photos")
                val staged =
                    s.stage { out ->
                        out.write("содержимое".toByteArray(), 0, "содержимое".toByteArray().size)
                    }

                assertTrue(s.deleteBucket("photos"), "empty at this instant, so S3 lets the bucket go")

                assertFailsWith<ObjectStore.BucketGone> {
                    s.commit("photos", ObjectKey.of("a.txt"), Metadata.EMPTY, staged)
                }
                assertNull(s.get("photos", ObjectKey.of("a.txt")))

                // And the second half of the damage, which is quieter: a version left in a deleted
                // bucket makes that name un-deletable once somebody takes it again. The bucket
                // reads as non-empty, and the object making it so is one no listing shows.
                s.createBucket("photos")
                assertTrue(s.deleteBucket("photos"), "nothing was left behind under the old name")
            }
        }

    @Test
    fun `a multipart completion into a bucket that was deleted is refused`() =
        runTest {
            // The same hole through the other door, and worth its own test because the two
            // commits are different functions: fixing one and leaving the other is the shape of
            // mistake this repository has made before with `screen` and `handle`.
            store().use { s ->
                s.createBucket("photos")
                val upload = s.createUpload("photos", ObjectKey.of("big.bin"), Metadata.EMPTY)
                val part = s.putPart(upload.id, 1) { out -> out.write("part one".toByteArray(), 0, 8) }

                assertTrue(s.deleteBucket("photos"), "an upload nobody finished does not make a bucket non-empty")

                assertFailsWith<ObjectStore.BucketGone> {
                    s.completeUpload(upload.id, listOf(1 to part.eTag))
                }
                assertNull(s.get("photos", ObjectKey.of("big.bin")))
            }
        }
}
