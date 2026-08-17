package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CRC-64/NVME against its published check value.
 *
 * A CRC has four parameters that can each be wrong in a way that still produces eight plausible
 * bytes — the polynomial, whether it is written reversed, whether the register shifts left or
 * right, and the initial and final xor. The catalogue publishes the checksum of `123456789` for
 * exactly that reason, and it is the one number here that no implementation in this repository
 * produced.
 */
class Crc64NvmeTest {
    private fun of(data: ByteArray): Long = Crc64Nvme().also { it.update(data, 0, data.size) }.value

    @Test
    fun `the published check value`() {
        assertEquals(-0x5174EB79F5866778, of("123456789".toByteArray()))
    }

    @Test
    fun `nothing checksums to nothing`() {
        assertEquals(0L, of(ByteArray(0)))
    }

    @Test
    fun `feeding one byte at a time gives the same answer`() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val whole = of(data)
        val piecemeal = Crc64Nvme().also { crc -> data.forEach { crc.update(it.toInt()) } }.value
        assertEquals(whole, piecemeal)
    }
}
