package io.github.youndie.bochka.benchmark

import io.github.youndie.bochka.core.IndexRecord
import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.core.RecordLog
import io.github.youndie.bochka.s3.AccessControl
import io.github.youndie.bochka.s3.Lifecycle
import io.github.youndie.bochka.s3.LifecycleSweep
import io.github.youndie.bochka.s3.Lifecycles
import io.github.youndie.bochka.s3.xml.S3Documents
import io.github.youndie.bochka.s3.xml.S3Requests
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * The measurements M8 exists for, as a program rather than a JMH benchmark.
 *
 * JMH answers "how many nanoseconds does this call take"; none of these questions is that shape.
 * They are about moving gigabytes through a socket and a disk, where the interesting quantity is
 * processor time per byte over seconds, and where a harness that runs the work a million times in
 * a warm loop would measure the page cache.
 *
 * ```
 * ./gradlew :bochka-benchmark:measure -Pbochka.measure=serve
 * ```
 *
 * `BOCHKA_MEASURE_DIR` chooses where files go; it must not be a memory filesystem ([MeasurementDir]).
 */
object Measurements {
    /** How many times each variant runs; the median is kept and the spread is printed. */
    private var repeats = 3

    private const val KIB = 1024L
    private const val MIB = 1024 * KIB
    private const val GIB = 1024 * MIB

    /** Where the two-machine measurement meets. Above the ephemeral range, and not a service port. */
    private const val PORT = 9101

    /** The bucket the collector measurement fills. Named once, because the fill and the load must agree. */
    private const val GC_BUCKET = "photos"

    /**
     * How many full collections are forced per variant. Odd, so the median is a run rather than a
     * mean of two, and more than three because the first one after a fill is not like the others.
     */
    private const val FORCED_COLLECTIONS = 5

    @JvmStatic
    fun main(args: Array<String>) {
        val what = args.firstOrNull() ?: "all"
        val dir =
            MeasurementDir.of(
                Path.of(System.getenv("BOCHKA_MEASURE_DIR") ?: (System.getProperty("user.home") + "/.bochka-measure")),
            )
        val bytes = (System.getenv("BOCHKA_MEASURE_BYTES")?.toLongOrNull() ?: GIB)
        repeats = System.getenv("BOCHKA_MEASURE_REPEATS")?.toIntOrNull() ?: 3

        println("bochka measurements")
        println("  directory   $dir — ${MeasurementDir.describe(dir)}")
        println("  size        ${"%.2f".format(bytes / GIB.toDouble())} GiB per variant")
        println("  jvm         ${ManagementRuntime.arguments()}")
        println()

        try {
            when (what) {
                "serve" -> {
                    serve(dir, bytes)
                }

                "write" -> {
                    write(dir, bytes)
                }

                "assemble" -> {
                    assemble(dir, bytes)
                }

                "index" -> {
                    index(dir)
                }

                "small" -> {
                    small(dir)
                }

                "sweep" -> {
                    sweep(dir)
                }

                "readpath" -> {
                    readPath(dir)
                }

                "acl" -> {
                    accessPath(dir)
                }

                "sse" -> {
                    sse(dir, bytes)
                }

                "verify" -> {
                    verify(dir, bytes)
                }

                "gc" -> {
                    gcPauses(dir)
                }

                "ceiling" -> {
                    ceiling()
                }

                // Seven by default rather than three: this one is milliseconds rather than
                // seconds, so a run that lands on somebody else's page fault moves the median of
                // three and not the median of seven.
                "startup" -> {
                    Startup.measure(dir, System.getenv("BOCHKA_MEASURE_REPEATS")?.toIntOrNull() ?: 7)
                }

                // Two halves of one measurement that needs two machines. `serve-network` is the
                // sender and holds the numbers; `drain` is the other end of the wire and prints
                // nothing worth reading.
                "serve-network" -> {
                    serveOverNetwork(dir, bytes, args.getOrNull(1) ?: "0.0.0.0", args.getOrNull(2)?.toInt() ?: PORT)
                }

                "drain" -> {
                    drain(args.getOrNull(1) ?: "127.0.0.1", args.getOrNull(2)?.toInt() ?: PORT)
                }

                else -> {
                    serve(dir, bytes)
                    println()
                    write(dir, bytes)
                    println()
                    assemble(dir, bytes)
                    println()
                    index(dir)
                    println()
                    small(dir)
                }
            }
        } finally {
            // Guarded, and not out of tidiness: an exception thrown while cleaning up **replaces**
            // the one being cleaned up after, and the report becomes `NoSuchFileException` from
            // the walk instead of whatever actually broke the measurement. Cost me a run.
            runCatching {
                if (Files.exists(dir)) {
                    Files.walk(dir).use { walk ->
                        walk.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
                    }
                }
            }
        }
    }

    /**
     * M-61: serving an object, zero-copy against a read into the heap.
     *
     * The claim the project is built on — `transferTo` from a file to a socket costs materially
     * less processor per byte than reading into a buffer and writing it out — is checked here
     * rather than assumed. Identical bytes either way, which is precisely why it needs measuring:
     * nothing about the output says which path ran.
     */
    private fun serve(
        dir: Path,
        bytes: Long,
    ) {
        println("== M-61: serving an object ==")
        val file = fill(dir.resolve("serve.bin"), bytes)

        val zeroCopy =
            Measurement.repeated("transferTo (zero-copy)", bytes, repeats) {
                overLoopback("transferTo (zero-copy)", bytes) { socket ->
                    FileChannel.open(file, StandardOpenOption.READ).use { source ->
                        var position = 0L
                        while (position < bytes) position += source.transferTo(position, bytes - position, socket)
                    }
                }
            }
        val throughHeap =
            Measurement.repeated("read into heap, then write", bytes, repeats) {
                overLoopback("read into heap, then write", bytes) { socket ->
                    FileChannel.open(file, StandardOpenOption.READ).use { source ->
                        // The shape the JDK falls back to when the target is not a real socket
                        // channel: a heap buffer, one read and one write per pass (§1.6.3).
                        val buffer = ByteBuffer.allocate(8 * KIB.toInt())
                        while (true) {
                            buffer.clear()
                            if (source.read(buffer) < 0) break
                            buffer.flip()
                            while (buffer.hasRemaining()) socket.write(buffer)
                        }
                    }
                }
            }

        println(zeroCopy)
        println(throughHeap)
        println("  ${Measurement.compare(zeroCopy.median, throughHeap.median)}")
    }

    /**
     * M-190: what it costs to serve an object encrypted under a customer key.
     *
     * Three variants, and the third is the only new number. The first two are already measured
     * properly on the network stand (M-61: reading into the heap costs 7.6–8.0× the processor of
     * zero-copy per byte), and they stand here so the third has something to be compared against
     * **on this same machine**: loopback has no driver and no interrupts, so it understates the
     * ratio of zero-copy to the rest, and does not understate the ratio of the two user-space paths
     * to each other. That is what is being asked.
     *
     * The question this is measured for is written in the milestone as a hypothesis: the cost of an
     * encrypted read will be bounded by AES rather than by copying, and will therefore come out
     * **lower** than "eight times more expensive".
     */
    private fun sse(
        dir: Path,
        bytes: Long,
    ) {
        println("== M-190: what an encrypted object costs to serve ==")
        val file = fill(dir.resolve("sse.bin"), bytes)
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val iv = ByteArray(16) { (it * 13 + 5).toByte() }

        // **In the process and not through a socket, and that was learned the expensive way here.**
        // The first version of this sent the bytes over loopback, the way the server does, and came
        // back with all three variants inside their own noise — and with AES-256-CTR reported as
        // *cheaper* than the same path without a cipher, which cannot happen: it is the same work
        // plus a cipher. A variant that does strictly more work coming out cheaper is the signature
        // of a stand measuring itself, and the remedy is the one this repository already wrote down
        // for the lifecycle read path: ask the question where the answer is, not where the request
        // is. The socket is measured by M-61 on the network stand; what is new here is the cipher.
        //
        // The sum goes into the output on purpose. A loop whose result nobody uses is a loop the
        // JIT may delete, and that has produced a beautiful number here before (M-178: two
        // nanoseconds for two hash lookups).
        var sink = 0L

        // One discarded pass of each, and it is not tidiness: `AES/CTR` compiles down to the
        // processor's AES instructions only after the JIT has seen the loop, so the first
        // measured run is a measurement of the compiler. Without this the spread of the ciphered
        // variant came back at 4.58x — larger than the difference being asked about, which by the
        // rule of this file means no conclusion at all.
        repeat(2) {
            FileChannel.open(file, StandardOpenOption.READ).use { source ->
                val warm = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
                warm.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.IvParameterSpec(iv),
                )
                val chunk = ByteArray(64 * KIB.toInt())
                val buffer = ByteBuffer.wrap(chunk)
                while (true) {
                    buffer.clear()
                    val read = source.read(buffer)
                    if (read < 0) break
                    warm.update(chunk, 0, read, chunk, 0)
                    sink += chunk[read - 1].toLong()
                }
            }
        }

