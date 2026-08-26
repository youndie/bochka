package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a completion refuses, and where each refusal's boundary lies (M-253).
 *
 * Eleven mutations lived in `completeUpload`, most of them in the walk that checks the parts a
 * client named. They lived because every existing completion hands over the parts it has, in order,
 * more than one of them and none of them repeated — a shape in which the comparison at the heart of
 * the check never has to decide anything.
 *
 * Each refusal below carries a **reason**, and the reason is the whole of it: the layer above turns
 * it into a different S3 error each time, and a client fixes a different thing for each one.
 */
class CompleteUploadRefusalTest {
    private val dir: Path = Files.createTempDirectory("bochka-complete")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun open() = ObjectStore(dir, ObjectStore.Durability.NONE)

    private suspend fun ObjectStore.part(
        upload: String,
        number: Int,
        size: Int,
    ): Pair<Int, String> {
        val bytes = ByteArray(size) { ('a' + number).code.toByte() }
        return number to putPart(upload, number) { out -> out.write(bytes, 0, bytes.size) }.eTag
    }

    @Test
    fun `a completion of one part is a completion, not a walk off the front of the list`() =
        runTest {
            // The order check compares each part with the one before it, so the walk has to start
            // at the second. Starting at the first asks what came before it, and there is nothing
            // there — a single-part upload is the most ordinary multipart upload there is.
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("one.bin"), Metadata())
                val only = store.part(upload.id, 1, 7)

                val stored = store.completeUpload(upload.id, listOf(only))

                assertEquals(7L, stored.size)
            }
        }

    @Test
    fun `parts out of order are refused by that name`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("two.bin"), Metadata())
                val first = store.part(upload.id, 1, MINIMUM_PART)
                val second = store.part(upload.id, 2, 4)

                val refused =
                    assertFailsWith<ObjectStore.CompletionRefused> {
                        store.completeUpload(upload.id, listOf(second, first))
                    }

                assertEquals(ObjectStore.CompletionRefused.Reason.INVALID_PART_ORDER, refused.reason)

                // And the same two the right way round complete, or the check above would pass for
                // a store that refuses everything.
                assertEquals(MINIMUM_PART + 4L, store.completeUpload(upload.id, listOf(first, second)).size)
            }
        }

    @Test
    fun `the same part number twice is in order, and names one part`() =
        runTest {
            // Equal is not out of order — the check is about descending, not about repeats — so a
            // completion naming part one twice is accepted. What it is **not** is that part joined
            // to itself: each number is resolved to the one part that carries it, so the object is
            // five mebibytes rather than ten.
            //
            // Written down because the comparison's boundary is exactly here. A check that refused
            // equality would refuse nothing any client sends, and nothing else in the suite would
            // notice it had started refusing.
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("dup.bin"), Metadata())
                val one = store.part(upload.id, 1, MINIMUM_PART)

                val stored = store.completeUpload(upload.id, listOf(one, one))

                assertEquals(MINIMUM_PART.toLong(), stored.size, "one part named twice is one part")
            }
        }

    @Test
    fun `a completion with no parts is refused before anything is written`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("none.bin"), Metadata())

                val refused =
                    assertFailsWith<ObjectStore.CompletionRefused> { store.completeUpload(upload.id, emptyList()) }

                assertEquals(ObjectStore.CompletionRefused.Reason.NO_PARTS, refused.reason)
                assertEquals(0, store.list("photos", maxKeys = 10).keys.size, "a refused completion writes nothing")
            }
        }

    @Test
    fun `a part that is not the last and not big enough is refused, and the last one is exempt`() =
        runTest {
            // The bound is S3's and it is the reason a three-byte part in the middle cannot be
            // joined: every part but the last has to be at least five mebibytes. The last one is
            // exempt because it is the tail of the object, and that exemption is what makes the
            // comparison a comparison rather than a blanket refusal.
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("small.bin"), Metadata())
                val tiny = store.part(upload.id, 1, 3)
                val tail = store.part(upload.id, 2, 4)

                val refused =
                    assertFailsWith<ObjectStore.CompletionRefused> {
                        store.completeUpload(upload.id, listOf(tiny, tail))
                    }
                assertEquals(ObjectStore.CompletionRefused.Reason.ENTITY_TOO_SMALL, refused.reason)

                // The same tiny part alone completes: as the last one it has no floor to meet.
                assertEquals(3L, store.completeUpload(upload.id, listOf(tiny)).size)
            }
        }

    @Test
    fun `exactly the minimum is enough, and one byte less is not`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")

                val exact = store.createUpload("photos", ObjectKey.of("exact.bin"), Metadata())
                val atFloor = store.part(exact.id, 1, MINIMUM_PART)
                val exactTail = store.part(exact.id, 2, 1)
                assertEquals(MINIMUM_PART + 1L, store.completeUpload(exact.id, listOf(atFloor, exactTail)).size)

                val under = store.createUpload("photos", ObjectKey.of("under.bin"), Metadata())
                val below = store.part(under.id, 1, MINIMUM_PART - 1)
                val underTail = store.part(under.id, 2, 1)
                val refused =
                    assertFailsWith<ObjectStore.CompletionRefused> {
                        store.completeUpload(under.id, listOf(below, underTail))
                    }
                assertEquals(ObjectStore.CompletionRefused.Reason.ENTITY_TOO_SMALL, refused.reason)
            }
        }

    @Test
    fun `a part the upload never had is refused by name`() =
        runTest {
            open().use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("missing.bin"), Metadata())
                val one = store.part(upload.id, 1, 4)

                val refused =
                    assertFailsWith<ObjectStore.CompletionRefused> {
                        store.completeUpload(upload.id, listOf(one, 2 to "\"nothing\""))
                    }

                assertEquals(ObjectStore.CompletionRefused.Reason.INVALID_PART, refused.reason)
            }
        }

    private companion object {
        const val MINIMUM_PART = 5 * 1024 * 1024
    }
}
