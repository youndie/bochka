package io.github.youndie.bochka.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordLogTest {
    private val dir: Path = Files.createTempDirectory("bochka-log")
    private val file: Path get() = dir.resolve("index.log")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun readAll(): Pair<List<ByteArray>, RecordLog.Recovery> {
        val records = ArrayList<ByteArray>()
        RecordLog(file).use { log ->
            val recovery = log.recover { records.add(it) }
            return records to recovery
        }
    }

    @Test
    fun `records come back in the order they were written`() {
        RecordLog(file).use { log ->
            log.recover { }
            repeat(1000) { log.append("record-$it".toByteArray()) }
            log.force()
        }

        val (records, recovery) = readAll()

        assertEquals(1000, records.size)
        assertEquals(RecordLog.Stop.CLEAN, recovery.stoppedBy)
        assertEquals(0, recovery.discardedBytes)
        assertContentEquals("record-0".toByteArray(), records.first())
        assertContentEquals("record-999".toByteArray(), records.last())
    }

    @Test
    fun `a record larger than the read buffer survives`() {
        // The window is a megabyte; a record bigger than it takes the path that allocates for the
        // record instead of the window, and that path is only ever exercised here.
        val big = Random(1).nextBytes(3 * 1024 * 1024)
        RecordLog(file).use { log ->
            log.recover { }
            log.append("small".toByteArray())
            log.append(big)
            log.append("after".toByteArray())
        }

        val (records, _) = readAll()

        assertEquals(3, records.size)
        assertContentEquals(big, records[1])
    }

    @Test
    fun `a header that never arrived ends the log where the record began`() {
        // Exactly what a killed writer leaves: the payload extended the file, the length never
        // landed, so the header reads back as the zeros the filesystem put there.
        RecordLog(file).use { log ->
            log.recover { }
            log.append("first".toByteArray())
            log.append("second".toByteArray())
        }
        val goodBytes = Files.size(file)

        // Append a payload without its header, by hand, the way a crash between the two writes does.
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            channel.write(ByteBuffer.wrap("orphaned payload".toByteArray()), goodBytes + RecordLog.HEADER_BYTES)
        }

        val (records, recovery) = readAll()

        assertEquals(2, records.size, "the record without a header must not be reported")
        assertEquals(RecordLog.Stop.TORN_WRITE, recovery.stoppedBy)
        assertEquals(goodBytes, recovery.acceptedBytes)
        assertTrue(recovery.discardedBytes > 0)
    }

    @Test
    fun `a payload torn in the middle is refused by its checksum`() {
        RecordLog(file).use { log ->
            log.recover { }
            log.append("first".toByteArray())
            log.append("second record, long enough to damage".toByteArray())
        }
        val afterFirst = RecordLog.HEADER_BYTES + "first".toByteArray().size

        // One byte of the second record's payload, as a page that never made it would leave it.
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            channel.write(ByteBuffer.wrap(byteArrayOf('X'.code.toByte())), afterFirst + RecordLog.HEADER_BYTES + 3L)
        }

        val (records, recovery) = readAll()

        assertEquals(1, records.size, "a record whose bytes changed must not be reported")
        assertEquals(RecordLog.Stop.CHECKSUM, recovery.stoppedBy)
    }

    @Test
    fun `a truncated tail ends the log cleanly`() {
        RecordLog(file).use { log ->
            log.recover { }
            repeat(10) { log.append("record-$it".toByteArray()) }
        }
        val size = Files.size(file)
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(size - 3) }

        val (records, recovery) = readAll()

        assertEquals(9, records.size)
        assertEquals(RecordLog.Stop.TRUNCATED_PAYLOAD, recovery.stoppedBy)
    }

    @Test
    fun `recovery truncates so the next append starts on the boundary it chose`() {
        // Without the truncate, this append would write over the head of the damaged record and
        // leave its tail behind — and a later recovery would find a valid header followed by
        // somebody else's bytes.
        RecordLog(file).use { log ->
            log.recover { }
            log.append("kept".toByteArray())
        }
        val goodBytes = Files.size(file)
        FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
            channel.write(ByteBuffer.wrap("junk that never got a header".toByteArray()), goodBytes + 8)
        }

        RecordLog(file).use { log ->
            val recovery = log.recover { }
            assertEquals(goodBytes, recovery.acceptedBytes)
            log.append("after".toByteArray())
        }

        val (records, recovery) = readAll()

        assertEquals(2, records.size)
        assertEquals(RecordLog.Stop.CLEAN, recovery.stoppedBy)
        assertContentEquals("after".toByteArray(), records[1])
    }

    @Test
    fun `an empty log is not an error`() {
        val (records, recovery) = readAll()

        assertEquals(0, records.size)
        assertEquals(RecordLog.Stop.CLEAN, recovery.stoppedBy)
    }

    @Test
    fun `records spanning the read window come back whole`() {
        // Records of an awkward size, enough of them to cross the megabyte boundary many times.
        val payloads = (0 until 5000).map { Random(it).nextBytes(200 + it % 800) }
        RecordLog(file).use { log ->
            log.recover { }
            payloads.forEach { log.append(it) }
        }

        val (records, recovery) = readAll()

        assertEquals(RecordLog.Stop.CLEAN, recovery.stoppedBy)
        assertEquals(payloads.size, records.size)
        for (i in payloads.indices) {
            assertContentEquals(payloads[i], records[i], "record $i")
        }
    }
}