        val plain =
            Measurement.repeated("read the object, no cipher", bytes, repeats) {
                Measurement.of("read the object, no cipher", bytes) {
                    FileChannel.open(file, StandardOpenOption.READ).use { source ->
                        val chunk = ByteArray(64 * KIB.toInt())
                        val buffer = ByteBuffer.wrap(chunk)
                        while (true) {
                            buffer.clear()
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink += chunk[read - 1].toLong()
                        }
                    }
                }
            }
        val ciphered =
            Measurement.repeated("read the object, AES-256-CTR", bytes, repeats) {
                Measurement.of("read the object, AES-256-CTR", bytes) {
                    val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(key, "AES"),
                        javax.crypto.spec.IvParameterSpec(iv),
                    )
                    FileChannel.open(file, StandardOpenOption.READ).use { source ->
                        val chunk = ByteArray(64 * KIB.toInt())
                        val buffer = ByteBuffer.wrap(chunk)
                        while (true) {
                            buffer.clear()
                            val read = source.read(buffer)
                            if (read < 0) break
                            cipher.update(chunk, 0, read, chunk, 0)
                            sink += chunk[read - 1].toLong()
                        }
                    }
                }
            }

        println(plain)
        println(ciphered)
        println("  ${Measurement.compare(plain.median, ciphered.median)}")
        println("  (checksum of the reads, so that nothing above can be optimised away: $sink)")
    }

    /**
     * M-307: what it would cost to check an object's bytes while serving it.
     *
     * Nothing on the read path compares what is on disk with what the index remembers, and that is
     * not a hole somebody forgot: the fast path exists **because** the bytes never enter this
     * process (§1.6). Verifying them means giving that up, and the price of giving it up is
     * already measured — M-61 on the network stand, 7.6–8.0× the processor per byte. What is not
     * measured, and is the only new number here, is what the digest itself adds once the bytes are
     * in a buffer anyway.
     *
     * Two digests rather than one, because they are not interchangeable. `CRC32C` is what would be
     * stored deliberately for this purpose; `MD5` is what an object already carries when the client
     * asked for no checksum, since a single-part `ETag` **is** the MD5 of the content. A design
     * that verifies "for free, from what is already there" is paying the second number, not the
     * first.
     *
     * In this process and through no socket, for the reason M-190 records above: over loopback all
     * of this lands inside its own noise, and a variant doing strictly more work has come back
     * cheaper. The socket half of the question already has its answer on a stand with a real card.
     */
    private fun verify(
        dir: Path,
        bytes: Long,
    ) {
        println("== M-307: what checking the bytes on the way out would cost ==")
        val file = fill(dir.resolve("verify.bin"), bytes)
        var sink = 0L

        // Reads the whole file and throws the bytes away. Called before every variant rather than
        // once at the start, because the first version left the cheapest variant with a spread of
        // 1.92x - larger than the difference being asked about, which by this file's own rule means
        // no conclusion at all. What varies between runs is whether the page cache still holds a
        // gibibyte, and that is not a property of any variant.
        fun warm() {
            FileChannel.open(file, StandardOpenOption.READ).use { source ->
                val buffer = ByteBuffer.allocate(64 * KIB.toInt())
                while (true) {
                    buffer.clear()
                    if (source.read(buffer) < 0) break
                }
            }
        }

        // Discarded passes, for the same reason the cipher needs them: `CRC32C` and `MD5` are
        // intrinsics, and the first measured run would otherwise be a measurement of the compiler.
        repeat(2) {
            FileChannel.open(file, StandardOpenOption.READ).use { source ->
                val chunk = ByteArray(64 * KIB.toInt())
                val buffer = ByteBuffer.wrap(chunk)
                val crc = java.util.zip.CRC32C()
                val md5 = java.security.MessageDigest.getInstance("MD5")
                while (true) {
                    buffer.clear()
                    val read = source.read(buffer)
                    if (read < 0) break
                    crc.update(chunk, 0, read)
                    md5.update(chunk, 0, read)
                    sink += chunk[read - 1].toLong()
                }
                sink += crc.value + md5.digest()[0]
            }
        }

        val plain =
            Measurement.repeated("read the object, no digest", bytes, repeats) {
                warm()
                Measurement.of("read the object, no digest", bytes) {
                    FileChannel.open(file, StandardOpenOption.READ).use { source ->
                        val chunk = ByteArray(64 * KIB.toInt())
                        val buffer = ByteBuffer.wrap(chunk)
                        while (true) {
                            buffer.clear()
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink += chunk[read - 1].toLong()
                        }
                    }
                }
            }
        val crc32c =
            Measurement.repeated("read the object, CRC32C", bytes, repeats) {
                warm()
                Measurement.of("read the object, CRC32C", bytes) {
                    FileChannel.open(file, StandardOpenOption.READ).use { source ->
                        val chunk = ByteArray(64 * KIB.toInt())
                        val buffer = ByteBuffer.wrap(chunk)
                        val crc = java.util.zip.CRC32C()
                        while (true) {
                            buffer.clear()
                            val read = source.read(buffer)
                            if (read < 0) break
                            crc.update(chunk, 0, read)
                            sink += chunk[read - 1].toLong()
                        }
                        // Into the output, so that the digest cannot be optimised away as a loop
                        // whose result nobody reads.
                        sink += crc.value
                    }
                }
            }
        val md5 =
            Measurement.repeated("read the object, MD5 (the ETag)", bytes, repeats) {
                warm()
                Measurement.of("read the object, MD5 (the ETag)", bytes) {
                    FileChannel.open(file, StandardOpenOption.READ).use { source ->
                        val chunk = ByteArray(64 * KIB.toInt())
                        val buffer = ByteBuffer.wrap(chunk)
                        val digest = java.security.MessageDigest.getInstance("MD5")
                        while (true) {
                            buffer.clear()
                            val read = source.read(buffer)
                            if (read < 0) break
                            digest.update(chunk, 0, read)
                            sink += chunk[read - 1].toLong()
                        }
                        sink += digest.digest()[0]
                    }
                }
            }

        // The digests again, this time with the file taken out of the question. The three variants
        // above are a sum of two costs, and the cheaper of the two - reading a gibibyte the page
        // cache already holds - carries the whole run's noise: its spread stayed at 1.34x while
        // the digested variants settled at 1.17-1.22x, so the CRC ratio above sits exactly on the
        // noise floor and this file's own rule forbids concluding from it. Over a buffer that is
        // already in memory there is nothing left to be noisy, and the two costs can be added by
        // hand instead of measured together.
        val chunk = ByteArray(64 * KIB.toInt()) { (it * 31 + 7).toByte() }
        val passes = (bytes / chunk.size).toInt()
        val crcInMemory =
            Measurement.repeated("CRC32C over memory", bytes, repeats) {
                Measurement.of("CRC32C over memory", bytes) {
                    val crc = java.util.zip.CRC32C()
                    repeat(passes) { crc.update(chunk, 0, chunk.size) }
                    sink += crc.value
                }
            }
        val md5InMemory =
            Measurement.repeated("MD5 over memory", bytes, repeats) {
                Measurement.of("MD5 over memory", bytes) {
                    val digest = java.security.MessageDigest.getInstance("MD5")
                    repeat(passes) { digest.update(chunk, 0, chunk.size) }
                    sink += digest.digest()[0]
                }
            }

        println(plain)
        println(crc32c)
        println(md5)
        println(crcInMemory)
        println(md5InMemory)
        println("  CRC32C on top of the read: ${Measurement.compare(plain.median, crc32c.median)}")
        println("  MD5 on top of the read:    ${Measurement.compare(plain.median, md5.median)}")
        println("  the digests by themselves: ${Measurement.compare(crcInMemory.median, md5InMemory.median)}")
        println("  (checksum of the reads, so that nothing above can be optimised away: $sink)")
    }

    /**
     * Open question 2: what a small object actually costs when it is a file of its own.
     *
     * Р2 states that small objects are not optimised and names the price in prose: an inode per
     * object and the filesystem's minimum block. That is its half of the question, and it is
     * measurable exactly — with no assumptions about what people store.
     *
     * What is measured is **space occupied, not size**: `st_blocks` from `unix:blocks`, times 512.
     * The logical size of a one-byte file is one byte and says nothing; what it occupies is a whole
     * block. The difference between the two is the subject of the question.
     *
     * The objects are put through a real [ObjectStore] rather than with `Files.write`: the layout
     * across two directory levels is part of the price, and directories occupy blocks too.
     */
    private fun small(dir: Path) {
        println("== Open question 2: what a small object costs ==")
        val counts = System.getenv("BOCHKA_MEASURE_SMALL_COUNT")?.toIntOrNull() ?: 20_000

        println("  %-12s %12s %12s %12s %10s".format("size", "logical", "on disk", "overhead", "factor"))
        for (size in listOf(1, 512, 4 * KIB.toInt(), 64 * KIB.toInt())) {
            val home = Files.createDirectories(dir.resolve("small-$size"))
            ObjectStore(home, ObjectStore.Durability.NONE).use { store ->
                store.createBucket("photos")
                val payload = ByteArray(size)
                // `runBlocking`, because the write path is suspending: it is fed from a socket
                // where it is the real one. There is no socket here, and this is exactly the case
                // `runBlocking` exists for — the boundary between measuring code and what it
                // measures.
                kotlinx.coroutines.runBlocking {
                    for (i in 0 until counts) {
                        val staged = store.stage { sink -> sink.write(payload, 0, payload.size) }
                        store.commit("photos", ObjectKey.of("img-%07d.bin".format(i)), Metadata.EMPTY, staged)
                    }
                }
            }

            val logical = counts.toLong() * size
            val onDisk = allocatedBytes(home.resolve("data"))
            println(
                "  %-12s %12s %12s %12s %9.1fx".format(
                    "$size B",
                    gib(logical),
                    gib(onDisk),
                    gib(onDisk - logical),
                    onDisk.toDouble() / logical,
                ),
            )
        }

        // The ceiling is the other half of the answer, and it does not depend on the distribution
        // of sizes at all.
        val ceiling = ObjectStore.ceilingForHeap()
        println()
        println("  ceiling of this heap: $ceiling objects")
        println("  so every conceivable saving from packing is bounded above by that number,")
        println("  however many small objects a consumer stores")
    }

    /**
     * How much is **occupied**, not how much was written.
     *
     * A one-byte file has a size of one byte and occupies a whole block; question 2 is about
     * exactly that difference, and `Files.size` does not answer it. Directories are counted too —
     * the layout across two levels is part of the price.
     *
     * Through `du` rather than through NIO: the JDK's `unix:` set has **no** `blocks` attribute
     * (`IllegalArgumentException: 'blocks' not recognized`), so occupied space cannot be got out of
     * `Files.readAttributes` at all. `du` counts precisely that, and the measurement is only
     * meaningful on Linux with the filesystem the question is about anyway.
     */
    private fun allocatedBytes(root: Path): Long {
        val process =
            ProcessBuilder("du", "-s", "--block-size=1", root.toString())
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) { "du refused to measure $root: $output" }
        return output.split(Regex("\\s+")).first().toLong()
    }

    private fun gib(bytes: Long): String =
        when {
            bytes >= GIB -> "%.2f GiB".format(bytes / GIB.toDouble())
            bytes >= MIB -> "%.1f MiB".format(bytes / MIB.toDouble())
            else -> "%.1f KiB".format(bytes / KIB.toDouble())
        }

    /**
     * M-61 again, with a network card in the path.
     *
     * The loopback measurement above answers "does `transferTo` cost less processor per byte than
     * reading into the heap", and it answers it on a path with **no device in it**: no driver, no
     * DMA, no interrupts, no segmentation. Every architectural decision in this project that rests
     * on zero-copy — the response-as-file-slice, the refusal to wrap the socket, TLS terminated
     * outside (Р5) — rests on the ratio surviving a real one.
     *
     * So: same file, same two variants, same buffer sizes, same repeats. The only thing that
     * changes is where the other end of the socket is. Changing one thing at a time is why this is
     * a separate mode rather than a flag on the old one.
     *
     * The reader is the same drain the loopback measurement uses, running on the other machine
     * ([drain]). It reconnects after every transfer, so the two sides need no agreement about how
     * many transfers there will be.
     */
    private fun serveOverNetwork(
        dir: Path,
        bytes: Long,
        bindHost: String,
        port: Int,
    ) {
        println("== M-61 over a network card: serving an object ==")
        val file = fill(dir.resolve("serve.bin"), bytes)

        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(bindHost, port))
            println("  listening on $bindHost:$port — start the drain on the other machine")

            val zeroCopy =
                Measurement.repeated("transferTo (zero-copy)", bytes, repeats) {
                    overNetwork(server, "transferTo (zero-copy)", bytes) { socket ->
                        FileChannel.open(file, StandardOpenOption.READ).use { source ->
                            var position = 0L
                            while (position < bytes) position += source.transferTo(position, bytes - position, socket)
                        }
                    }
                }
            val throughHeap =
                Measurement.repeated("read into heap, then write", bytes, repeats) {
                    overNetwork(server, "read into heap, then write", bytes) { socket ->
                        FileChannel.open(file, StandardOpenOption.READ).use { source ->
                            val buffer = ByteBuffer.allocate(8 * KIB.toInt())
                            while (true) {
                                buffer.clear()
                                if (source.read(buffer) < 0) break
                                buffer.flip()
                                while (buffer.hasRemaining()) socket.write(buffer)
                            }
                        }
                    }
                }

            println(zeroCopy)
            println(throughHeap)
            println("  ${Measurement.compare(zeroCopy.median, throughHeap.median)}")
        }
    }

    /**
     * One transfer to a reader that is somewhere else.
     *
     * `accept` sits outside the measured region, exactly as the loopback version keeps its thread
     * start outside: what is being timed is sending the bytes, not waiting for somebody to ask.
     */
    private fun overNetwork(
        server: ServerSocketChannel,
        name: String,
        bytes: Long,
        send: (SocketChannel) -> Unit,
    ): Measurement =
        server.accept().use { socket ->
            val measured = Measurement.of(name, bytes) { send(socket) }
            socket.shutdownOutput()
            measured
        }

    /**
     * The other end of the wire: connect, read until the sender is done, connect again.
     *
     * Deliberately the same drain as the loopback reader — a direct 256 KiB buffer and nothing
     * else — so that moving the measurement onto a network card changes the path and not the
     * reader too. It prints only what is needed to see that it is alive; the numbers live on the
     * sending side, which is the side whose processor time is the question.
     */
    private fun drain(
        host: String,
        port: Int,
    ) {
        println("draining $host:$port — stop with Ctrl-C")
        val sink = ByteBuffer.allocateDirect(256 * KIB.toInt())
        var transfer = 0
        var refused = 0
        while (true) {
            val socket =
                try {
                    SocketChannel.open(InetSocketAddress(host, port))
                } catch (e: java.io.IOException) {
                    // The sender is not listening yet, or has finished and gone. Neither is fatal
                    // here — this side is a fixture, and one that dies on a closed port has to be
                    // restarted by hand between variants — but it is **said out loud**, every
                    // tenth attempt so a long wait does not drown the log.
                    //
                    // Silence here cost a run: a drain that could not connect printed exactly what
                    // a drain that was connected and busy prints, which is nothing, and the two
                    // were indistinguishable from the outside for ten minutes.
                    if (refused++ % 10 == 0) println("  cannot connect to $host:$port (${e.message}); retrying")
                    Thread.sleep(200)
                    continue
                }
            refused = 0
            var read = 0L
            socket.use {
                while (true) {
                    sink.clear()
                    val n = it.read(sink)
                    if (n < 0) break
                    read += n
                }
            }
            transfer++
            println("  transfer $transfer: ${"%.2f".format(read / GIB.toDouble())} GiB")
        }
    }

    /**
     * M-58: taking an upload, which is never zero-copy.
     *
     * `transferFrom` has a fast path only when the **source** is a file (§1.6.2), and on a `PUT`
     * the source is a socket — so the bytes go through user space whatever is written here. What
     * this measures is how much that actually costs, and against what ceiling: the third variant
     * writes the same bytes to the same disk with no socket at all, which is the floor no upload
     * path can go below.
     *
     * The decision that hangs on it (backlog M-58): `splice(2)` through FFM is worth introducing
     * only if the answer is "the socket path is well above the disk".
     */
    private fun write(
        dir: Path,
        bytes: Long,
    ) {
        println("== M-58: taking an upload ==")
        val target = dir.resolve("upload.bin")

        // Three variants and not two, because "heap 8 KiB" against "direct 256 KiB" changes two
        // things at once and a ratio between them cannot say which one moved. The middle variant
        // separates the size from the kind of buffer, and the two answers turn out to be different
        // sizes of answer — which is the whole reason the axis has to be picked before the verdict.
        val heapSmall =
            Measurement.repeated("socket -> heap 8 KiB -> file", bytes, repeats) {
                intoFile("socket -> heap 8 KiB -> file", bytes, target) { socket, out ->
                    drain(socket, ByteBuffer.allocate(8 * KIB.toInt()), out)
                }
            }
        val heapLarge =
            Measurement.repeated("socket -> heap 256 KiB -> file", bytes, repeats) {
                intoFile("socket -> heap 256 KiB -> file", bytes, target) { socket, out ->
                    drain(socket, ByteBuffer.allocate(256 * KIB.toInt()), out)
                }
            }
        val direct =
            Measurement.repeated("socket -> direct 256 KiB -> file", bytes, repeats) {
                intoFile("socket -> direct 256 KiB -> file", bytes, target) { socket, out ->
                    drain(socket, ByteBuffer.allocateDirect(256 * KIB.toInt()), out)
                }
            }

        val ceiling =
            Measurement.repeated("disk alone, no socket", bytes, repeats) {
                Measurement.of("disk alone, no socket", bytes) {
                    FileChannel
                        .open(
                            target,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        ).use { out ->
                            val buffer = ByteBuffer.allocateDirect(256 * KIB.toInt())
                            var written = 0L
                            while (written < bytes) {
                                buffer.clear()
                                buffer.limit(minOf(buffer.capacity().toLong(), bytes - written).toInt())
                                while (buffer.hasRemaining()) written += out.write(buffer)
                            }
                            out.force(true)
                        }
                }
            }

        println(heapSmall)
        println(heapLarge)
        println(direct)
        println(ceiling)
        println("  size:  ${Measurement.compare(heapLarge.median, heapSmall.median)}")
        println("  kind:  ${Measurement.compare(heapLarge.median, direct.median)}")
        println("  floor: ${Measurement.compare(ceiling.median, heapSmall.median)}")
    }

    /**
     * M-62: joining parts against serving them as a list (open question 3).
     *
     * Two costs, and the decision is which one to pay. Joining costs one copy of the whole object
     * at completion and nothing afterwards; keeping a list of parts costs nothing at completion
     * and one extra `transferTo` per part on every read for ever. This measures both sides in the
     * same run, so the answer is a comparison rather than an opinion.
     */
    private fun assemble(
        dir: Path,
        bytes: Long,
    ) {
        println("== M-62: joining parts against keeping them ==")
        val partSize = 8 * MIB
        val parts = (bytes / partSize).toInt().coerceAtLeast(2)
        val sources = (0 until parts).map { fill(dir.resolve("part-$it.bin"), partSize) }
        val total = partSize * parts
        val joined = dir.resolve("joined.bin")

        val joining =
            Measurement.repeated("join $parts parts at completion", total, repeats) {
                Measurement.of("join $parts parts at completion", total) {
                    FileChannel
                        .open(
                            joined,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        ).use { out ->
                            var at = 0L
                            for (part in sources) {
                                FileChannel.open(part, StandardOpenOption.READ).use { source ->
                                    var moved = 0L
                                    while (moved < partSize) {
                                        moved += out.transferFrom(source, at + moved, partSize - moved)
                                    }
                                    at += moved
                                }
                            }
                            out.force(true)
                        }
                }
            }

        val servedWhole =
            Measurement.repeated("serve the joined object", total, repeats) {
                overLoopback("serve the joined object", total) { socket ->
                    FileChannel.open(joined, StandardOpenOption.READ).use { source ->
                        var position = 0L
                        while (position < total) position += source.transferTo(position, total - position, socket)
                    }
                }
            }
        val servedAsParts =
            Measurement.repeated("serve $parts parts in sequence", total, repeats) {
                overLoopback("serve $parts parts in sequence", total) { socket ->
                    for (part in sources) {
                        FileChannel.open(part, StandardOpenOption.READ).use { source ->
                            var at = 0L
                            while (at < partSize) at += source.transferTo(at, partSize - at, socket)
                        }
                    }
                }
            }

        println(joining)
        println(servedWhole)
        println(servedAsParts)
        println("  ${Measurement.compare(servedWhole.median, servedAsParts.median)}")
        val perRead = servedAsParts.median.cpuSecondsPerGiB - servedWhole.median.cpuSecondsPerGiB
        println(
            "  joining costs %.3f cpu-s/GiB once; serving as parts costs %+.3f cpu-s/GiB on every read"
                .format(joining.median.cpuSecondsPerGiB, perRead),
        )
        if (perRead > 0) {
            println("  so joining pays for itself after %.0f reads".format(joining.median.cpuSecondsPerGiB / perRead))
        }
    }

    /**
     * The spread of a sorted list of per-operation times, as a printable word.
     *
     * The ratio of the slowest run to the fastest is only a ratio while the fastest is a number.
     * On a variant that costs a single map lookup the fastest run rounds to **zero** nanoseconds
     * per operation, and the division then prints `Infinity` in a column of measurements — a
     * non-number standing where a reader expects a figure to compare. What the zero means is that
     * the variant is under the clock's resolution, so that is what it says. Either way the row is
     * marked as one to draw no conclusion from.
     */
    private fun spread(sorted: List<Long>): String =
        if (sorted.first() <= 0L) {
            "under the clock  ← at the resolution floor, no conclusion from the figure"
        } else {
            val ratio = sorted.last().toDouble() / sorted.first()
            "%.2f%s".format(ratio, if (ratio > 1.3) "  ← too noisy to conclude from" else "")
        }

    /**
     * M-178, the half a wire cannot answer: what the lifecycle lookup itself costs a read.
     *
     * The end-to-end run on the two-machine stand could not see it — three variants inside a spread
     * of 1.2 to 1.7 — and that is a fact about the instrument rather than about the code: a request
     * across a public link at 2.4 ms costs hundreds of microseconds, and the thing being looked for
     * is a map lookup. Measuring it there is measuring the link.
     *
     * So it is measured where it happens, on one thread, with nothing else in the way. What is
     * timed is exactly what M23 added to a read that was not doing it before: finding the bucket's
     * rules, deciding whether the object is under one, and formatting the date the header carries.
     * What is **not** here is everything a read already did — the signature, the socket, the file —
     * which is the point: those did not change, and folding them in would bury the number again.
     */
    private fun readPath(dir: Path) {
        println("== M-178: the lifecycle lookup on the read path ==")
        val home = Files.createDirectories(dir.resolve("readpath"))
        Files.deleteIfExists(home.resolve("index.log"))
        val store = ObjectStore(root = home, durability = ObjectStore.Durability.NONE)
        store.createBucket("nolc")
        store.createBucket("nomatch")
        store.createBucket("match")

        fun rules(prefix: String) =
            S3Documents.lifecycleResult(
                S3Requests.parseLifecycle(
                    (
                        "<LifecycleConfiguration><Rule><ID>bench</ID>" +
                            "<Expiration><Days>30</Days></Expiration>" +
                            "<Prefix>$prefix</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"
                    ).toByteArray(),
                ),
            )
        store.putBucketSubresource("nomatch", "lifecycle", rules("other/"))
        store.putBucketSubresource("match", "lifecycle", rules(""))

        val lifecycles = Lifecycles(store)
        val key = ObjectKey.of("photos/2026/08/img.jpg")
        val created = java.time.Instant.parse("2026-08-19T14:30:00Z")
        val httpDate =
            java.time.format.DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.ENGLISH)
                .withZone(java.time.ZoneOffset.UTC)

        // The whole of what a read now does and did not before, including the string the client
        // sees: a lookup that stops at "no rules" and one that ends in a formatted date are
        // different amounts of work, and reporting only the first would flatter the feature.
        // Every branch returns something different and every return is added up, because a branch
        // that returns a constant zero is a branch the JIT may delete: the first run of this
        // reported two nanoseconds for the bucket with no rules — six cycles, which is less than
        // the two map lookups it makes, and therefore a measurement of the optimiser.
        fun once(bucket: String): Int {
            val lifecycle = lifecycles.of(bucket) ?: return 1
            val hit = lifecycle.expiryOf(key, 1024, emptyMap(), created, Lifecycle.DAY) ?: return 2
            return ("expiry-date=\"${httpDate.format(hit.first)}\", rule-id=\"${hit.second.id}\"").length
        }

        val rounds = System.getenv("BOCHKA_MEASURE_KEYS")?.toIntOrNull() ?: 2_000_000
        for (bucket in listOf("nolc", "nomatch", "match")) {
            // Warm the path before measuring it: the first thousand calls of anything on a JVM are
            // the interpreter, and publishing those as the cost of a read would be publishing the
            // cost of the first read after a restart.
            var sink = 0
            repeat(200_000) { sink += once(bucket) }

            val nanos = ArrayList<Long>(repeats)
            repeat(repeats) {
                val started = Measurement.currentThreadCpuNanos()
                repeat(rounds) { sink += once(bucket) }
                nanos += (Measurement.currentThreadCpuNanos() - started) / rounds
            }
            nanos.sort()
            println(
                "%-10s %8d ns per read  spread %s  (checksum %d)".format(
                    bucket,
                    nanos[nanos.size / 2],
                    spread(nanos),
                    sink,
                ),
            )
        }
        store.close()
    }

    /**
     * M-209: what the access model costs a read, measured where it happens.
     *
     * The same shape as [readPath] and for the same reason: what M27 added to a request that was
     * not doing it before is a handful of map lookups and — on an object read — one lookup in the
     * index for the version's owner. A wire cannot see that; a request across a socket costs
     * hundreds of microseconds and this is nanoseconds, so measuring it end to end would measure
     * the socket (M-178 learned that the expensive way).
     *
     * Three variants, and the first is the one that matters: a bucket with no recorded owner is
     * every deployment that existed before this milestone, and it pays only the early return.
     */
    private fun accessPath(dir: Path) {
        println("== M-209: the access check on the read path ==")
        val home = Files.createDirectories(dir.resolve("accesspath"))
        Files.deleteIfExists(home.resolve("index.log"))
        val store = ObjectStore(root = home, durability = ObjectStore.Durability.NONE)
        store.createBucket("legacy")
        store.createBucket("owned", owner = "main", acl = "private")
        store.createBucket("shared", owner = "main", acl = "public-read")

        val key = ObjectKey.of("photos/2026/08/img.jpg")
        // Written **with** an owner where the bucket has one: an object whose owner is null makes
        // the decision short-circuit, and the number would then be a floor rather than the cost.
        kotlinx.coroutines.runBlocking {
            for ((bucket, acl) in listOf("legacy" to null, "owned" to "private", "shared" to "public-read")) {
                val staged = store.stage { out -> out.write(ByteArray(1024), 0, 1024) }
                store.commit(
                    bucket = bucket,
                    key = key,
                    metadata = Metadata.EMPTY,
                    staged = staged,
                    owner = if (acl == null) null else "main",
                    acl = acl,
                )
            }
        }

        // Exactly what `S3Handler.aclRefusal` does for a `GET`, and nothing else: the bucket's
        // owner and ACL, then — when there is an owner — the version's, then the decision. Every
        // branch returns something different and all of it is summed, because a branch returning a
        // constant is a branch the JIT is free to delete (M-178 published two nanoseconds once,
        // and that was the optimiser rather than the code).
        fun once(
            bucket: String,
            requester: String,
        ): Int {
            val resource = AccessControl.Resource(store.bucketOwner(bucket), store.bucketAcl(bucket))
            if (resource.unrestricted) return 1
            val stored = store.currentVersion(bucket, key) ?: return 2
            val obj = AccessControl.Resource(stored.owner, stored.acl)
            return if (AccessControl.allowsObjectRead(obj, requester, resource.owner)) 3 else 4
        }

        val rounds = System.getenv("BOCHKA_MEASURE_KEYS")?.toIntOrNull() ?: 2_000_000
        for ((bucket, requester) in listOf("legacy" to "main", "owned" to "main", "shared" to "stranger")) {
            var sink = 0
            repeat(200_000) { sink += once(bucket, requester) }

            val nanos = ArrayList<Long>(repeats)
            repeat(repeats) {
                val started = Measurement.currentThreadCpuNanos()
                repeat(rounds) { sink += once(bucket, requester) }
                nanos += (Measurement.currentThreadCpuNanos() - started) / rounds
            }
            nanos.sort()
            println(
                "%-8s as %-9s %6d ns per read  spread %s  (checksum %d)".format(
                    bucket,
                    requester,
                    nanos[nanos.size / 2],
                    spread(nanos),
                    sink,
                ),
            )
        }
        store.close()
    }

    /**
     * M-179 and M-180: what one lifecycle pass costs, by how much there is to walk.
     *
     * Not a [Measurement], and that is the point of writing it separately: everything else here is
     * processor time **per byte**, and a sweep moves no bytes at all. Its cost is per *version* in
     * the index, so forcing it into a per-byte figure would produce a number that divides by
     * something the work does not depend on.
     *
     * **Nothing is due, and that is the measurement rather than a shortcut.** A pass that deletes
     * is proportional to what it deletes, which is a property of the bucket's history; a pass that
     * deletes nothing is what every bucket with rules pays every period, for ever. That is the
     * number the derived period has to be compared against — if a pass over a million versions
     * takes longer than the interval between passes, passes overlap, and today that was a guess.
     *
     * The store is built by writing the log and opening it, the way [index] does: the sweep with
     * nothing due never opens a data file, so files for a million objects would cost minutes of
     * fixture to be read zero times.
     */
    private fun sweep(dir: Path) {
        println("== M-179/M-180: what a lifecycle pass costs ==")
        // Everything matches, nothing is due for thirty thousand days: the walk happens in full and
        // ends with an empty report.
        val document =
            S3Documents.lifecycleResult(
                S3Requests.parseLifecycle(
                    (
                        "<LifecycleConfiguration><Rule><ID>bench</ID>" +
                            "<Expiration><Days>30000</Days></Expiration>" +
                            "<Prefix></Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"
                    ).toByteArray(),
                ),
            )

        fun measureSweep(
            label: String,
            keys: Int,
            versionsPerKey: Int,
        ) {
            val home = Files.createDirectories(dir.resolve("sweep-$keys-$versionsPerKey"))
            val log = home.resolve("index.log")
            Files.deleteIfExists(log)
            RecordLog(log).use { fresh ->
                fresh.recover { }
                fresh.append(IndexRecord.encode(IndexRecord.BucketCreated("photos")))
                fresh.append(IndexRecord.encode(IndexRecord.BucketSubresource("photos", "lifecycle", document)))
                var sequence = 0L
                for (i in 0 until keys) {
                    val key = ObjectKey.of("photos/%08d/img.jpg".format(i))
                    repeat(versionsPerKey) { generation ->
                        fresh.append(
                            IndexRecord.encode(
                                IndexRecord.Put(
                                    bucket = "photos",
                                    key = key,
                                    fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e33",
                                    size = 1024,
                                    eTag = "\"d41d8cd98f00b204e9800998ecf8427e\"",
                                    lastModifiedMillis = 1_755_400_000_000L,
                                    metadata = Metadata(contentType = "image/jpeg"),
                                    sequence = sequence++,
                                    versionId = "v%d-%d".format(i, generation),
                                ),
                            ),
                        )
                    }
                }
                fresh.force()
            }

            val store = ObjectStore(root = home, durability = ObjectStore.Durability.NONE, maxObjects = Int.MAX_VALUE)
            val sweeper = LifecycleSweep(store, Lifecycles(store), Lifecycle.DAY)
            // One pass before the measured ones: the first walk of a freshly opened index pays for
            // page faults and JIT, and reporting that as the cost of a pass would be reporting the
            // cost of the first pass after a restart — a different question, and one nobody asked.
            sweeper.sweep()

            val walls = ArrayList<Long>(repeats)
            val cpus = ArrayList<Long>(repeats)
            repeat(repeats) {
                val cpu0 = Measurement.currentThreadCpuNanos()
                val wall0 = System.nanoTime()
                val report = sweeper.sweep()
                walls += System.nanoTime() - wall0
                cpus += Measurement.currentThreadCpuNanos() - cpu0
                check(report.empty) { "the fixture is not steady state: $report" }
            }
            walls.sort()
            cpus.sort()
            val wall = walls[walls.size / 2] / 1e9
            val cpu = cpus[cpus.size / 2] / 1e9
            val spread = walls.last().toDouble() / walls.first()
            val versions = keys.toLong() * versionsPerKey
            println(
                "%-28s %9d versions  %7.3f s wall  %7.3f s cpu  %8.2f µs/version  spread %.2f%s".format(
                    label,
                    versions,
                    wall,
                    cpu,
                    wall / versions * 1e6,
                    spread,
                    if (spread > 1.3) "  ← too noisy to conclude from" else "",
                ),
            )
            store.close()
        }

        println("-- M-179: by number of keys, one version each")
        for (keys in listOf(10_000, 100_000, 1_000_000)) measureSweep("$keys keys", keys, 1)
        println("-- M-180: by versions per key, at a thousand keys")
        for (versions in listOf(1, 10, 100)) measureSweep("$versions per key", 1_000, versions)
    }

    /**
     * M-64 and M-66: what the index costs in memory, and what it costs to open.
     *
     * The ceiling this project publishes is a **count of objects**, not a volume of data, because
     * that is what the bitcask shape makes it (Р1) — so the number that has to exist is bytes of
     * heap per key in the index. It is measured through the log rather than by uploading: recovery
     * populates exactly the structure being measured and touches no object files, so the figure is
     * about the index and nothing else.
     *
     * Reported for two key lengths, because "bytes per object" is not a constant and publishing it
     * as one would be the misleading half of a true statement.
     *
     * Needs a heap: `-Pbochka.jvmArgs="-Xmx4G -XX:+UseSerialGC"`.
     */
    private fun index(dir: Path) {
        println("== M-64/M-66: what the index costs ==")
        val counts = System.getenv("BOCHKA_MEASURE_KEYS")?.toIntOrNull() ?: 500_000
        // Every key written more than once, because a log with no dead records compacts to its own
        // size and a demonstration of that says nothing about compaction. Three generations is what
        // an object overwritten twice leaves behind.
        val generations = System.getenv("BOCHKA_MEASURE_GENERATIONS")?.toIntOrNull() ?: 3

        // Both lengths by default, because "bytes per object" is not a constant and publishing it
        // as one would be the misleading half of a true statement. Overridable because a run that
        // wants a **known** live set cannot have two stores in it: the peak live set of a two-length
        // run is larger than any real store holds at that object count, so a pause measured against
        // it compares collectors to each other and nothing else (M-159).
        val lengths =
            System
                .getenv("BOCHKA_MEASURE_KEY_LENGTHS")
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(40, 100)

        for (keyLength in lengths) {
            val home = Files.createDirectories(dir.resolve("index-$keyLength"))
            val log = home.resolve("index.log")
            Files.deleteIfExists(log)

            RecordLog(log).use { fresh ->
                fresh.recover { }
                fresh.append(IndexRecord.encode(IndexRecord.BucketCreated("photos")))
                repeat(generations) { generation ->
                    for (i in 0 until counts) {
                        val key = "photos/%0${keyLength - 17}d/img.jpg".format(i)
                        fresh.append(
                            IndexRecord.encode(
                                IndexRecord.Put(
                                    bucket = "photos",
                                    key = ObjectKey.of(key),
                                    fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e3$generation",
                                    size = 4096,
                                    eTag = "\"d41d8cd98f00b204e9800998ecf8427e\"",
                                    lastModifiedMillis = 1_755_400_000_000L,
                                    metadata = Metadata(contentType = "image/jpeg"),
                                ),
                            ),
                        )
                    }
                }
                fresh.force()
            }

            val logBytes = Files.size(log)
            val before = usedHeap()
            val startedAt = System.nanoTime()
            val store = ObjectStore(home, ObjectStore.Durability.NONE)
            val openNanos = System.nanoTime() - startedAt
            val after = usedHeap()
            val perObject = (after - before).toDouble() / store.objectCount

            println(
                "  %3d-byte keys  %,d objects  log %,.1f MiB  open %6.3f s  heap %,.1f MiB  %6.0f bytes/object".format(
                    keyLength,
                    store.objectCount,
                    logBytes / (1024.0 * 1024),
                    openNanos / 1e9,
                    (after - before) / (1024.0 * 1024),
                    perObject,
                ),
            )

            // M-66: a compaction writes exactly the live set, so opening does not get slower with
            // the number of compactions. Shown rather than argued.
            val compacted = store.compact()
            val reopenedAt = System.nanoTime()
            val reopened = ObjectStore(home, ObjectStore.Durability.NONE)
            println(
                "                 after compaction: log %,.1f MiB (was %,.1f), open %6.3f s, %,d objects".format(
                    compacted.bytesAfter / (1024.0 * 1024),
                    compacted.bytesBefore / (1024.0 * 1024),
                    (System.nanoTime() - reopenedAt) / 1e9,
                    reopened.objectCount,
                ),
            )
            reopened.close()
            store.close()
        }
    }

    /**
     * M-151/M-152: what a collector costs when the live set is the index.
     *
     * The axis is the collector and nothing else. Heap, object count, operation sequence and the
     * volume of garbage are identical in every variant; the collector is chosen by the JVM that
     * launches this, which is why the matrix lives in `ci/gc-measure.sh` and this prints one
     * variant. The live set is built once per heap into a seed directory and read by every variant
     * after it, because a fill repeated twenty-seven times measures the disk.
     *
     * Two instruments, because they disagree by construction and the disagreement is the finding.
     *
     * **A forced full collection.** `System.gc()` against a known live set, timed. For Serial,
     * Parallel and G1 that is a stop-the-world compaction and the number is the pause; for ZGC it
     * is a mostly concurrent cycle and the number is its duration, which is not what a request
     * waits for. Reported either way and labelled either way — a table that put them in one column
     * would say ZGC is the slowest collector here, which is the opposite of true.
     *
     * **A thread that only sleeps.** One millisecond at a time, recording how much longer than a
     * millisecond it took. It measures what a request would feel — every safepoint, not only the
     * ones a collector reports — and it does not care what a collector calls its phases. Its
     * median is the stand's own noise and is printed for that reason: a maximum without it is a
     * number with no scale.
     */
    private fun gcPauses(dir: Path) {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val keys = System.getenv("BOCHKA_MEASURE_KEYS")?.toIntOrNull() ?: ObjectStore.ceilingForHeap(maxMemory)
        val garbage = (System.getenv("BOCHKA_MEASURE_GARBAGE_GIB")?.toDoubleOrNull() ?: 8.0) * GIB
        val seed = System.getenv("BOCHKA_MEASURE_SEED")?.let { Path.of(it) } ?: dir.resolve("gc-$keys")

        println("== M-151: the collector against a live set that is the index ==")
        println("  collector   ${collectorNames()}")
        println(
            "  heap        -Xmx %s, maxMemory %,.1f MiB (%.1f%% of it)".format(
                ManagementRuntime.heapArgument(),
                maxMemory / MIB.toDouble(),
                100.0 * maxMemory / ManagementRuntime.heapArgumentBytes().coerceAtLeast(1),
            ),
        )
        // M-152: the published ceiling is a function of the collector, because `maxMemory()` is.
        // The fill is **not** — every variant holds the same objects, or the pauses below would be
        // comparing live sets rather than collectors.
        println(
            "  ceiling     %,d versions of its own; filled with %,d".format(
                ObjectStore.ceilingForHeap(maxMemory),
                keys,
            ),
        )

        fill(seed, keys)

        val collections = GcLog().also { it.install() }
        val openedAt = System.nanoTime()
        val store = ObjectStore(seed, ObjectStore.Durability.NONE, maxObjects = keys)
        val openNanos = System.nanoTime() - openedAt
        check(store.objectCount == keys) { "the seed holds ${store.objectCount} objects, not $keys" }

        val stalls = Hiccups().also { it.start() }
        try {
            // Its first samples are the thread starting, not the machine stalling: on a loaded box
            // that showed up as 123 ms of "pause" beside a 27 ms collection.
            Thread.sleep(500)
            // Long enough to leave the collector nothing to hide behind, and forced rather than
            // waited for: a full collection that happens by itself happens at a moment nobody
            // chose, and timing it means timing the wait as well.
            val forced = LongArray(FORCED_COLLECTIONS)
            val felt = LongArray(FORCED_COLLECTIONS)
            for (i in forced.indices) {
                val from = stalls.mark()
                val before = System.nanoTime()
                System.gc()
                forced[i] = System.nanoTime() - before
                // The stalled thread writes its sample **after** it is let go, so a window closed
                // the instant `System.gc()` returns is closed before the number it is asking for
                // exists. It read a median of zero beside a thirty-millisecond collection.
                Thread.sleep(100)
                felt[i] = stalls.max(from, stalls.mark())
            }
            forced.sort()
            felt.sort()

            val live = usedHeap()
            println("  open        %.3f s for %,d objects".format(openNanos / 1e9, store.objectCount))
            println(
                "  live set    %,.1f MiB after a full collection — %.1f%% of maxMemory, %.0f bytes an object".format(
                    live / MIB.toDouble(),
                    100.0 * live / maxMemory,
                    live.toDouble() / keys,
                ),
            )
            println(
                "  forced full median %7.3f s   min %7.3f   max %7.3f   (%s)".format(
                    forced[forced.size / 2] / 1e9,
                    forced.first() / 1e9,
                    forced.last() / 1e9,
                    if (isConcurrent()) "a cycle, not a pause — this collector is concurrent" else "stop-the-world",
                ),
            )
            // Per collection rather than over all of them, because the first full collection after
            // a fill is not like the ones after it and a single window would publish that one.
            println(
                "  felt as     median %,.1f ms of stall, worst %,.1f".format(
                    felt[felt.size / 2] / 1e6,
                    felt.last() / 1e6,
                ),
            )

            val loadFrom = stalls.mark()
            val minor = collections.minorCount()
            val major = collections.majorCount()
            val load = churn(store, keys, garbage)
            val loadTo = stalls.mark()

            println(
                "  load        %,d lookups, %,d listings, %,.2f GiB allocated in %.1f s (%,.0f MiB/s)".format(
                    load.lookups,
                    load.listings,
                    load.allocated / GIB.toDouble(),
                    load.nanos / 1e9,
                    load.allocated / MIB.toDouble() / (load.nanos / 1e9),
                ),
            )
            println(
                "  processor   %.1f s of CPU for %,.2f GiB of garbage — %.2f cpu-s per GiB".format(
                    load.cpuNanos / 1e9,
                    load.allocated / GIB.toDouble(),
                    (load.cpuNanos / 1e9) / (load.allocated / GIB.toDouble()),
                ),
            )
            println("  by name     ${collections.byName()}")
            println(
                "  collections minor %,d (%,.0f ms) major %,d (%,.0f ms) — the collector's own count".format(
                    collections.minorCount() - minor,
                    collections.minorMillis(minor),
                    collections.majorCount() - major,
                    collections.majorMillis(major),
                ),
            )
            println(
                "  stalls      p50 %6.2f ms  p99 %7.2f  p99.9 %8.2f  max %9.2f".format(
                    stalls.percentile(loadFrom, loadTo, 50.0) / 1e6,
                    stalls.percentile(loadFrom, loadTo, 99.0) / 1e6,
                    stalls.percentile(loadFrom, loadTo, 99.9) / 1e6,
                    stalls.max(loadFrom, loadTo) / 1e6,
                ),
            )
            println("  rss         %,d MiB now, %,d MiB at its peak".format(Rss.current() / MIB, Rss.peak() / MIB))

            // One line a script can read, because the matrix is assembled by `ci/gc-measure.sh`
            // and parsing the prose above would be a second description of this measurement.
            println(
                (
                    "RESULT collector=%s xmx=%d max=%d ceiling=%d objects=%d open=%.3f live=%d " +
                        "forced_median=%.3f forced_max=%.3f felt_median=%.3f felt_max=%.3f minor=%d minor_ms=%.0f " +
                        "major=%d major_ms=%.0f p50=%.3f p99=%.3f p999=%.3f stall_max=%.3f " +
                        "load=%.1f alloc=%.2f cpu=%.1f rss=%d concurrent=%b"
                ).format(
                    collectorTag(),
                    ManagementRuntime.heapArgumentBytes() / MIB,
                    maxMemory / MIB,
                    ObjectStore.ceilingForHeap(maxMemory),
                    keys,
                    openNanos / 1e9,
                    live / MIB,
                    forced[forced.size / 2] / 1e9,
                    forced.last() / 1e9,
                    felt[felt.size / 2] / 1e6,
                    felt.last() / 1e6,
                    collections.minorCount() - minor,
                    collections.minorMillis(minor),
                    collections.majorCount() - major,
                    collections.majorMillis(major),
                    stalls.percentile(loadFrom, loadTo, 50.0) / 1e6,
                    stalls.percentile(loadFrom, loadTo, 99.0) / 1e6,
                    stalls.percentile(loadFrom, loadTo, 99.9) / 1e6,
                    stalls.max(loadFrom, loadTo) / 1e6,
                    load.nanos / 1e9,
                    load.allocated / GIB.toDouble(),
                    load.cpuNanos / 1e9,
                    Rss.peak() / MIB,
                    isConcurrent(),
                ),
            )
        } finally {
            stalls.stop()
            collections.uninstall()
            store.close()
        }
    }

    /** How many objects this heap publishes room for. Printed alone, for the matrix to read. */
    private fun ceiling() {
        println("ceiling ${ObjectStore.ceilingForHeap()} maxMemory ${Runtime.getRuntime().maxMemory()}")
    }

    /**
     * The live set on disk, written once and read by every variant after it.
     *
     * One generation and one key length, both deliberate: a log with three generations of every
     * key has a **peak** live set larger than any store of this object count holds, and a pause
     * measured against it compares collectors to each other rather than to a deployment.
     */
    private fun fill(
        seed: Path,
        keys: Int,
    ) {
        val log = seed.resolve("index.log")
        if (Files.exists(log) && Files.size(log) > 0) {
            println("  seed        %s, %,.1f MiB — reused".format(seed, Files.size(log) / MIB.toDouble()))
            return
        }
        Files.createDirectories(seed)
        val startedAt = System.nanoTime()
        RecordLog(log).use { fresh ->
            fresh.recover { }
            fresh.append(IndexRecord.encode(IndexRecord.BucketCreated(GC_BUCKET)))
            for (i in 0 until keys) {
                fresh.append(
                    IndexRecord.encode(
                        IndexRecord.Put(
                            bucket = GC_BUCKET,
                            key = ObjectKey.of(gcKey(i)),
                            fileId = "0e2b9c34-6a1f-4a7d-9b8e-2f5c1d0a7e30",
                            size = 4096,
                            eTag = "\"d41d8cd98f00b204e9800998ecf8427e\"",
                            lastModifiedMillis = 1_755_400_000_000L,
                            metadata = Metadata(contentType = "image/jpeg"),
                        ),
                    ),
                )
            }
            fresh.force()
        }
        println(
            "  seed        %s, %,.1f MiB — written in %.1f s".format(
                seed,
                Files.size(log) / MIB.toDouble(),
                (System.nanoTime() - startedAt) / 1e9,
            ),
        )
    }

    /** What one load phase did. */
    private class Churn(
        val lookups: Long,
        val listings: Long,
        val allocated: Long,
        val nanos: Long,
        /**
         * Processor time of the **whole process**, not of the thread doing the work.
         *
         * A concurrent collector moves work off the request path rather than removing it, and a
         * per-thread figure cannot see that: it would report the collector that spends four cores
         * marking as the cheapest one in the table. This project decides on processor per unit of
         * work everywhere else (`docs/measurements.md`), and a collector is not an exception.
         */
        val cpuNanos: Long,
    )

    /**
     * Garbage against the index, in the shape this server makes it.
     *
     * The budget is bytes allocated rather than seconds or operations, and that is the whole
     * point: every variant digests the same garbage against the same live set, and the wall clock
     * is a result rather than an input. A time-boxed loop would let the faster collector allocate
     * more and then report more collections for it.
     *
     * A listing every hundredth lookup, because a page of a thousand entries is the largest
     * allocation this server makes on a read path and the one that scales with the index — it
     * still dominates the garbage at that ratio, by roughly three to one. Lookups alone produce
     * garbage at a rate no collector notices, and a store that listed as often as it read would be
     * a workload nobody has.
     */
    private fun churn(
        store: ObjectStore,
        keys: Int,
        budget: Double,
    ): Churn {
        val prefix = "photos/".toByteArray()
        val random = java.util.Random(20_260_819)
        var lookups = 0L
        var listings = 0L
        val allocatedBefore = allocatedBytes()
        val cpuBefore = processCpuNanos()
        val startedAt = System.nanoTime()
        while (true) {
            repeat(100) {
                val key = gcKey(random.nextInt(keys))
                store.get(GC_BUCKET, ObjectKey.of(key))
                lookups++
                if (lookups % 100 == 0L) {
                    store.list(GC_BUCKET, prefix = prefix, startAfter = key.toByteArray(), maxKeys = 1000)
                    listings++
                }
            }
            if (allocatedBytes() - allocatedBefore >= budget) break
        }
        return Churn(
            lookups,
            listings,
            allocatedBytes() - allocatedBefore,
            System.nanoTime() - startedAt,
            processCpuNanos() - cpuBefore,
        )
    }

    private fun gcKey(i: Int): String = "photos/%023d/img.jpg".format(i)

    private fun processCpuNanos(): Long =
        (
            java.lang.management.ManagementFactory
                .getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        ).processCpuTime

    private fun allocatedBytes(): Long =
        (
            java.lang.management.ManagementFactory
                .getThreadMXBean() as com.sun.management.ThreadMXBean
        ).currentThreadAllocatedBytes

    private fun collectorNames(): String =
        java.lang.management.ManagementFactory
            .getGarbageCollectorMXBeans()
            .joinToString(" + ") { it.name }

    /** The word the matrix groups by: `Serial`, `Parallel`, `G1`, `Z`. */
    private fun collectorTag(): String =
        when {
            collectorNames().contains("MarkSweepCompact") -> "Serial"
            collectorNames().contains("PS ") -> "Parallel"
            collectorNames().startsWith("G1") -> "G1"
            collectorNames().contains("Z") -> "Z"
            else -> collectorNames().substringBefore(' ')
        }

    /** Whether the collector does its marking beside the application rather than instead of it. */
    private fun isConcurrent(): Boolean = collectorTag() == "Z" || collectorTag() == "Shenandoah"

    private fun usedHeap(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(120)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    // --- plumbing ---------------------------------------------------------------------------

    private fun drain(
        socket: SocketChannel,
        buffer: ByteBuffer,
        out: FileChannel,
    ) {
        while (true) {
            buffer.clear()
            if (socket.read(buffer) < 0) break
            buffer.flip()
            while (buffer.hasRemaining()) out.write(buffer)
        }
        out.force(true)
    }

    /** Runs [send] against a reader that discards, and times only the sending thread. */
    private fun overLoopback(
        name: String,
        bytes: Long,
        send: (SocketChannel) -> Unit,
    ): Measurement {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            val reader =
                thread(name = "reader") {
                    server.accept().use { accepted ->
                        val sink = ByteBuffer.allocateDirect(256 * KIB.toInt())
                        while (true) {
                            sink.clear()
                            if (accepted.read(sink) < 0) break
                        }
                    }
                }
            SocketChannel.open(server.localAddress as InetSocketAddress).use { socket ->
                val measured = Measurement.of(name, bytes) { send(socket) }
                socket.shutdownOutput()
                reader.join()
                return measured
            }
        }
    }

    /** Runs [receive] on this thread while another sends [bytes] at it. */
    private fun intoFile(
        name: String,
        bytes: Long,
        target: Path,
        receive: (SocketChannel, FileChannel) -> Unit,
    ): Measurement {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            val sender =
                thread(name = "sender") {
                    SocketChannel.open(server.localAddress as InetSocketAddress).use { socket ->
                        val source = ByteBuffer.allocateDirect(256 * KIB.toInt())
                        while (source.hasRemaining()) source.put(0x5A)
                        var sent = 0L
                        while (sent < bytes) {
                            source.clear()
                            source.limit(minOf(source.capacity().toLong(), bytes - sent).toInt())
                            while (source.hasRemaining()) sent += socket.write(source)
                        }
                    }
                }
            server.accept().use { accepted ->
                FileChannel
                    .open(
                        target,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    ).use { out ->
                        val measured = Measurement.of(name, bytes) { receive(accepted, out) }
                        sender.join()
                        return measured
                    }
            }
        }
    }

    private fun fill(
        path: Path,
        bytes: Long,
    ): Path {
        FileChannel
            .open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            .use { out ->
                val buffer = ByteBuffer.allocateDirect(256 * KIB.toInt())
                while (buffer.hasRemaining()) buffer.put(0x42)
                var written = 0L
                while (written < bytes) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), bytes - written).toInt())
                    while (buffer.hasRemaining()) written += out.write(buffer)
                }
                out.force(true)
            }
        return path
    }
}

