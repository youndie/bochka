package io.github.youndie.bochka.core

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32C

/**
 * An append-only log of records, framed so that a crash leaves a boundary rather than a lie.
 *
 * ```
 * [u32 length][u32 crc32c][payload …]
 * ```
 *
 * The length is at the front of the record and is written **last**. That inversion is the whole
 * design, carried over from the neighbouring broker: the payload goes to `position + 8` first,
 * which extends the file and leaves the header as a hole that reads back as zeros, and only then
 * is the header written. A process killed between the two leaves a zero length — which recovery
 * reads as "the log ends here" — instead of a plausible header in front of nothing.
 *
 * The checksum covers the payload and is verified on every record read during recovery. It catches
 * the other half: a header that arrived while its payload did not, and a payload that was torn in
 * the middle by a page that never made it.
 *
 * `CRC32C` and not `CRC32`: the JDK compiles it to one instruction on any processor made this
 * century, and this runs over every byte of the index.
 */
class RecordLog(
    private val path: Path,
) : Closeable {
    private val channel: FileChannel =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )

    private var end: Long = 0

    /** Bytes of log that recovery accepted; where the next record goes. */
    val sizeBytes: Long get() = end

    /**
     * Reads what survived and hands each payload over, then truncates to the boundary it found.
     *
     * Truncating is not tidiness. Without it the next append writes over the first bytes of a
     * half-record and leaves the rest of it behind, so a later recovery can find a valid header
     * followed by somebody else's bytes — the exact situation the framing exists to prevent.
     */
    fun recover(consume: (ByteArray) -> Unit): Recovery {
        val size = channel.size()
        val window = Window(channel, size)
        var position = 0L
        var records = 0L
        var stopped = Stop.CLEAN

        while (true) {
            if (!window.holds(position, HEADER_BYTES)) {
                stopped = if (position == size) Stop.CLEAN else Stop.TRUNCATED_HEADER
                break
            }
            val length = window.int(position)
            val checksum = window.int(position + 4)

            if (length == 0) {
                // The hole a killed writer leaves between the payload and the header that would
                // have described it. Everything before this point is whole.
                stopped = if (position == size) Stop.CLEAN else Stop.TORN_WRITE
                break
            }
            if (length < 0 || !window.holds(position, HEADER_BYTES + length)) {
                stopped = Stop.TRUNCATED_PAYLOAD
                break
            }

            val payload = window.bytes(position + HEADER_BYTES, length)
            if (crc32c(payload) != checksum) {
                stopped = Stop.CHECKSUM
                break
            }

            consume(payload)
            records++
            position += HEADER_BYTES + length
        }

        end = position
        // Not tidiness: without it the next append writes over the first bytes of a half-record and
        // leaves the rest behind, so a later recovery can find a valid header followed by somebody
        // else's payload — the one situation this framing exists to prevent.
        channel.truncate(position)
        return Recovery(records, position, size, stopped)
    }

    fun append(payload: ByteArray): Long {
        require(payload.isNotEmpty()) { "a zero-length record would be indistinguishable from the end of the log" }
        val at = end

        // Body first: this extends the file past the header, which stays a hole reading as zeros.
        writeFully(ByteBuffer.wrap(payload), at + HEADER_BYTES)

        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        header.putInt(payload.size)
        header.putInt(crc32c(payload))
        header.flip()
        // Length last: until this lands, recovery sees zero and stops before the record.
        writeFully(header, at)

        end = at + HEADER_BYTES + payload.size
        return at
    }

    /** `fsync`. What turns "the kernel has it" into "the disk has it". */
    fun force() = channel.force(false)

    override fun close() = channel.close()

    private fun writeFully(
        buffer: ByteBuffer,
        position: Long,
    ) {
        var at = position
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at)
        }
    }

    /**
     * A megabyte of the log at a time, re-read from the start of a record that spans the edge.
     *
     * Buffering is not an optimisation here, it is the difference between a start and an outage:
     * the neighbouring broker measured one `read` per record at 174 MiB/s against 4900 MiB/s
     * buffered — ten minutes against twenty seconds on a hundred gigabytes. Re-reading at a
     * boundary costs one extra read per megabyte, which is nothing next to that.
     */
    private class Window(
        private val channel: FileChannel,
        private val size: Long,
    ) {
        private var buffer = ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.BIG_ENDIAN)
        private var start = -1L
        private var length = 0

        fun holds(
            at: Long,
            need: Int,
        ): Boolean {
            if (at + need > size) return false
            if (start >= 0 && at >= start && at + need <= start + length) return true
            if (need > buffer.capacity()) {
                buffer = ByteBuffer.allocate(need).order(ByteOrder.BIG_ENDIAN)
            }
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), size - at).toInt())
            var position = at
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) break
                position += read
            }
            length = buffer.position()
            start = at
            return length >= need
        }

        fun int(at: Long): Int = buffer.getInt((at - start).toInt())

        fun bytes(
            at: Long,
            count: Int,
        ): ByteArray {
            val out = ByteArray(count)
            buffer.position((at - start).toInt())
            buffer.get(out)
            return out
        }
    }

    /** Why recovery stopped. Everything but [Stop.CLEAN] means the tail of the log was lost. */
    enum class Stop {
        CLEAN,
        TORN_WRITE,
        TRUNCATED_HEADER,
        TRUNCATED_PAYLOAD,
        CHECKSUM,
    }

    data class Recovery(
        val records: Long,
        val acceptedBytes: Long,
        val fileBytes: Long,
        val stoppedBy: Stop,
    ) {
        val discardedBytes: Long get() = fileBytes - acceptedBytes
    }

    companion object {
        const val HEADER_BYTES = 8
        private const val BUFFER_BYTES = 1024 * 1024

        fun crc32c(payload: ByteArray): Int {
            val crc = CRC32C()
            crc.update(payload)
            return crc.value.toInt()
        }
    }
}
