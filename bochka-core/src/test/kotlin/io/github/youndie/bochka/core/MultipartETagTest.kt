package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `ETag` a completed multipart upload gets, against a value computed somewhere else (M-246).
 *
 * `"<md5 of the parts' md5s>-<count>"` is not a hash of the object, and a client that compares it
 * with one of its own over the whole file is *supposed* to find they disagree — the suffix is there
 * to say so. Which means the value has to be right in a way nothing about the object's bytes can
 * confirm: only the same construction, performed independently, can.
 *
 * So the expectations below come from Python's `hashlib` rather than from this code. Four mutations
 * of `unhex` and one of the digest itself survived the whole suite before this file existed, and
 * they survived for the reason such things usually do: every test compared an ETag with the ETag
 * the server had just produced.
 */
class MultipartETagTest {
    private val dir: Path = Files.createTempDirectory("bochka-etag")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `the composite etag is the md5 of the parts' md5s, and it says how many`() =
        runTest {
            // Every part but the last has to be at least five mebibytes — the store enforces S3's
            // minimum, and it refused the first version of this test, which used three short ones.
            //
            // hashlib: md5(b"a" * 5242880) = 79b281060d337b9b2b84ccf390adcf74,
            //          md5(b"b" * 5242880) = 74843a3ab193a389bced899402d99d5f,
            //          md5(b"!") = 9033e0e305f247c0c3c80d0c7848c8b3,
            //          md5(those three digests, raw and concatenated) = 4f513f9d462beb43ff01dcad9cfe2e04
            val expected = "\"4f513f9d462beb43ff01dcad9cfe2e04-3\""
            val bodies = listOf("a".repeat(MINIMUM_PART), "b".repeat(MINIMUM_PART), "!")

            ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("big.bin"), Metadata())

                val parts =
                    bodies.mapIndexed { at, text ->
                        val bytes = text.toByteArray()
                        val part = store.putPart(upload.id, at + 1) { out -> out.write(bytes, 0, bytes.size) }
                        (at + 1) to part.eTag
                    }

                // Each part's own ETag is an ordinary MD5, and it has to be: the composite is built
                // out of these, so a wrong part ETag is a wrong object ETag with nothing to say so.
                assertEquals("\"79b281060d337b9b2b84ccf390adcf74\"", parts[0].second)
                assertEquals("\"74843a3ab193a389bced899402d99d5f\"", parts[1].second)
                assertEquals("\"9033e0e305f247c0c3c80d0c7848c8b3\"", parts[2].second)

                val stored = store.completeUpload(upload.id, parts)
                assertEquals(expected, stored.eTag)
                assertEquals(
                    bodies.sumOf { it.length }.toLong(),
                    stored.size,
                    "the object is the parts joined, and the size says so",
                )
            }
        }

    @Test
    fun `one part still gets the suffix, because the shape is the promise`() =
        runTest {
            // hashlib: md5(md5(b"hello").digest()) = 62109206880d38a4010a98e11243924a
            ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("small.bin"), Metadata())
                val part = store.putPart(upload.id, 1) { out -> out.write("hello".toByteArray(), 0, 5) }

                val stored = store.completeUpload(upload.id, listOf(1 to part.eTag))

                // Not `5d41402a…`, which is what the object's own MD5 would be. A client that reads
                // this and computes MD5 over the file has to find a disagreement, or the suffix is
                // a lie about how the object was made.
                assertEquals("\"62109206880d38a4010a98e11243924a-1\"", stored.eTag)
                assertTrue(stored.eTag.endsWith("-1\""), stored.eTag)
            }
        }

    @Test
    fun `a hex digit that is not a digit is still read as its value`() =
        runTest {
            // `unhex` reads a part's ETag back into bytes, and the letters `a` to `f` are half of
            // every digest. A version of it that handled only `0`-`9` would be right about roughly
            // one composite in a million and wrong about the rest — and no test comparing the
            // server's answer with the server's answer could tell.
            //
            // hashlib: md5(b"\xff" * 5242880) = fbce70f468befb6661cf436c839bf40e,
            //          md5(b"\x00") = 93b885adfe0da089cdf634904fd59f71,
            //          md5(those two digests) = 62282263e05cfcdfc956a7e5495d5025
            ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
                store.createBucket("photos")
                val upload = store.createUpload("photos", ObjectKey.of("hex.bin"), Metadata())
                val head = ByteArray(MINIMUM_PART) { 0xFF.toByte() }
                val first = store.putPart(upload.id, 1) { out -> out.write(head, 0, head.size) }
                val second = store.putPart(upload.id, 2) { out -> out.write(byteArrayOf(0), 0, 1) }

                assertEquals("\"fbce70f468befb6661cf436c839bf40e\"", first.eTag)
                assertEquals("\"93b885adfe0da089cdf634904fd59f71\"", second.eTag)

                val stored = store.completeUpload(upload.id, listOf(1 to first.eTag, 2 to second.eTag))
                assertEquals("\"62282263e05cfcdfc956a7e5495d5025-2\"", stored.eTag)
            }
        }

    private companion object {
        /** S3's minimum for every part but the last, and this store enforces it. */
        const val MINIMUM_PART = 5 * 1024 * 1024
    }
}