private object ManagementRuntime {
    /** What was asked for, as written on the command line — `maxMemory()` is what was granted. */
    fun heapArgument(): String =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .inputArguments
            .lastOrNull { it.startsWith("-Xmx") }
            ?.removePrefix("-Xmx") ?: "(default)"

    fun heapArgumentBytes(): Long {
        val written = heapArgument()
        val digits = written.takeWhile { it.isDigit() }.toLongOrNull() ?: return 0
        return when (written.last().lowercaseChar()) {
            'k' -> digits * 1024
            'm' -> digits * 1024 * 1024
            'g' -> digits * 1024 * 1024 * 1024
            else -> digits
        }
    }

    fun arguments(): String =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .inputArguments
            .filter { it.startsWith("-X") }
            .joinToString(" ")
            .ifEmpty { "(default)" }
}

/**
 * A thread that does nothing but sleep, and reports how badly that went.
 *
 * The point of measuring a pause this way is that it needs to know nothing about collectors. A
 * collector reports the phases it has names for; a request waits for every safepoint there is,
 * including the ones that belong to no collector at all. What this records is the second thing,
 * and it is the number a timeout is set against.
 *
 * The buffer is preallocated and never grows, because a recorder that allocates while recording
 * takes part in what it is recording. It is also part of the live set the collector has to trace —
 * five megabytes of it, identically in every variant, which is why it is stated rather than
 * hidden: at the smallest heap here that is two percent of the index.
 */
