package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Joining the parts, and what happens when one of them is not what the index says it is (M-254).
 *
 * The loop that copies a part into the object is written for a kernel that moves less than it was
 * asked to — the KDoc next to it says the loop is not optional — and at test sizes a kernel never
 * does. So its second iteration, and the check that says a part ended early, had never run: five
 * mutations of that arithmetic survived every completion the suite performs.
 *
 * The way to reach it without a five-gigabyte object is to make the claim false from the other side:
 * truncate the part on the disk and let the index go on believing its size. What must not happen is
 * an object shorter than promised, quietly.
 */
class CompleteUploadIntegrityTest {
    private val dir: Path = Files.createTempDirectory("bochka-integrity")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun open() = ObjectStore(dir, ObjectStore.Durability.NONE)

    private suspend fun ObjectStore.part(
        upload: String,
        number: Int,
        size: Int,
    ): ObjectStore.Part {
        val bytes = ByteArray(size) { ('a' + number).code.toByte() }
        return putPart(upload, number) { out -> out.write(bytes, 0, bytes.size) }
    }

    @Test
    fun `a part whose bytes went missing stops the completion instead of shortening the object`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("big.bin"), Metadata())
                val first = store.part(upload.id, 1, MINIMUM_PART)
                val second = store.part(upload.id, 2, 16)

                // The index still says five mebibytes; the file no longer has them. This is what a
                // disk that lost a block looks like from inside the process.
                writable(fileOf(first.fileId)).use { it.truncate(1024) }

                assertFailsWith<java.io.EOFException> {
                    store.completeUpload(upload.id, listOf(1 to first.eTag, 2 to second.eTag))
                }

                assertNull(
                    store.get("photos", ObjectKey.of("big.bin")),
                    "nothing is published when the parts cannot be joined",
                )
            }
        }

    @Test
    fun `a completion repeated answers the same thing rather than refusing`() =
        runTest {
            // A client whose connection dropped after the completion retries it, and by then the
            // upload is gone. The remembered answer is the only thing between that client and a
            // `NoSuchUpload` for an object that exists.
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("retry.bin"), Metadata())
                val only = store.part(upload.id, 1, 12)

                val stored = store.completeUpload(upload.id, listOf(1 to only.eTag))

                val remembered = assertNotNull(store.completion(upload.id), "the completion has to be remembered")
                assertEquals(stored.eTag, remembered.eTag)
                assertEquals(ObjectKey.of("retry.bin"), remembered.key)
                assertEquals("photos", remembered.bucket)
            }
        }

    @Test
    fun `a completed upload does not come back when the store reopens`() =
        runTest {
            // The upload's end is an entry in the journal like its beginning. Without it a restart
            // replays the start and not the finish, and an upload nobody can complete twice is
            // listed as still in flight — for a client, and for the sweep that aborts abandoned
            // ones.
            val id: String
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("done.bin"), Metadata())
                id = upload.id
                val only = store.part(upload.id, 1, 9)
                store.completeUpload(upload.id, listOf(1 to only.eTag))
            }

            open().use { store ->
                assertNull(store.upload(id), "a finished upload is finished after a restart too")
                assertEquals(emptyList(), store.uploads("photos"))
                assertEquals(9L, store.get("photos", ObjectKey.of("done.bin"))?.size)
            }
        }

    @Test
    fun `a checksum the client states has to be the one the parts make`() =
        runTest {
            // The layer above combines the parts' checksums; this one compares the answer with what
            // the completion claimed, before anything is written. Claiming one and storing another
            // would hand a client a `200` for an object it will later refuse to trust.
            open().use { store ->
                store.createBucket("photos")
                val upload =
                    store.createUpload(
                        "photos",
                        ObjectKey.of("sum.bin"),
                        Metadata(),
                        checksumAlgorithm = "crc32",
                        checksumType = "COMPOSITE",
                    )
                // The part has to carry a checksum of its own, or there is nothing to combine and
                // the comparison below would be against a `null` either way — which is how the
                // first version of this test managed to fail for the wrong reason.
                val staged = store.stage { out -> out.write("hello".toByteArray(), 0, 5) }
                val only = store.commitPart(upload.id, 1, staged, Metadata.Checksum("crc32", "CCCCCC=="))

                val made = Metadata.Checksum("crc32", "AAAAAA==")
                val refused =
                    assertFailsWith<ObjectStore.CompletionRefused> {
                        store.completeUpload(
                            upload.id,
                            listOf(1 to only.eTag),
                            combineChecksums = { _, _ -> made },
                            expectedChecksum = Metadata.Checksum("crc32", "BBBBBB=="),
                        )
                    }
                assertEquals(ObjectStore.CompletionRefused.Reason.CHECKSUM_MISMATCH, refused.reason)
                assertNull(store.get("photos", ObjectKey.of("sum.bin")), "a refused completion writes nothing")

                // And the same completion whose claim matches goes through, or the assertion above
                // would hold for a store that refuses every checksum.
                val stored =
                    store.completeUpload(
                        upload.id,
                        listOf(1 to only.eTag),
                        combineChecksums = { _, _ -> made },
                        expectedChecksum = made,
                    )
                assertEquals(made, stored.metadata.checksum)
            }
        }

    @Test
    fun `a checksum is combined only when every part carried one`() =
        runTest {
            // A mixture has no answer, and inventing one is worse than saying nothing: a client
            // reading a composite checksum believes every part went into it.
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("mixed.bin"), Metadata())
                val first = store.part(upload.id, 1, MINIMUM_PART)
                val second = store.part(upload.id, 2, 4)

                var asked = false
                val stored =
                    store.completeUpload(
                        upload.id,
                        listOf(1 to first.eTag, 2 to second.eTag),
                        combineChecksums = { _, _ ->
                            asked = true
                            Metadata.Checksum("crc32", "AAAAAA==")
                        },
                    )

                assertTrue(!asked, "no part carried a checksum, so there was nothing to combine")
                assertNull(stored.metadata.checksum)
            }
        }

    /**
     * Where a part's bytes live, spelled out here rather than asked for.
     *
     * The store hands out the path of a **stored object** and not of a part, and rightly: a part is
     * its business until it becomes one. The layout is two directory levels of the file's own name,
     * which this test has to know because it is standing in for a disk that lost a block.
     */
    private fun fileOf(fileId: String): Path =
        dir
            .resolve("data")
            .resolve(fileId.substring(0, 2))
            .resolve(fileId.substring(2, 4))
            .resolve(fileId)

    private fun writable(path: Path) =
        java.nio.channels.FileChannel
            .open(path, StandardOpenOption.WRITE)

    private companion object {
        const val MINIMUM_PART = 5 * 1024 * 1024
    }
}
