package io.github.youndie.bochka.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The order object keys are listed in, checked against an implementation that is not ours.
 *
 * Source: `docs/spec/key-order/vectors.txt`, produced by `docs/spec/key-order/generate.py` from
 * Python's `sorted()` over `bytes`. The rule itself is `docs/spec/s3-service-2.json`,
 * `ListObjectsV2`, section "Sorting order of returned objects".
 *
 * Agreeing with a green test of our own writing would only prove the comparator repeats whatever
 * this file assumes. Agreeing with Python proves the reading of the rule.
 */
class ObjectKeyOrderTest {
    private val specDir: Path =
        Path.of(
            System.getProperty("bochka.specDir")
                ?: error("bochka.specDir is not set; the build has to point tests at docs/spec"),
        )

    private fun vectors(): List<ObjectKey> {
        val lines = Files.readAllLines(specDir.resolve("key-order/vectors.txt"))
        return lines
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { hex -> ObjectKey(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }
    }

    @Test
    fun `sorting keys agrees with python sorted over bytes`() {
        val expected = vectors()
        assertTrue(expected.size > 500, "the vector file looks truncated: ${expected.size} keys")

        // Shuffled with a fixed seed: a list that is already in order would pass with a comparator
        // that returns 0 for everything.
        val actual = expected.shuffled(Random(20260817)).sorted()

        assertEquals(
            expected.map { it.toByteArray().toHex() },
            actual.map { it.toByteArray().toHex() },
            "sorted order disagrees with docs/spec/key-order/vectors.txt",
        )
    }

    @Test
    fun `the order is not the one string comparison gives`() {
        // U+FF01 is a single UTF-16 unit, U+1F600 is a surrogate pair, and the pair is numerically
        // lower — so String puts the emoji first while UTF-8 bytes put it last. This test is here
        // to be read as much as to be run: it is the reason ObjectKey exists (research, §1.5).
        val fullWidth = "！"
        val emoji = "😀"

        assertTrue(fullWidth > emoji, "precondition: String order puts the emoji first")
        assertTrue(
            ObjectKey.of(fullWidth) < ObjectKey.of(emoji),
            "key order must be UTF-8 byte order, which is the opposite here",
        )
    }

    @Test
    fun `bytes above 0x7f sort after ascii, not before it`() {
        // The signed-byte trap: a loop comparing Kotlin `Byte` values puts 0x80..0xFF first,
        // because they are negative. Every non-ASCII key would jump to the head of the listing.
        assertTrue(ObjectKey.of("a") < ObjectKey(byteArrayOf(0x80.toByte())))
        assertTrue(ObjectKey(byteArrayOf(0x80.toByte())) < ObjectKey(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `a key that is a prefix of another sorts before it`() {
        // This is also the rule CommonPrefixes grouping will lean on, so it is worth pinning here.
        assertTrue(ObjectKey.of("a") < ObjectKey.of("ab"))
        assertTrue(ObjectKey.of("a/") < ObjectKey.of("a/b"))
        assertTrue(ObjectKey.of("a/b") < ObjectKey.of("a0"), "'/' is 0x2F and '0' is 0x30")
    }

    @Test
    fun `keys are equal by content and usable as map keys`() {
        val one = ObjectKey.of("photos/2026/summer.jpg")
        val same = ObjectKey("photos/2026/summer.jpg".toByteArray())

        assertEquals(one, same)
        assertEquals(one.hashCode(), same.hashCode())
        assertEquals(1, setOf(one, same).size)
    }

    @Test
    fun `keys differing only in case or in unicode normalisation are different keys`() {
        // Both pairs are one file on a Mac (research, §1.3), which is why the name on disk is never
        // derived from the key. Here they simply have to stay two keys.
        assertNotEquals(ObjectKey.of("Photo.JPG"), ObjectKey.of("photo.jpg"))
        assertNotEquals(ObjectKey.of("café.txt"), ObjectKey.of("café.txt"))
    }

    @Test
    fun `a key is not required to be valid utf-8`() {
        // 0xC3 0x28 is an invalid sequence; S3 accepts such keys, so the type must carry them
        // unchanged rather than replace them on the way in.
        val raw = byteArrayOf(0xC3.toByte(), 0x28)
        assertEquals(raw.toList(), ObjectKey(raw).toByteArray().toList())
    }

    @Test
    fun `a key cannot be changed after it is made`() {
        val bytes = "key".toByteArray()
        val key = ObjectKey(bytes)
        bytes[0] = 'X'.code.toByte()

        assertEquals("key", key.toString())
        key.toByteArray()[0] = 'Y'.code.toByte()
        assertEquals("key", key.toString())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