private class Hiccups(
    capacity: Int = 600_000,
) : Runnable {
    private val samples = LongArray(capacity)

    @Volatile
    private var running = true

    @Volatile
    private var count = 0
    private val thread = Thread(this, "hiccups").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
        thread.join(1_000)
    }

    /** Where the record stands now, so a window can be asked about later. */
    fun mark(): Int = count

    /** Whether the buffer filled up, which would make every percentile below a partial answer. */
    fun saturated(): Boolean = count >= samples.size

    override fun run() {
        while (running) {
            val before = System.nanoTime()
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                return
            }
            val over = System.nanoTime() - before - 1_000_000
            val at = count
            if (at < samples.size) {
                samples[at] = if (over > 0) over else 0
                count = at + 1
            }
        }
    }

    fun max(
        from: Int,
        to: Int,
    ): Long {
        var top = 0L
        for (i in from until minOf(to, count)) top = maxOf(top, samples[i])
        return top
    }

    fun percentile(
        from: Int,
        to: Int,
        p: Double,
    ): Long {
        val window = samples.copyOfRange(from, minOf(to, count).coerceAtLeast(from))
        if (window.isEmpty()) return 0
        window.sort()
        return window[((p / 100.0) * (window.size - 1)).toInt().coerceIn(0, window.size - 1)]
    }
}

