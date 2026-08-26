package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a delete does in each of the three states a bucket can be in (M-254).
 *
 * `delete` is one function with four decisions inside it — a precondition, whether versioning is off,
 * whether it is on or merely suspended, and what the tombstone's version is called — and five
 * negated conditionals survived across them. The reason is the same everywhere in this file's
 * neighbourhood: the tests that exercise deletion do it in one state, and a branch nobody takes both
 * ways is a branch nothing distinguishes.
 *
 * The three states are not degrees of the same thing. Off means the bytes go; on means nothing goes
 * and a tombstone is laid; suspended means a tombstone is laid **and replaces** the previous one, so
 * repeated deletes do not pile up. Reading the second as the third loses a version each time.
 */
class ObjectStoreDeleteTest {
    private val dir: Path = Files.createTempDirectory("bochka-delete")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun open() = ObjectStore(dir, ObjectStore.Durability.NONE)

    private suspend fun ObjectStore.write(
        bucket: String,
        key: String,
        content: String,
    ) = put(bucket, ObjectKey.of(key), Metadata()) { out ->
        val bytes = content.toByteArray()
        out.write(bytes, 0, bytes.size)
    }

    @Test
    fun `without versioning the entry and its file both go`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                val stored = store.write("photos", "a.txt", "body")
                val file = store.pathOf(stored)
                assertTrue(Files.exists(file))

                val deletion = store.delete("photos", ObjectKey.of("a.txt"))

                assertTrue(deletion.existed, "the object was there and the caller is told so")
                assertNull(deletion.marker, "nothing is laid down where nothing is kept")
                assertNull(store.get("photos", ObjectKey.of("a.txt")))
                assertFalse(Files.exists(file), "the bytes go with the entry, or the disk fills with orphans")
            }
        }

    @Test
    fun `deleting a key that is not there is a success that says so`() =
        runTest {
            // Not an error: S3 answers `204` either way, and the difference between the two lives in
            // the flag rather than in the status. The layer above needs it for the batch form, which
            // reports per key.
            open().use { store ->
                store.createBucket("photos")

                val deletion = store.delete("photos", ObjectKey.of("never.txt"))

                assertFalse(deletion.existed)
                assertNull(deletion.marker)
            }
        }

    @Test
    fun `with versioning on, nothing goes and the tombstone gets a name of its own`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                store.setVersioning("photos", ObjectStore.Versioning.ENABLED)
                val first = store.write("photos", "a.txt", "body")

                val deletion = store.delete("photos", ObjectKey.of("a.txt"))
                val marker = assertNotNull(deletion.marker, "a versioning bucket lays a tombstone")

                assertTrue(deletion.existed)
                assertTrue(marker.deleteMarker)
                assertNotEquals("null", marker.versionId, "an enabled bucket mints a version for the tombstone")
                assertNotEquals(first.versionId, marker.versionId)
                assertNull(store.get("photos", ObjectKey.of("a.txt")), "the current version is the tombstone")
                assertEquals(
                    "body",
                    String(
                        Files.readAllBytes(store.pathOf(store.get("photos", ObjectKey.of("a.txt"), first.versionId)!!)),
                    ),
                    "and the version underneath is still reachable by name",
                )
            }
        }

    @Test
    fun `with versioning suspended the tombstone is the null version and replaces the last one`() =
        runTest {
            // The difference between suspended and enabled, and the only place it shows: a suspended
            // bucket has exactly one `null` version at a time. Laying a fresh tombstone beside the
            // old one instead of over it grows the history of a bucket whose owner turned versioning
            // off precisely so it would stop growing.
            open().use { store ->
                store.createBucket("photos")
                store.setVersioning("photos", ObjectStore.Versioning.ENABLED)
                val kept = store.write("photos", "a.txt", "kept")
                store.setVersioning("photos", ObjectStore.Versioning.SUSPENDED)
                store.write("photos", "a.txt", "current")

                val first = store.delete("photos", ObjectKey.of("a.txt"))
                val second = store.delete("photos", ObjectKey.of("a.txt"))

                assertEquals("null", assertNotNull(first.marker).versionId)
                assertEquals("null", assertNotNull(second.marker).versionId)
                assertEquals(
                    1,
                    store.versions("photos", ObjectKey.of("a.txt")).count { it.versionId == "null" },
                    "a suspended bucket holds one null version, however many times it is deleted",
                )
                assertNotNull(
                    store.get("photos", ObjectKey.of("a.txt"), kept.versionId),
                    "the version made while versioning was on is not touched by any of this",
                )
            }
        }

    @Test
    fun `a precondition that does not hold refuses the delete and leaves the object`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                val stored = store.write("photos", "a.txt", "body")

                val refused =
                    assertFailsWith<ObjectStore.PreconditionFailed> {
                        store.delete(
                            "photos",
                            ObjectKey.of("a.txt"),
                            ObjectStore.Precondition(ifMatch = listOf("\"not-the-etag\"")),
                        )
                    }
                assertEquals(ObjectStore.Outcome.MISMATCH, refused.outcome)
                assertNotNull(store.get("photos", ObjectKey.of("a.txt")), "a refused delete deletes nothing")

                // The same condition naming the object's own tag goes through, or the assertion
                // above would hold for a store that refuses every conditional delete.
                assertTrue(
                    store
                        .delete(
                            "photos",
                            ObjectKey.of("a.txt"),
                            ObjectStore.Precondition(ifMatch = listOf(stored.eTag)),
                        ).existed,
                )
            }
        }

    @Test
    fun `a precondition about an object that is not there has nothing to be wrong about`() =
        runTest {
            // Deleting a key that is missing already succeeded, and a condition on it cannot change
            // that: there is no object for the condition to disagree with. Checking it anyway turns
            // a `204` into a `412` for a client that is already in the state it asked for.
            open().use { store ->
                store.createBucket("photos")

                val deletion =
                    store.delete(
                        "photos",
                        ObjectKey.of("never.txt"),
                        ObjectStore.Precondition(ifMatch = listOf("\"anything\"")),
                    )

                assertFalse(deletion.existed)
            }
        }
}
