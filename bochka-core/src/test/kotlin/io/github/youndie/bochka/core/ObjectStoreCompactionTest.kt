package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Compaction and the published ceiling (M-63, M-64).
 *
 * The crash half of it — a kill in the middle of a rewrite — is [ObjectStoreCompactionCrashTest],
 * which needs a process to kill and so cannot live in the same file.
 */
class ObjectStoreCompactionTest {
    private val dir: Path = Files.createTempDirectory("bochka-compaction")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun store(maxObjects: Int = ObjectStore.ceilingForHeap()) =
        ObjectStore(dir, ObjectStore.Durability.NONE, maxObjects)

    private suspend fun ObjectStore.write(
        key: String,
        content: String,
    ) = put("b", ObjectKey.of(key), Metadata.EMPTY) { out ->
        val bytes = content.toByteArray()
        out.write(bytes, 0, bytes.size)
    }

    @Test
    fun `compaction drops what has been overwritten and keeps what is live`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                repeat(20) { generation -> for (i in 0 until 50) s.write("k$i", "generation $generation") }

                val before = s.logSizeBytes
                val compaction = s.compact()

                assertEquals(before, compaction.bytesBefore)
                assertTrue(
                    compaction.bytesAfter < before / 10,
                    "twenty generations should compact to about one: $before -> ${compaction.bytesAfter}",
                )
                assertEquals(51, compaction.records, "fifty objects and the bucket they are in")
                assertEquals(50, s.objectCount)
                assertEquals("generation 19", s.read("k7"))
            }
        }

    @Test
    fun `a tombstone and the record it buried both go`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                repeat(100) { s.write("gone-$it", "x") }
                repeat(100) { s.delete("b", ObjectKey.of("gone-$it")) }
                s.write("kept", "y")

                val compaction = s.compact()
                assertEquals(2, compaction.records, "one bucket and one object; the other 200 records said nothing")
                assertEquals(1, s.objectCount)
            }
        }

    @Test
    fun `what compaction wrote is what recovery reads`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                s.createBucket("other")
                s.write("a", "first")
                s.write("b/c", "second")
                s.write("a", "first again")
                s.delete("b", ObjectKey.of("nothing"))
                s.compact()
            }

            store().use { reopened ->
                assertEquals(listOf("b", "other"), reopened.bucketNames())
                assertEquals(listOf("a", "b/c"), reopened.list("b").keys.map { it.first.toString() })
                assertEquals("first again", reopened.read("a"))
                assertEquals(RecordLog.Stop.CLEAN, reopened.recovery.stoppedBy)
            }
        }

    @Test
    fun `a version keeps everything it says about itself through a compaction`() =
        runTest {
            // Compaction rewrites the log from memory, so every field of a version has to make the
            // trip. The ones that do not are invisible from outside until somebody needs them: an
            // object whose IV was dropped decrypts to rubbish, a tombstone that came back as an
            // object resurrects deleted data, and a legal hold that vanished is a lock that does
            // not lock. None of those look like a compaction bug from where they surface.
            val iv = ByteArray(16) { it.toByte() }
            store().use { s ->
                s.createBucket("b", owner = "s3main", acl = "public-read")
                s.setVersioning("b", ObjectStore.Versioning.ENABLED)
                s.commit(
                    bucket = "b",
                    key = ObjectKey.of("secret.txt"),
                    metadata = Metadata(contentType = "text/plain"),
                    staged = s.stage { out -> out.write("cipher".toByteArray(), 0, 6) },
                    encryption = ObjectStore.Encryption("AES256", "DWygnHRtgiJ77HCm+1rvHw==", iv),
                    owner = "s3main",
                    acl = "private",
                )
                s.commit(
                    bucket = "b",
                    key = ObjectKey.of("held.txt"),
                    metadata = Metadata(contentType = "text/plain"),
                    staged = s.stage { out -> out.write("plain".toByteArray(), 0, 5) },
                    legalHold = true,
                )
                s.delete("b", ObjectKey.of("held.txt"))
                s.compact()
            }

            store().use { reopened ->
                assertEquals("s3main", reopened.bucketOwner("b"))
                assertEquals("public-read", reopened.bucketAcl("b"))

                val secret = reopened.get("b", ObjectKey.of("secret.txt"))
                assertEquals("AES256", secret?.encryption?.algorithm)
                assertContentEquals(iv, secret?.encryption?.iv)
                assertEquals("s3main", secret?.owner)
                assertEquals("private", secret?.acl)
                assertTrue(secret!!.versionId != ObjectStore.NULL_VERSION, "a versioned write keeps its id")

                // The tombstone is the current version of the key, and the version under it still
                // carries its hold.
                assertTrue(
                    reopened.currentVersion("b", ObjectKey.of("held.txt"))!!.deleteMarker,
                    "a tombstone stays one",
                )
                assertTrue(
                    reopened.versions("b", ObjectKey.of("held.txt")).any { it.legalHold },
                    "the version under the tombstone keeps its legal hold",
                )
            }
        }

    @Test
    fun `an upload in flight survives compaction`() =
        runTest {
            // A compaction that dropped uploads would throw away parts a client has already been
            // told were accepted — the same lie a restart would tell without the log records.
            var uploadId: String
            store().use { s ->
                s.createBucket("b")
                val upload = s.createUpload("b", ObjectKey.of("big.bin"), Metadata(contentType = "text/plain"))
                uploadId = upload.id
                s.putPart(upload.id, 1) { out -> out.write("part one".toByteArray(), 0, 8) }
                s.putPart(upload.id, 2) { out -> out.write("part two".toByteArray(), 0, 8) }
                s.compact()
            }

            store().use { reopened ->
                assertEquals("big.bin", reopened.upload(uploadId)?.key?.toString())
                assertEquals("text/plain", reopened.upload(uploadId)?.metadata?.contentType)
                assertEquals(listOf(1, 2), reopened.parts(uploadId).map { it.number })
            }
        }

    @Test
    fun `compacting twice changes nothing the second time`() =
        runTest {
            // M-66: a compaction writes exactly the live set, so the log does not grow with the
            // number of compactions and neither does the time to open it.
            store().use { s ->
                s.createBucket("b")
                repeat(200) { s.write("k$it", "v") }
                val first = s.compact()
                val second = s.compact()
                assertEquals(first.records, second.records)
                assertEquals(first.bytesAfter, second.bytesAfter)
            }
        }

    @Test
    fun `the ceiling refuses a new key and lets an overwrite through`() =
        runTest {
            store(maxObjects = 3).use { s ->
                s.createBucket("b")
                repeat(3) { s.write("k$it", "v") }

                val refused = assertFailsWith<ObjectStore.CeilingExceeded> { s.write("one too many", "v") }
                assertEquals(3, refused.ceiling)

                // Overwriting costs no index entry, and refusing it would leave a full store
                // unable to make itself smaller.
                s.write("k1", "replaced")
                assertEquals("replaced", s.read("k1"))
            }
        }

    @Test
    fun `a store over its ceiling refuses to open`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                repeat(5) { s.write("k$it", "v") }
            }

            // Risk 7: the alternative is a process that starts and then thrashes, which looks like
            // a slow disk to everybody who did not write the index.
            val refused = assertFailsWith<ObjectStore.CeilingExceeded> { store(maxObjects = 4) }
            assertEquals(5, refused.objects)
            assertEquals(4, refused.ceiling)
        }

    @Test
    fun `the ceiling is derived from the heap, not chosen`() {
        // 650 bytes per object measured (docs/measurements.md), half the heap to the index.
        assertEquals(825_955, ObjectStore.ceilingForHeap(1024L * 1024 * 1024))
        assertTrue(ObjectStore.ceilingForHeap(64L * 1024 * 1024) in 40_000..60_000)
    }

    @Test
    fun `object bytes are untouched by compaction`() =
        runTest {
            store().use { s ->
                s.createBucket("b")
                s.write("a", "the bytes")
                val before = s.get("b", ObjectKey.of("a"))!!
                s.compact()
                val after = s.get("b", ObjectKey.of("a"))!!

                assertEquals(before.fileId, after.fileId, "compaction is about the index, not the objects")
                assertContentEquals("the bytes".toByteArray(), Files.readAllBytes(s.pathOf(after)))
            }
        }

    private fun ObjectStore.read(key: String): String? {
        val stored = get("b", ObjectKey.of(key)) ?: return null
        return String(Files.readAllBytes(pathOf(stored)))
    }
}
