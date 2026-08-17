package io.github.youndie.bochka.core

import java.io.Closeable
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Objects on a disk, keys in an index, and a written order that decides what a crash can leave.
 *
 * ## The order (Р12)
 *
 * The object file is written, `fsync`ed and renamed into place **before** the index hears about
 * it. That order has exactly one bad outcome — an orphan: a file nobody points at, which
 * [sweepOrphans] collects. The other order has a worse one: an index entry pointing at a file that
 * is not there, which is a `500` on a key the server itself said exists. Between two kinds of
 * inconsistency, this picks the harmless one rather than pretending there is a third option; there
 * is no transaction across a filesystem and a log.
 *
 * ## The name on disk
 *
 * A UUID under two levels of directory, never anything derived from the key (Р2). The key can hold
 * bytes no filesystem will keep — a case-insensitive volume folds `Photo.JPG` into `photo.jpg`, a
 * normalising one folds the two spellings of `café.txt`, `NAME_MAX` is 255 against a key of 1024
 * bytes, and `a/b` cannot be both a file and a directory. Deriving the name means meeting all of
 * those; not deriving it means none of them exist.
 *
 * ## What is in memory
 *
 * Every key, ordered — the bitcask shape (Р1). That is a published ceiling rather than an
 * oversight: the index costs memory per object, and the number belongs in the README once it has
 * been measured (M-64). What it buys is a listing that is a walk of a sorted structure rather than
 * a scan of a disk.
 */
