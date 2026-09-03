package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state that is not an object, across a restart (M-253).
 *
 * Versioning, a deleted bucket, an upload in flight, a part already committed: everything the store
 * knows that is not bytes on the disk. Each of those is an entry in the index journal, and each of
 * those entries is written by a single call — which five mutations removed without a single test
 * noticing, because in memory the answer stays right until the process ends.
 *
 * The store's own KDoc says what that costs: a configuration that survives a restart only in memory
 * is a configuration the client was told an untruth about. A test that never reopens the store is
 * a test that cannot tell one from the other.
 */
class ObjectStoreJournalTest {
    private val dir: Path = Files.createTempDirectory("bochka-journal")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun open() = ObjectStore(dir, ObjectStore.Durability.NONE)

    private suspend fun ObjectStore.write(
        bucket: String,
        key: String,
        content: String,
    ) = put(bucket, ObjectKey.of(key), Metadata(contentType = "text/plain")) { out ->
        val bytes = content.toByteArray()
        out.write(bytes, 0, bytes.size)
    }

    @Test
    fun `versioning outlives the process that turned it on`() {
        open().use { store ->
            store.createBucket("photos")
            store.setVersioning("photos", ObjectStore.Versioning.ENABLED)
            assertEquals(ObjectStore.Versioning.ENABLED, store.versioning("photos"))
        }

        open().use { store ->
            assertEquals(
                ObjectStore.Versioning.ENABLED,
                store.versioning("photos"),
                "a bucket that answered ENABLED before the restart cannot answer NONE after it",
            )
            store.setVersioning("photos", ObjectStore.Versioning.SUSPENDED)
        }

        // And so does turning it down: suspended is a third state, not the absence of the second.
        open().use { store ->
            assertEquals(ObjectStore.Versioning.SUSPENDED, store.versioning("photos"))
        }
    }

    @Test
    fun `a deleted bucket stays deleted`() {
        open().use { store ->
            store.createBucket("photos")
            store.createBucket("scratch")
            assertTrue(store.deleteBucket("scratch"))
        }

        open().use { store ->
            assertEquals(listOf("photos"), store.bucketNames(), "the delete has to be an entry, not a gap")
        }
    }

    @Test
    fun `an upload in flight and the parts already sent both survive a restart`() =
        runTest {
            // This is the one a client feels: a multipart upload takes minutes, a deploy takes
            // seconds, and a part that was acknowledged and then forgotten is an upload that can
            // never be completed.
            val id: String
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("big.bin"), Metadata())
                id = upload.id
                store.putPart(
                    upload.id,
                    1,
                ) { out -> out.write(ByteArray(MINIMUM_PART) { 'a'.code.toByte() }, 0, MINIMUM_PART) }
            }

            open().use { store ->
                val upload = store.upload(id)
                assertEquals(ObjectKey.of("big.bin"), upload?.key, "the upload was acknowledged before the restart")
                assertEquals(listOf(1), store.parts(id).map { it.number })
                assertEquals(listOf(id), store.uploads("photos").map { it.id })

                // And it can still be finished, which is the only thing the client wanted.
                val part = store.parts(id).single()
                store.putPart(id, 2) { out -> out.write("tail".toByteArray(), 0, 4) }
                val tail = store.parts(id).first { it.number == 2 }
                val stored = store.completeUpload(id, listOf(1 to part.eTag, 2 to tail.eTag))
                assertEquals(MINIMUM_PART + 4L, stored.size)
            }
        }

    @Test
    fun `creating a bucket says whether it created one`() {
        // The second call is not an error and not a success: `false` is how the layer above answers
        // `BucketAlreadyOwnedByYou` rather than pretending it made a bucket.
        open().use { store ->
            assertTrue(store.createBucket("photos"), "the first call makes it")
            assertFalse(store.createBucket("photos"), "the second finds it already there")

            assertTrue(store.hasBucket("photos"))
            assertFalse(store.hasBucket("nothing"), "a bucket nobody made is not there")

            assertFalse(store.deleteBucket("nothing"), "deleting what is not there deletes nothing")
        }
    }

    @Test
    fun `every version gets a name of its own, and none of them is empty`() =
        runTest {
            // A version id is how a client names a version later. Two the same means the second
            // write hides the first; an empty one means the object has no name at all — and both
            // read as an ordinary successful `PUT`.
            open().use { store ->
                store.createBucket("photos")
                store.setVersioning("photos", ObjectStore.Versioning.ENABLED)

                val ids = (1..8).map { store.write("photos", "a.txt", "v$it").versionId }

                assertEquals(8, ids.toSet().size, "eight writes are eight versions")
                assertTrue(ids.none { it.isNullOrEmpty() }, "a version with no name cannot be asked for: $ids")
                assertNotEquals(ids.first(), ids.last())
            }
        }

    @Test
    fun `a delete marker is an entry too, and the versions under it stay`() =
        runTest {
            val versioned: String?
            open().use { store ->
                store.createBucket("photos")
                store.setVersioning("photos", ObjectStore.Versioning.ENABLED)
                versioned = store.write("photos", "a.txt", "first").versionId
                store.delete("photos", ObjectKey.of("a.txt"))
                assertNull(store.get("photos", ObjectKey.of("a.txt")), "the tombstone hides it")
            }

            open().use { store ->
                assertNull(
                    store.get("photos", ObjectKey.of("a.txt")),
                    "a tombstone that is not in the journal brings the object back from the dead",
                )
                assertEquals(
                    "first",
                    String(Files.readAllBytes(store.pathOf(store.get("photos", ObjectKey.of("a.txt"), versioned!!)!!))),
                    "and the version under it is still reachable by name",
                )
            }
        }

    @Test
    fun `a completed upload's lock is in the record that creates the version, not a second one`() =
        runTest {
            // M-175 states the property as an absence: "one index record instead of two, and no
            // window". The end state is already checked elsewhere -- the object comes out locked --
            // and the end state is exactly what a two-record implementation also produces. What
            // separates them is only visible in the journal: with two records there is a moment,
            // and a crash inside it, where the object exists and is not protected yet.
            val until = System.currentTimeMillis() + 3_600_000
            open().use { store ->
                store.createBucket("photos")
                val upload =
                    store.createUpload(
                        "photos",
                        ObjectKey.of("big.bin"),
                        Metadata(),
                        retention = ObjectStore.Retention("COMPLIANCE", until),
                        legalHold = true,
                    )
                val part =
                    store.putPart(
                        upload.id,
                        1,
                    ) { out -> out.write(ByteArray(MINIMUM_PART) { 'a'.code.toByte() }, 0, MINIMUM_PART) }
                store.completeUpload(upload.id, listOf(1 to part.eTag))
            }

            val puts = mutableListOf<IndexRecord.Put>()
            RecordLog(dir.resolve("index.log")).use { log ->
                log.recover { payload ->
                    val record = IndexRecord.decode(payload)
                    if (record is IndexRecord.Put && record.key == ObjectKey.of("big.bin")) puts += record
                }
            }

            assertEquals(1, puts.size, "the version was written more than once: $puts")
            assertEquals("COMPLIANCE", puts.single().retentionMode)
            assertEquals(until, puts.single().retentionUntilMillis)
            assertTrue(puts.single().legalHold)
        }

    private companion object {
        const val MINIMUM_PART = 5 * 1024 * 1024
    }
}
