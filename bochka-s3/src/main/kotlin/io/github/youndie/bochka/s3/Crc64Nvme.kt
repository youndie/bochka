package io.github.youndie.bochka.s3

import java.util.zip.Checksum

/**
 * CRC-64/NVME, the checksum `aws-cli` puts on every upload unless told otherwise.
 *
 * The parameters are the NVM Express Command Set Specification's CRC-64 (also catalogued as
 * CRC-64/Rocksoft): polynomial `0xAD93D23594C93659`, initial value all ones, input and output
 * reflected, final xor all ones. Its published check value — the checksum of the nine bytes
 * `123456789` — is `0xAE8B14860A799888`, and that is what [Crc64NvmeTest] pins.
 *
 * Written rather than refused, and that is a correction. This server refused
 * `x-amz-checksum-crc64nvme` by name for about an hour, on the reasoning that no client sends it
 * unasked; the first `aws s3 cp` disproved it. Every default upload from `aws-cli` v2 carries this
 * header, so refusing it is not a scope decision, it is being unusable by the reference client.
 *
 * The reflected form means the table is built from the **reversed** polynomial and the register
 * shifts right. Getting that backwards produces a well-formed checksum of the same length that
 * agrees with nothing, which is why the check value is a test and not a comment.
 */
class Crc64Nvme : Checksum {
    private var crc: Long = INITIAL

    override fun update(b: Int) {
        crc = TABLE[((crc xor b.toLong()) and 0xFF).toInt()] xor (crc ushr 8)
    }

    override fun update(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        var value = crc
        for (i in off until off + len) {
            value = TABLE[((value xor b[i].toLong()) and 0xFF).toInt()] xor (value ushr 8)
        }
        crc = value
    }

    override fun getValue(): Long = crc xor INITIAL

    override fun reset() {
        crc = INITIAL
    }

    private companion object {
        const val INITIAL = -1L

        /** The polynomial reversed, which is the form a right-shifting register uses. */
        const val REVERSED_POLYNOMIAL = -0x65936CD653B4364B // 0x9A6C9329AC4BC9B5

        val TABLE =
            LongArray(256) { index ->
                var value = index.toLong()
                repeat(8) {
                    value = if (value and 1L != 0L) (value ushr 1) xor REVERSED_POLYNOMIAL else value ushr 1
                }
                value
            }
    }
}