/**
 * What the collector says about itself, which is the other half of the answer and not the whole
 * of it.
 *
 * Read through the notification rather than through the cumulative counters, because a total
 * divided by a count is an average and the question here is about the worst one. The durations are
 * the collector's own: G1 reports the end of a concurrent cycle as a "major" collection whose
 * duration is the cycle, not the pause, so a number from here is never published as a pause
 * without [Hiccups] beside it.
 */
private class GcLog {
    private val minor = ArrayList<Long>()
    private val major = ArrayList<Long>()
    private val named = LinkedHashMap<String, Int>()
    private val installed =
        ArrayList<Pair<javax.management.NotificationEmitter, javax.management.NotificationListener>>()

    fun install() {
        for (bean in java.lang.management.ManagementFactory
            .getGarbageCollectorMXBeans()) {
            if (bean !is javax.management.NotificationEmitter) continue
            val listener =
                javax.management.NotificationListener { notification, _ ->
                    if (notification.type ==
                        com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                    ) {
                        val info =
                            com.sun.management.GarbageCollectionNotificationInfo
                                .from(notification.userData as javax.management.openmbean.CompositeData)
                        val into = if (info.gcAction.contains("major")) major else minor
                        synchronized(into) { into.add(info.gcInfo.duration) }
                        synchronized(named) {
                            val label = "${info.gcName}/${info.gcAction.removePrefix("end of ")}"
                            named[label] = (named[label] ?: 0) + 1
                        }
                    }
                }
            bean.addNotificationListener(listener, null, null)
            installed += bean to listener
        }
    }

