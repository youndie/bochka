package io.github.youndie.bochka.benchmark

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

                else -> {
                    serve(dir, bytes)
                    println()
                    write(dir, bytes)
                    println()
                    assemble(dir, bytes)
                }
            }
        } finally {
            Files.list(dir).use { list -> list.forEach { runCatching { Files.deleteIfExists(it) } } }
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
