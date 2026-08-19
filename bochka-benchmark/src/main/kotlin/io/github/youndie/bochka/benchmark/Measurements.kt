package io.github.youndie.bochka.benchmark

import io.github.youndie.bochka.core.IndexRecord
import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.core.RecordLog
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
     * Открытый вопрос 2: чего на самом деле стоит мелкий объект, если он — отдельный файл.
     *
     * Р2 объявляет «мелкие объекты не оптимизируются» и называет цену прозой: инода на объект
     * и минимальный блок файловой системы. Это её половина вопроса, и она измерима точно —
     * без всяких предположений о том, что люди хранят.
     *
     * Меряется **занятое место, а не размер**: `st_blocks` из `unix:blocks`, умноженное на 512.
     * Логический размер файла в 1 байт равен одному байту и не говорит ничего; занятое — целый
     * блок. Разница между ними и есть предмет вопроса.
     *
     * Объекты кладутся через настоящий [ObjectStore], а не `Files.write`: раскладка по двум
     * уровням каталогов — часть цены, каталоги тоже занимают блоки.
     */
    private fun small(dir: Path) {
        println("== Открытый вопрос 2: что стоит мелкий объект ==")
        val counts = System.getenv("BOCHKA_MEASURE_SMALL_COUNT")?.toIntOrNull() ?: 20_000

        println("  %-12s %12s %12s %12s %10s".format("размер", "логически", "на диске", "накладные", "во сколько"))
        for (size in listOf(1, 512, 4 * KIB.toInt(), 64 * KIB.toInt())) {
            val home = Files.createDirectories(dir.resolve("small-$size"))
            ObjectStore(home, ObjectStore.Durability.NONE).use { store ->
                store.createBucket("photos")
                val payload = ByteArray(size)
                // `runBlocking`, потому что путь записи suspend: он кормится из сокета там, где
                // он настоящий. Здесь сокета нет, и это ровно тот случай, для которого `runBlocking`
                // и существует — граница между измеряющим кодом и тем, что он измеряет.
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

        // Потолок — вторая половина ответа, и он от распределения размеров не зависит вовсе.
        val ceiling = ObjectStore.ceilingForHeap()
        println()
        println("  потолок этой кучи: $ceiling объектов")
        println("  то есть вся мыслимая экономия от упаковки ограничена сверху этим числом,")
        println("  сколько бы мелких объектов ни хранил потребитель")
    }

    /**
     * Сколько **занято**, а не сколько записано.
     *
     * Файл в один байт имеет размер один байт и занимает целый блок; вопрос 2 ровно про эту
     * разницу, и `Files.size` на неё не отвечает. Каталоги считаются тоже — раскладка по двум
     * уровням это часть цены.
     *
     * Через `du`, а не через NIO: у JDK в наборе `unix:` **нет** атрибута `blocks`
     * (`IllegalArgumentException: 'blocks' not recognized`), то есть занятое место из
     * `Files.readAttributes` не достать вовсе. `du` считает именно это, а замер и так имеет смысл
     * только на Linux с той файловой системой, про которую вопрос.
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
        check(process.waitFor() == 0) { "du отказался считать $root: $output" }
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
            val spread = nanos.last().toDouble() / nanos.first()
            println(
                "%-10s %8d ns per read  spread %.2f%s  (checksum %d)".format(
                    bucket,
                    nanos[nanos.size / 2],
                    spread,
                    if (spread > 1.3) "  ← too noisy to conclude from" else "",
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