class ObjectStore(
    private val root: Path,
    private val durability: Durability = Durability.FSYNC,
) : Closeable {
    /**
     * Whether a write is on the disk before it is acknowledged.
     *
     * [NONE] exists for tests and for measuring, and it is a different product: without the
     * barrier an acknowledged object lives in the page cache, and a power cut takes it. The
     * neighbouring broker learned to state the flush mode next to every number, because the
     * difference between the two is four orders of magnitude and a number without it is not
     * imprecise, it is about something else.
     */
    enum class Durability { FSYNC, NONE }

    data class Stored(
        val fileId: String,
        val size: Long,
        val eTag: String,
        val lastModified: Instant,
        val contentType: String?,
    )

    private data class Located(
        val bucket: String,
        val key: ObjectKey,
    ) : Comparable<Located> {
        override fun compareTo(other: Located): Int {
            val byBucket = bucket.compareTo(other.bucket)
            return if (byBucket != 0) byBucket else key.compareTo(other.key)
        }
    }

    // The directory is made here rather than in `init`, and the order is load-bearing: property
    // initialisers run in declaration order, before any `init` block, so a log opened first would
    // try to create its file in a directory that does not exist yet. Every test had one already,
    // which is why only starting the server for real found it.
    private val data = root.resolve("data").also { Files.createDirectories(it) }
    private val log = RecordLog(root.resolve("index.log"))

    // Ordered, because a listing is defined by that order (§1.5) and rebuilding it per request
    // would make every listing a sort of the whole bucket.
    private val objects = ConcurrentSkipListMap<Located, Stored>()
    private val buckets = ConcurrentHashMap.newKeySet<String>()

    /** One writer at a time on the log: its records must land in the order they were decided. */
    private val writing = ReentrantLock()

    /** What the log said when it was opened. Worth printing at startup rather than discarding. */
    val recovery: RecordLog.Recovery

    init {
        recovery =
            log.recover { payload ->
                when (val record = IndexRecord.decode(payload)) {
                    is IndexRecord.BucketCreated -> {
                        buckets.add(record.bucket)
                    }

                    is IndexRecord.BucketDeleted -> {
                        buckets.remove(record.bucket)
                    }

                    is IndexRecord.Deleted -> {
                        objects.remove(Located(record.bucket, record.key))
                    }

                    is IndexRecord.Put -> {
                        objects[Located(record.bucket, record.key)] =
                            Stored(
                                fileId = record.fileId,
                                size = record.size,
                                eTag = record.eTag,
                                lastModified = Instant.ofEpochMilli(record.lastModifiedMillis),
                                contentType = record.contentType,
                            )
                    }
                }
            }
    }

    val objectCount: Int get() = objects.size

    fun createBucket(name: String): Boolean {
        if (!buckets.add(name)) return false
        write(IndexRecord.BucketCreated(name))
        return true
    }

    fun hasBucket(name: String): Boolean = name in buckets

    fun bucketNames(): List<String> = buckets.sorted()

    fun deleteBucket(name: String): Boolean {
        if (firstKeyOf(name) != null) return false
        if (!buckets.remove(name)) return false
        write(IndexRecord.BucketDeleted(name))
        return true
    }

    /**
     * Writes an object and only then tells the index about it.
     *
     * [write] suspends because the bytes come off a socket; a blocking bridge would park a
     * dispatcher thread for the length of an upload, and a handful of slow clients would be
     * indistinguishable from a hung server.
     */
    suspend fun put(
        bucket: String,
        key: ObjectKey,
        contentType: String?,
        write: suspend (OutputStream) -> Unit,
    ): Stored {
        val fileId = UUID.randomUUID().toString()
        val target = pathOf(fileId)
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.partial")

        val digest = MessageDigest.getInstance("MD5")
        var size = 0L
        FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val sink =
                object : OutputStream() {
                    private val one = ByteArray(1)

                    override fun write(b: Int) {
                        one[0] = b.toByte()
                        write(one, 0, 1)
                    }

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        var written = 0
                        val buffer = java.nio.ByteBuffer.wrap(b, off, len)
                        while (buffer.hasRemaining()) written += channel.write(buffer)
                        digest.update(b, off, len)
                        size += len
                    }
                }
            write(sink)
            // The barrier that makes the rename mean something. Without it the file exists and its
            // contents do not, and the index would be pointing at a hole.
            if (durability == Durability.FSYNC) channel.force(true)
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)

        val stored =
            Stored(
                fileId = fileId,
                size = size,
                eTag = "\"" + digest.digest().joinToString("") { "%02x".format(it) } + "\"",
                lastModified = Instant.now(),
                contentType = contentType,
            )

        // Only now does the object exist as far as anybody asking is concerned.
        val previous = objects.put(Located(bucket, key), stored)
        this.write(
            IndexRecord.Put(
                bucket = bucket,
                key = key,
                fileId = fileId,
                size = size,
                eTag = stored.eTag,
                lastModifiedMillis = stored.lastModified.toEpochMilli(),
                contentType = contentType,
            ),
        )

        // The replaced file goes after the index stops pointing at it, and a reader that opened it
        // before that keeps reading — the descriptor outlives the name.
        if (previous != null) Files.deleteIfExists(pathOf(previous.fileId))
        return stored
    }

    fun get(
        bucket: String,
        key: ObjectKey,
    ): Stored? = objects[Located(bucket, key)]

    /** Where the bytes are, for a reader that wants the file rather than a copy of it (M-59). */
    fun pathOf(stored: Stored): Path = pathOf(stored.fileId)

    fun delete(
        bucket: String,
        key: ObjectKey,
    ): Boolean {
        val removed = objects.remove(Located(bucket, key)) ?: return false
        write(IndexRecord.Deleted(bucket, key))
        Files.deleteIfExists(pathOf(removed.fileId))
        return true
    }

    /** Keys of one bucket from [after] onwards, in the order a listing is defined by. */
    fun list(
        bucket: String,
        prefix: ByteArray,
        limit: Int,
        after: ObjectKey? = null,
    ): List<Pair<ObjectKey, Stored>> {
        val from = Located(bucket, after ?: ObjectKey(prefix))
        val out = ArrayList<Pair<ObjectKey, Stored>>(minOf(limit, 1000))
        // tailMap and not a filter over everything: the point of keeping the index ordered is that
        // a listing touches the keys it returns and stops.
        for ((located, stored) in objects.tailMap(from, after == null)) {
            if (located.bucket != bucket) break
            val bytes = located.key.toByteArray()
            if (!bytes.startsWith(prefix)) break
            out.add(located.key to stored)
            if (out.size == limit) break
        }
        return out
    }

    /**
     * Deletes object files nobody points at.
     *
     * The other half of the write order: a crash between the file and its index record leaves the
     * file, and nothing will ever mention it again. [olderThanMillis] keeps the sweep off files
     * that are being written right now — an upload in flight looks exactly like an orphan.
     */
    fun sweepOrphans(olderThanMillis: Long = 60 * 60 * 1000): Int {
        val referenced = objects.values.mapTo(HashSet()) { it.fileId }
        val cutoff = System.currentTimeMillis() - olderThanMillis
        var removed = 0
        Files.walk(data).use { walk ->
            for (path in walk.filter(Files::isRegularFile)) {
                val name = path.fileName.toString().removeSuffix(".partial")
                if (name in referenced) continue
                if (Files.getLastModifiedTime(path).toMillis() > cutoff) continue
                Files.deleteIfExists(path)
                removed++
            }
        }
        return removed
    }

    override fun close() = log.close()

    private fun write(record: IndexRecord) {
        writing.withLock {
            log.append(IndexRecord.encode(record))
            if (durability == Durability.FSYNC) log.force()
        }
    }

    private fun firstKeyOf(bucket: String): ObjectKey? {
        val entry = objects.ceilingEntry(Located(bucket, ObjectKey(ByteArray(0)))) ?: return null
        return if (entry.key.bucket == bucket) entry.key.key else null
    }

    /** `data/ab/cd/<uuid>` — two levels so that no directory ever holds a million entries. */
    private fun pathOf(fileId: String): Path =
        data.resolve(fileId.substring(0, 2)).resolve(fileId.substring(2, 4)).resolve(fileId)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (prefix.size > size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
