package io.github.youndie.bochka.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a store says when its journal was written by a version that knew more than it does (M-222).
 *
 * Measured before it was fixed: an older release meeting a record kind from M27 answered
 * `IllegalArgumentException: unknown index record kind 26` and six lines of stack, and `kubectl
 * logs --tail` showed the bottom of that stack rather than the reason. It is the one message read
 * during a rollback, by somebody in a hurry, and it said neither that the store was newer, nor
 * that the data was untouched, nor what to do.
 *
 * **The record's checksum is what makes the diagnosis honest.** Recovery verifies CRC32C before it
 * decodes anything (`RecordLog.recover`), so a kind nobody recognises cannot be a flipped bit — a
 * damaged payload fails the checksum and stops recovery as `CHECKSUM`. Reaching the decoder with a
 * kind from the future means the record is intact and was written by something that knew it.
 */
class JournalFromNewerVersionTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("bochka-newer")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    private fun sha(path: Path) =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    /** A record this build cannot know: a kind well past anything it defines, framed correctly. */
    private fun appendRecordFromTheFuture(dir: Path) {
        RecordLog(dir.resolve("index.log")).use { log ->
            log.recover { }
            log.append(byteArrayOf(99, 1, 2, 3))
            log.force()
        }
    }

    @Test
    fun `a journal written by a newer version is refused, and says so`() {
        withDir { dir ->
            ObjectStore(dir).use { it.createBucket("photos") }
            appendRecordFromTheFuture(dir)

            val refused = assertFailsWith<ObjectStore.JournalFromNewerVersion> { ObjectStore(dir) }
            val message = refused.message ?: ""

            assertEquals(99, refused.kind, "the message has to name the record it could not read")
            // The three things somebody rolling back needs, and none of them are in a stack trace.
            assertTrue("newer" in message, "it has to say the store is from a newer version: $message")
            assertTrue("checksum" in message, "and why that is known rather than guessed: $message")
            assertTrue("intact" in message || "not damaged" in message, "and that the data is fine: $message")
        }
    }

    @Test
    fun `the refusal leaves the journal exactly as it found it`() {
        withDir { dir ->
            ObjectStore(dir).use { it.createBucket("photos") }
            appendRecordFromTheFuture(dir)
            val path = dir.resolve("index.log")
            val before = Files.size(path) to sha(path)

            assertFailsWith<ObjectStore.JournalFromNewerVersion> { ObjectStore(dir) }

            // The claim the message makes is that nothing was lost, and recovery truncates the log
            // to the last whole record it read. If the refusal happened after that, the message
            // would be a lie told with confidence.
            assertEquals(before, Files.size(path) to sha(path), "the refusal must not touch the journal")
        }
    }
}
