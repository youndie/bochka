package io.github.youndie.bochka.s3

/**
 * The checksum of `a + b` from the checksums of `a` and `b`, without seeing either.
 *
 * This exists because of one word in `s3-service-2.json`: `ChecksumType` has two values, and
 * `FULL_OBJECT` means the checksum of a multipart object is the checksum of **its bytes** rather
 * than of its parts' checksums. S3 offers it for the CRCs and not for the digests, and the reason
 * is arithmetic rather than policy — a CRC is linear over GF(2), so two of them compose, and SHA-256
 * does not.
 *
 * The alternative was to read the assembled object back and hash it. That would undo the one
 * measured property of the assembly path: parts are joined with `transferFrom`, which is the single
 * place in this server where the kernel copies without the bytes passing through the heap (§1.6.2).
 * Hashing them would put every byte back on the heap to answer a header. This is the same answer
 * for the cost of sixty-four squarings of a small matrix per part.
 *
 * ### How
 *
 * Feeding a byte to a CRC register is a linear map, so feeding *n* zero bytes is that map applied
 * *n* times, and a linear map over GF(2) is a matrix of bits. `combine` raises the one-zero-bit
 * matrix to the power `8 * length` by repeated squaring, applies it to the first CRC, and XORs the
 * second in — which is zlib's `crc32_combine`, generalised to a width and a polynomial because
 * three of the five algorithms here need it.
 *
 * ### What it assumes
 *
 * A **reflected** CRC whose initial value and final xor are both all ones. That is true of CRC-32,
 * CRC-32C and CRC-64/NVME, which is every CRC S3 names; it is what makes the two inversions cancel
 * so that finished checksums can be combined directly rather than raw registers. An algorithm
 * without that property would need a different derivation, so [of] takes the polynomial rather than
 * pretending to be general.
 */
internal class CrcCombine(
    /** The polynomial in reversed form — the one a right-shifting register uses. */
    private val reversedPolynomial: Long,
    /** 32 or 64. Everything is carried in a `Long`; this says how much of it is the register. */
    private val width: Int,
) {
    private val mask: Long = if (width == 64) -1L else (1L shl width) - 1

    /**
     * The checksum of the concatenation, given the checksum of the tail and how long the tail is.
     *
     * Associative, so a list of parts folds left through it.
     */
    fun combine(
        head: Long,
        tail: Long,
        tailLength: Long,
    ): Long {
        if (tailLength == 0L) return head

        // `odd` is the operator for one zero **bit**; squaring it doubles the number of bits it
        // stands for, so the two buffers alternate as the exponent is walked bit by bit.
        val odd = LongArray(width)
        val even = LongArray(width)
        odd[0] = reversedPolynomial
        var row = 1L
        for (n in 1 until width) {
            odd[n] = row
            row = row shl 1
        }

        square(even, odd)
        square(odd, even)

        var length = tailLength
        var value = head
        while (true) {
            square(even, odd)
            if (length and 1L != 0L) value = apply(even, value)
            length = length ushr 1
            if (length == 0L) break
            square(odd, even)
            if (length and 1L != 0L) value = apply(odd, value)
            length = length ushr 1
            if (length == 0L) break
        }
        return (value xor tail) and mask
    }

    private fun apply(
        matrix: LongArray,
        vector: Long,
    ): Long {
        var sum = 0L
        var remaining = vector and mask
        var index = 0
        while (remaining != 0L && index < width) {
            if (remaining and 1L != 0L) sum = sum xor matrix[index]
            remaining = remaining ushr 1
            index++
        }
        return sum and mask
    }

    private fun square(
        into: LongArray,
        matrix: LongArray,
    ) {
        for (n in 0 until width) into[n] = apply(matrix, matrix[n])
    }

    companion object {
        /**
         * The combiner for an algorithm, or `null` for one that has none.
         *
         * `null` is the honest answer for SHA-1 and SHA-256 rather than an omission: a digest of a
         * concatenation cannot be computed from the digests of the pieces, which is exactly why S3
         * has a `COMPOSITE` type at all.
         */
        fun of(algorithm: PayloadChecksums.Algorithm): CrcCombine? =
            when (algorithm) {
                // Reversed CRC-32 (`java.util.zip.CRC32`) and CRC-32C (`CRC32C`), taken from the
                // same catalogue entries those classes implement.
                PayloadChecksums.Algorithm.CRC32 -> CrcCombine(0xEDB88320L, 32)

                PayloadChecksums.Algorithm.CRC32C -> CrcCombine(0x82F63B78L, 32)

                PayloadChecksums.Algorithm.CRC64NVME -> CrcCombine(-0x65936CD653B4364B, 64)

                PayloadChecksums.Algorithm.SHA1, PayloadChecksums.Algorithm.SHA256 -> null
            }
    }
}
