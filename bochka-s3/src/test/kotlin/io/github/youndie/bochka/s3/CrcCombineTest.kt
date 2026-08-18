package io.github.youndie.bochka.s3

import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Checksum
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The combiner is checked against the bytes themselves, which is the only oracle worth having here.
 *
 * A combine that agreed with a second implementation of the same idea would prove the idea was
 * copied twice, not that it is right. So every case here runs the algorithm over the concatenation
 * — the thing a client will do when it verifies the object it downloaded — and requires the
 * combined value to equal it.
 */
class CrcCombineTest {
    private fun checksumOf(
        algorithm: PayloadChecksums.Algorithm,
        bytes: ByteArray,
    ): Long {
        val running: Checksum =
            when (algorithm) {
                PayloadChecksums.Algorithm.CRC32 -> CRC32()
                PayloadChecksums.Algorithm.CRC32C -> CRC32C()
                PayloadChecksums.Algorithm.CRC64NVME -> Crc64Nvme()
                else -> error("$algorithm is not a CRC")
            }
        running.update(bytes, 0, bytes.size)
        return running.value
    }

    private val crcs =
        listOf(
            PayloadChecksums.Algorithm.CRC32,
            PayloadChecksums.Algorithm.CRC32C,
            PayloadChecksums.Algorithm.CRC64NVME,
        )

    @Test
    fun `two pieces combine into the checksum of the whole`() {
        val head = "the first part of it".toByteArray()
        val tail = "and everything after".toByteArray()

        for (algorithm in crcs) {
            val combined =
                CrcCombine.of(algorithm)!!.combine(
                    checksumOf(algorithm, head),
                    checksumOf(algorithm, tail),
                    tail.size.toLong(),
                )
            assertEquals(checksumOf(algorithm, head + tail), combined, "$algorithm over two pieces")
        }
    }

    @Test
    fun `a list of parts folds left through it`() {
        // The shape the object actually has: the parts arrive one at a time and the object is what
        // they concatenate to, so the combiner is used as a fold rather than pairwise.
        val random = Random(20260818)
        val parts = List(7) { ByteArray(1 + random.nextInt(5000)).also(random::nextBytes) }

        for (algorithm in crcs) {
            val combiner = CrcCombine.of(algorithm)!!
            var running = checksumOf(algorithm, parts.first())
            for (part in parts.drop(1)) {
                running = combiner.combine(running, checksumOf(algorithm, part), part.size.toLong())
            }
            assertEquals(checksumOf(algorithm, parts.reduce(ByteArray::plus)), running, "$algorithm folded")
        }
    }

    @Test
    fun `a part of the size a real one has combines correctly`() {
        // Five mebibytes is the floor S3 puts on every part but the last, so it is the length the
        // exponent is actually raised to in practice — forty million bits. A combiner that is
        // right for twenty bytes and wrong for this would look correct in every small test.
        val head = ByteArray(5 * 1024 * 1024) { 'A'.code.toByte() }
        val tail = ByteArray(5 * 1024 * 1024) { 'B'.code.toByte() }

        for (algorithm in crcs) {
            val combined =
                CrcCombine.of(algorithm)!!.combine(
                    checksumOf(algorithm, head),
                    checksumOf(algorithm, tail),
                    tail.size.toLong(),
                )
            assertEquals(checksumOf(algorithm, head + tail), combined, "$algorithm over five-mebibyte parts")
        }
    }

    @Test
    fun `an empty tail leaves the head alone`() {
        for (algorithm in crcs) {
            val head = checksumOf(algorithm, "something".toByteArray())
            assertEquals(head, CrcCombine.of(algorithm)!!.combine(head, checksumOf(algorithm, ByteArray(0)), 0))
        }
    }

    @Test
    fun `a digest has no combiner, and says so`() {
        // Not an omission — it is the reason `ChecksumType` has two values. A SHA-256 of a
        // concatenation cannot be had from the SHA-256s of the pieces, so those objects carry a
        // checksum of checksums and say `COMPOSITE`.
        assertNull(CrcCombine.of(PayloadChecksums.Algorithm.SHA1))
        assertNull(CrcCombine.of(PayloadChecksums.Algorithm.SHA256))
    }
}
