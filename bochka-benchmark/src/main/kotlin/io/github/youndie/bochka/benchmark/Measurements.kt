package io.github.youndie.bochka.benchmark

import io.github.youndie.bochka.core.IndexRecord
import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.core.RecordLog
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

        for (keyLength in listOf(40, 100)) {
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
    fun arguments(): String =
        java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .inputArguments
            .filter { it.startsWith("-X") }
            .joinToString(" ")
            .ifEmpty { "(default)" }
}
