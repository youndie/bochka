package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObjectKeyRulesTest {
    @Test
    fun `keys the reference server refuses are accepted here`() {
        // Every one of these is a key real S3 accepts and MinIO rejects, because MinIO lays objects
        // out under their names and a filesystem cannot hold them (research, §1.3, §1.10 of s3kn).
        // Accepting them is the visible half of deciding that a key never reaches the disk (Р2), so
        // it is worth a test that fails loudly if that decision is ever quietly walked back.
        val accepted =
            listOf(
                "a//b", // XMinioInvalidObjectName
                "a/./b", // XMinioInvalidResourceName
                "a/../b",
                "a/b", // and, below, the key that would need `a/b` to be a directory
                "a/b/c",
                "Photo.JPG",
                "photo.jpg", // same file as the line above on a case-insensitive volume
                "café.txt",
                "café.txt", // NFC and NFD: the same file on a normalising volume
                "trailing/",
                "/leading",
                "dots...",
            )

        for (key in accepted) {
            assertNull(ObjectKeyRules.check(ObjectKey.of(key)), "key '$key' should be accepted")
        }
    }

    @Test
    fun `a key that is not valid utf-8 is still a key`() {
        assertNull(ObjectKeyRules.check(ObjectKey(byteArrayOf(0xC3.toByte(), 0x28))))
        assertNull(ObjectKeyRules.check(ObjectKey(byteArrayOf(1))))
    }

    @Test
    fun `the limit is 1024 bytes and it is bytes`() {
        assertNull(ObjectKeyRules.check(ObjectKey.of("a".repeat(1024))))
        assertEquals(
            ObjectKeyRules.Rejection.TOO_LONG,
            ObjectKeyRules.check(ObjectKey.of("a".repeat(1025))),
        )

        // 256 four-byte characters: 256 characters, 1024 bytes — still fine. One more is not.
        assertNull(ObjectKeyRules.check(ObjectKey.of("😀".repeat(256))))
        assertEquals(
            ObjectKeyRules.Rejection.TOO_LONG,
            ObjectKeyRules.check(ObjectKey.of("😀".repeat(257))),
        )
    }

    @Test
    fun `an empty key is refused`() {
        assertEquals(ObjectKeyRules.Rejection.EMPTY, ObjectKeyRules.check(ObjectKey(ByteArray(0))))
    }

    @Test
    fun `rejections carry the code that goes on the wire`() {
        assertEquals("KeyTooLongError", ObjectKeyRules.Rejection.TOO_LONG.code)
        assertEquals("InvalidURI", ObjectKeyRules.Rejection.EMPTY.code)
    }
}