    fun uninstall() {
        for ((bean, listener) in installed) runCatching { bean.removeNotificationListener(listener) }
    }

    /**
     * Every collector bean with what it reported, because the classification above is a guess made
     * from a word in a string. `PS Scavenge` calling itself a minor collection and `PS MarkSweep` a
     * major one is a convention, not an interface, and a table that said "139 major collections"
     * without saying which bean said so would be unfalsifiable.
     */
    fun byName(): String =
        synchronized(named) {
            if (named.isEmpty()) {
                "(nothing collected)"
            } else {
                named.entries.joinToString(
                    ", ",
                ) { "${it.key} x${it.value}" }
            }
        }

    fun minorCount(): Int = synchronized(minor) { minor.size }

    fun majorCount(): Int = synchronized(major) { major.size }

    fun minorMillis(since: Int): Double = synchronized(minor) { minor.drop(since).sum().toDouble() }

    fun majorMillis(since: Int): Double = synchronized(major) { major.drop(since).sum().toDouble() }
}

/**
 * Resident memory, because that is the axis ZGC is decided on (M-153).
 *
 * A collector that buys its pause with footprint is the reverse of every other trade in this
 * project, and `-Xmx` says nothing about it: the question is what the cgroup sees, and the cgroup
 * sees this.
 */
private object Rss {
    fun current(): Long = field("VmRSS")

    fun peak(): Long = field("VmHWM")

    private fun field(name: String): Long =
        runCatching {
            java.nio.file.Files
                .readAllLines(
                    java.nio.file.Path
                        .of("/proc/self/status"),
                ).first { it.startsWith("$name:") }
                .filter { it.isDigit() }
                .toLong() * 1024
        }.getOrDefault(0L)
}
