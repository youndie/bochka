package io.github.youndie.bochka.core

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Arrays
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
        val metadata: Metadata,
    )

    /**
     * Where the bytes of an object go.
     *
     * The same shape as every other byte path in the server — [HttpHandler.RequestBody.forEach],
     * the `aws-chunked` decoder, the HTTP chunk decoder — and that is the point of it having a name
     * (M-76). It was a `java.io.OutputStream` first, which meant the one place bytes are written
     * had a second description of the same thing, with a hand-rolled one-byte array in it for a
     * `write(Int)` nobody calls.
     */
    fun interface Sink {
        fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        )
    }

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
                                metadata = record.metadata,
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
        metadata: Metadata,
        write: suspend (Sink) -> Unit,
    ): Stored = commit(bucket, key, metadata, stage(write))

    /**
     * An object's bytes on the disk, not yet anybody's.
     *
     * Written, checksummed and `fsync`ed, and pointed at by nothing — so a crash here leaves an
     * orphan for [sweepOrphans] and nothing else. It becomes an object at [commit] and disappears
     * at [discard].
     *
     * The two steps are apart because the decision to keep the bytes can depend on the bytes: a
     * `Content-MD5` that turns out not to describe them, a chunk signature that does not verify.
     * Deleting the key afterwards would be the wrong repair — it destroys the object that was
     * already there, so a refused overwrite would cost the client the version it had.
     */
    data class Staged(
        val fileId: String,
        val size: Long,
        val eTag: String,
    )

    suspend fun stage(write: suspend (Sink) -> Unit): Staged {
        val fileId = UUID.randomUUID().toString()
        val target = pathOf(fileId)
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.partial")

        val digest = MessageDigest.getInstance("MD5")
        var size = 0L
        FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            write { bytes, offset, length ->
                val buffer = ByteBuffer.wrap(bytes, offset, length)
                while (buffer.hasRemaining()) channel.write(buffer)
                digest.update(bytes, offset, length)
                size += length
            }
            // The barrier that makes the rename mean something. Without it the file exists and its
            // contents do not, and the index would be pointing at a hole.
            if (durability == Durability.FSYNC) channel.force(true)
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)

        return Staged(
            fileId = fileId,
            size = size,
            eTag = "\"" + digest.digest().joinToString("") { "%02x".format(it) } + "\"",
        )
    }

    /** Makes [staged] the object at [key]. Everything before this point is invisible. */
    fun commit(
        bucket: String,
        key: ObjectKey,
        metadata: Metadata,
        staged: Staged,
    ): Stored {
        val stored =
            Stored(
                fileId = staged.fileId,
                size = staged.size,
                eTag = staged.eTag,
                lastModified = Instant.now(),
                metadata = metadata,
            )

        // Only now does the object exist as far as anybody asking is concerned.
        val previous = objects.put(Located(bucket, key), stored)
        write(
            IndexRecord.Put(
                bucket = bucket,
                key = key,
                fileId = stored.fileId,
                size = stored.size,
                eTag = stored.eTag,
                lastModifiedMillis = stored.lastModified.toEpochMilli(),
                metadata = metadata,
            ),
        )

        // The replaced file goes after the index stops pointing at it, and a reader that opened it
        // before that keeps reading — the descriptor outlives the name (Р2, M-44).
        if (previous != null) Files.deleteIfExists(pathOf(previous.fileId))
        return stored
    }

    /** Throws away bytes that were written and turned out not to be wanted. */
    fun discard(staged: Staged) {
        Files.deleteIfExists(pathOf(staged.fileId))
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

    /**
     * One page of a listing.
     *
     * [nextAfter] is what a continuation token carries: the last thing on this page, whether that
     * was a key or a rolled-up prefix. It is `null` when the page is the last one.
     */
    data class Page(
        val keys: List<Pair<ObjectKey, Stored>>,
        val commonPrefixes: List<ByteArray>,
        val isTruncated: Boolean,
        val nextAfter: ByteArray?,
        /**
         * How many index entries the walk actually read.
         *
         * Reported because it is the difference between a listing that jumps over a group and one
         * that walks through it, and the two are otherwise indistinguishable from the outside:
         * same page, same order, same answer, a hundred thousand times the work. A test asserts on
         * this rather than on a clock, because a clock on a shared machine measures the machine.
         */
        val entriesRead: Int = keys.size + commonPrefixes.size,
    ) {
        /** What `MaxKeys` bounds and `KeyCount` reports: a rolled-up prefix counts as one. */
        val size: Int get() = keys.size + commonPrefixes.size
    }

    /**
     * A page of one bucket, in the order a listing is defined by (§1.5).
     *
     * ## The jump
     *
     * With a [delimiter], every key holding it after [prefix] collapses into one `CommonPrefixes`
     * entry. The naive way is to walk the whole group and discard it; this seeks past it instead —
     * having emitted `photos/2019/`, the next thing read is the first key at or after the smallest
     * string greater than everything under it. A bucket with a million objects in one directory
     * costs one entry to list, not a million (§1.4.3). It is the reason the index is an ordered
     * structure rather than a hash.
     *
     * ## Resuming
     *
     * [startAfter] is exclusive, and a group is skipped when it is not greater than it. That second
     * half is what makes pagination terminate: a page truncated on a rolled-up prefix carries that
     * prefix as its marker, and the prefix sorts **before** every key under it — so resuming
     * without the rule would roll the same group up again, for ever.
     */
    fun list(
        bucket: String,
        prefix: ByteArray = ByteArray(0),
        delimiter: ByteArray? = null,
        startAfter: ByteArray? = null,
        maxKeys: Int = 1000,
    ): Page {
        // `max-keys=0` is a question with an answer — no keys, and the listing is not truncated,
        // which is not what a loop that stops at zero would say.
        if (maxKeys <= 0) return Page(emptyList(), emptyList(), isTruncated = false, nextAfter = null)

        val keys = ArrayList<Pair<ObjectKey, Stored>>(minOf(maxKeys, 1000))
        val groups = ArrayList<ByteArray>()
        var truncated = false
        var last: ByteArray? = null
        var read = 0
        var cursor: ByteArray = startAfter?.let(::justAfter) ?: prefix

        while (true) {
            val entry = objects.ceilingEntry(Located(bucket, ObjectKey(cursor))) ?: break
            read++
            if (entry.key.bucket != bucket) break
            val bytes = entry.key.key.toByteArray()
            if (!bytes.startsWith(prefix)) break

            val group = delimiter?.let { groupOf(bytes, prefix.size, it) }
            if (group != null) {
                if (startAfter != null && Arrays.compareUnsigned(group, startAfter) <= 0) {
                    cursor = past(group) ?: break
                    continue
                }
                if (keys.size + groups.size == maxKeys) {
                    truncated = true
                    break
                }
                groups.add(group)
                last = group
                cursor = past(group) ?: break
                continue
            }

            if (keys.size + groups.size == maxKeys) {
                truncated = true
                break
            }
            keys.add(entry.key.key to entry.value)
            last = bytes
            cursor = justAfter(bytes)
        }

        return Page(keys, groups, truncated, if (truncated) last else null, read)
    }

    /** The smallest byte string strictly greater than [value]: itself with a zero byte appended. */
    private fun justAfter(value: ByteArray): ByteArray = value + 0

    /**
     * The smallest byte string greater than **everything** starting with [group] — the last byte
     * incremented, carrying.
     *
     * `null` when there is no such string, which happens only for a group of nothing but `0xFF`
     * bytes: everything after it is under it, so the listing is over.
     */
    private fun past(group: ByteArray): ByteArray? {
        val out = group.copyOf()
        for (i in out.indices.reversed()) {
            if (out[i] != 0xFF.toByte()) {
                out[i] = (out[i] + 1).toByte()
                return out.copyOf(i + 1)
            }
        }
        return null
    }

    /** `photos/2019/a.jpg` under prefix `photos/` and delimiter `/` groups as `photos/2019/`. */
    private fun groupOf(
        key: ByteArray,
        from: Int,
        delimiter: ByteArray,
    ): ByteArray? {
        if (delimiter.isEmpty()) return null
        var i = from
        outer@ while (i <= key.size - delimiter.size) {
            for (j in delimiter.indices) {
                if (key[i + j] != delimiter[j]) {
                    i++
                    continue@outer
                }
            }
            return key.copyOf(i + delimiter.size)
        }
        return null
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
