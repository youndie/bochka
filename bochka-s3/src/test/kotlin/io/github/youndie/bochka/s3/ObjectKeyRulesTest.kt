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
    fun `a control byte is a key and a malformed encoding is not`() {
        // This said "a key that is not valid utf-8 is still a key" and asserted `0xC3 0x28` was
        // fine. That read Р3 one step too far: the byte-string rule is about **order** — nothing
        // compares keys as text — and S3 still defines a key as characters whose UTF-8 encoding
        // fits in 1024 bytes. `ceph/s3-tests` settled it (`test_object_read_unreadable`: 400, not
        // 404), and 404 was the worse answer of the two — it says the key is merely absent.
        assertEquals(
            ObjectKeyRules.Rejection.NOT_UTF8,
            ObjectKeyRules.check(ObjectKey(byteArrayOf(0xC3.toByte(), 0x28))),
        )

        // A control byte, on the other hand, encodes a character and stays a key. It is why the
        // listing has `encoding-type=url` at all: XML cannot carry it, and the answer to that is
        // to encode the response, not to refuse the key.
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

    @Test
    fun `a key whose bytes are not UTF-8 is refused, and one that merely looks odd is not`() {
        // Р3 says the key is a byte string, and that is about its **order**: nothing here compares
        // keys as text. Its **validity** is a different question with a different answer — S3
        // defines a key as characters whose UTF-8 encoding is at most 1024 bytes, so bytes that
        // are not an encoding of anything are not a key.
        //
        // `ceph/s3-tests`, `test_object_read_unreadable`: `\xae\x8a-` is a 400, and this server
        // answered 404 — which tells the client the key is merely absent and that writing it
        // would work.
        assertEquals(
            ObjectKeyRules.Rejection.NOT_UTF8,
            ObjectKeyRules.check(ObjectKey(byteArrayOf(0xAE.toByte(), 0x8A.toByte(), '-'.code.toByte()))),
        )
        assertEquals(ObjectKeyRules.Rejection.NOT_UTF8, ObjectKeyRules.check(ObjectKey(byteArrayOf(0xC3.toByte()))))
        assertEquals(ObjectKeyRules.Rejection.NOT_UTF8, ObjectKeyRules.check(ObjectKey(byteArrayOf(0x80.toByte()))))

        // A lone surrogate encoded as three bytes: valid-looking and not valid.
        assertEquals(
            ObjectKeyRules.Rejection.NOT_UTF8,
            ObjectKeyRules.check(ObjectKey(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))),
        )

        // And the keys that must keep working, which is the whole point of the byte-string rule.
        for (key in listOf("a/b", "a//b", "..", ".", "café.txt", "😀", "\u0001control", "a b+c%2F")) {
            assertNull(ObjectKeyRules.check(ObjectKey.of(key)), key)
        }
    }
}
