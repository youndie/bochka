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
    /**
     * The published ceiling: how many objects this store will manage.
     *
     * Not a limit invented to be safe — a consequence of the shape. Every key lives in memory
     * (Р1), so the number of objects is bounded by the heap whether anybody says so or not, and
     * the only question is whether it is a stated characteristic or a slow slide into swap that
     * looks like the disk being slow. It is stated, and it is enforced twice: the store refuses to
     * **open** a log that is already over it, and refuses a new key once it is reached.
     *
     * The default comes from the measurement in `docs/measurements.md`, not from taste.
     */
    val maxObjects: Int = ceilingForHeap(),
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
        /**
         * What the object was assembled from, when it was assembled from anything.
         *
         * Empty for an ordinary upload, and one entry per part for a completed multipart one. The
         * bytes are joined into a single file at completion (open question 3), so this is the only
         * thing that remembers the seams — and three operations need them: `partNumber` on a `GET`,
         * `ObjectParts` in `GetObjectAttributes`, and the checksum of a multipart object, which is
         * the checksum of its parts' checksums rather than of its bytes.
         */
        val parts: List<PartSummary> = emptyList(),
    )

    /**
     * One part of a completed object: how long it was and what it hashed to.
     *
     * Deliberately not the [Part] of an upload in flight — that one has a file of its own and this
     * one does not, because the bytes are in the assembled object. Sharing a type would invite
     * code that asks a finished object for a part's `fileId`.
     */
    data class PartSummary(
        val number: Int,
        val size: Long,
        val eTag: String,
        val checksum: Metadata.Checksum?,
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
    private val logPath = root.resolve("index.log")

    // Reassigned by [compact], which is why it is not a `val`: the log the store appends to after
    // a compaction is a different file, and the old one is gone by then.
    private var log = RecordLog(logPath)

    // Ordered, because a listing is defined by that order (§1.5) and rebuilding it per request
    // would make every listing a sort of the whole bucket.
    private val objects = ConcurrentSkipListMap<Located, Stored>()
    private val buckets = ConcurrentHashMap.newKeySet<String>()

    /** One writer at a time on the log: its records must land in the order they were decided. */
    private val writing = ReentrantLock()

    // Declared here rather than beside the rest of the multipart code because property
    // initialisers run in declaration order and `init` replays the log into this one.
    private val uploads = ConcurrentHashMap<String, UploadState>()

    /**
     * Records appended since the log last held only live ones. Drives [compactIfWorthwhile].
     *
     * Up here for the same reason as [uploads]: `init` sets it from what recovery read, and a
     * property declared after `init` does not exist yet when `init` runs.
     */
    private val recordsSinceCompaction =
        java.util.concurrent.atomic
            .AtomicLong()

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
                                parts = record.parts,
                            )
                    }

                    is IndexRecord.UploadStarted -> {
                        uploads[record.uploadId] =
                            UploadState(
                                Upload(
                                    id = record.uploadId,
                                    bucket = record.bucket,
                                    key = record.key,
                                    metadata = record.metadata,
                                    startedAt = Instant.ofEpochMilli(record.startedAtMillis),
                                ),
                            )
                    }

                    is IndexRecord.UploadPart -> {
                        // An upload the log never opened cannot have parts; a record for one is a
                        // torn tail rather than something to invent an upload from.
                        uploads[record.uploadId]?.parts?.put(
                            record.number,
                            Part(
                                number = record.number,
                                fileId = record.fileId,
                                size = record.size,
                                eTag = record.eTag,
                                lastModified = Instant.ofEpochMilli(record.lastModifiedMillis),
                            ),
                        )
                    }

                    is IndexRecord.UploadEnded -> {
                        uploads.remove(record.uploadId)
                    }
                }
            }

        // A refusal at startup rather than degradation into swap (Risk 7). A store that opens and
        // then thrashes looks like a slow disk, and the person looking at it has no reason to
        // suspect the index; a store that will not open says exactly what is wrong.
        recordsSinceCompaction.set(recovery.records)

        if (objects.size > maxObjects) {
            throw CeilingExceeded(
                objects.size,
                maxObjects,
                "the index holds ${objects.size} objects and this heap allows $maxObjects " +
                    "($BYTES_PER_OBJECT bytes each, half of ${heapMiB()} MiB). " +
                    "Start with a larger -Xmx, or move objects off this node.",
            )
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

    /**
     * What has to be true of the key for a write to happen at all.
     *
     * `If-Match` and `If-None-Match` on a write, and the reason they live down here rather than in
     * the request layer: a handler that reads the object, compares the tag and then writes has a
     * window between the three, and two clients using the header to not overwrite each other both
     * pass their check and one loses. The comparison and the write are one step or the header is
     * a race with a promise attached.
     */
    sealed interface Precondition {
        data object None : Precondition

        /** The object must exist, and its `ETag` must be one of these. `*` means "must exist". */
        data class IfMatch(
            val eTags: List<String>,
        ) : Precondition

        /** `*` means "must not exist"; a tag means "must not be this one". */
        data class IfNoneMatch(
            val eTags: List<String>,
        ) : Precondition
    }

    class PreconditionFailed(
        override val message: String,
    ) : RuntimeException(message)

    private fun Precondition.holdsFor(existing: Stored?): Boolean =
        when (this) {
            is Precondition.None -> {
                true
            }

            is Precondition.IfMatch -> {
                existing != null && ("*" in eTags || eTags.any { it.trim('"') == existing.eTag.trim('"') })
            }

            is Precondition.IfNoneMatch -> {
                existing == null || ("*" !in eTags && eTags.none { it.trim('"') == existing.eTag.trim('"') })
            }
        }

    /** Makes [staged] the object at [key]. Everything before this point is invisible. */
    fun commit(
        bucket: String,
        key: ObjectKey,
        metadata: Metadata,
        staged: Staged,
        precondition: Precondition = Precondition.None,
        parts: List<PartSummary> = emptyList(),
    ): Stored {
        val stored =
            Stored(
                fileId = staged.fileId,
                size = staged.size,
                eTag = staged.eTag,
                lastModified = Instant.now(),
                metadata = metadata,
                parts = parts,
            )

        // The ceiling is checked against a key that is not already there: overwriting an object
        // costs no index entry, and refusing it would make a full store unable to shrink.
        val located = Located(bucket, key)
        if (objects.size >= maxObjects && !objects.containsKey(located)) {
            throw CeilingExceeded(
                objects.size,
                maxObjects,
                "the index is at its ceiling of $maxObjects objects",
            )
        }

        // Only now does the object exist as far as anybody asking is concerned. `compute` and not
        // `put`, because that is what makes the precondition and the write one step: the map holds
        // the key while the check runs, so two writers with `If-None-Match: *` cannot both pass.
        //
        // The replaced value is captured **inside** the lambda: `compute` returns the new value,
        // and deriving the old one from the return is how the file of a replaced object quietly
        // stopped being deleted. Only a test that looked at the disk noticed.
        var refused: String? = null
        var previous: Stored? = null
        objects.compute(located) { _, existing ->
            if (!precondition.holdsFor(existing)) {
                refused = "the object is ${existing?.eTag ?: "absent"}"
                existing
            } else {
                previous = existing
                stored
            }
        }
        refused?.let { throw PreconditionFailed(it) }
        write(
            IndexRecord.Put(
                bucket = bucket,
                key = key,
                fileId = stored.fileId,
                size = stored.size,
                eTag = stored.eTag,
                lastModifiedMillis = stored.lastModified.toEpochMilli(),
                metadata = metadata,
                parts = parts,
            ),
        )

        // The replaced file goes after the index stops pointing at it, and a reader that opened it
        // before that keeps reading — the descriptor outlives the name (Р2, M-44).
        previous?.let { Files.deleteIfExists(pathOf(it.fileId)) }
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

    /**
     * Copies an object inside the store, without the bytes leaving it.
     *
     * The bytes are copied rather than shared, and the alternative is worth naming: two keys could
     * point at one file, which costs nothing until the first delete and then needs a reference
     * count on every object for ever. A count that is wrong loses data silently — the file goes
     * while a key still names it — and this store's whole write order exists to make that
     * impossible. A copy costs a copy and is always right.
     *
     * `transferFrom` between two files, which is the direction of it that takes the fast path
     * (§1.6.2): the source is a real `FileChannelImpl` here, which is exactly what it is not on
     * the upload path.
     */
    fun copy(
        source: Stored,
        bucket: String,
        key: ObjectKey,
        metadata: Metadata,
    ): Stored {
        val fileId = UUID.randomUUID().toString()
        val target = pathOf(fileId)
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.partial")

        FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            FileChannel.open(pathOf(source.fileId), StandardOpenOption.READ).use { from ->
                var moved = 0L
                while (moved < source.size) {
                    val n = out.transferFrom(from, moved, source.size - moved)
                    if (n <= 0) throw java.io.EOFException("the source ended ${source.size - moved} bytes early")
                    moved += n
                }
            }
            if (durability == Durability.FSYNC) out.force(true)
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)

        // The ETag comes across rather than being recomputed: it describes the bytes, the bytes are
        // the same bytes, and recomputing an MD5 of five gigabytes to learn what is already known
        // would be the expensive way to get the same string.
        return commit(bucket, key, metadata, Staged(fileId, source.size, source.eTag))
    }

    /**
     * Stages a part from a stretch of another object.
     *
     * The bytes never leave the store, which is the whole point of the operation: a client
     * rewriting a five-gigabyte object copies the parts it keeps instead of downloading and
     * re-uploading them. The `ETag` is recomputed rather than carried across, unlike [copy] —
     * a slice of an object hashes to something the source's `ETag` says nothing about.
     */
    fun stagePartFrom(
        source: Stored,
        offset: Long,
        length: Long,
    ): Staged {
        require(offset >= 0 && length >= 0 && offset + length <= source.size) {
            "range $offset..${offset + length} is outside an object of ${source.size} bytes"
        }
        val fileId = UUID.randomUUID().toString()
        val target = pathOf(fileId)
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.partial")

        val digest = MessageDigest.getInstance("MD5")
        FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            FileChannel.open(pathOf(source.fileId), StandardOpenOption.READ).use { from ->
                // Read into a buffer rather than `transferFrom`, because the ETag has to be
                // computed from these bytes and a kernel-side copy never shows them to anybody.
                val buffer = ByteBuffer.allocate(256 * 1024)
                var moved = 0L
                while (moved < length) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), length - moved).toInt())
                    val read = from.read(buffer, offset + moved)
                    if (read <= 0) throw java.io.EOFException("the source ended ${length - moved} bytes early")
                    buffer.flip()
                    digest.update(buffer.duplicate())
                    while (buffer.hasRemaining()) out.write(buffer)
                    moved += read
                }
            }
            if (durability == Durability.FSYNC) out.force(true)
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)

        return Staged(fileId, length, "\"" + digest.digest().joinToString("") { "%02x".format(it) } + "\"")
    }

    /** Where the bytes are, for a reader that wants the file rather than a copy of it (M-59). */
    fun pathOf(stored: Stored): Path = pathOf(stored.fileId)

    fun delete(
        bucket: String,
        key: ObjectKey,
        precondition: Precondition = Precondition.None,
    ): Boolean {
        var refused: String? = null
        var removed: Stored? = null
        objects.compute(Located(bucket, key)) { _, existing ->
            when {
                existing == null -> {
                    null
                }

                !precondition.holdsFor(existing) -> {
                    refused = "the object is ${existing.eTag}"
                    existing
                }

                else -> {
                    removed = existing
                    null
                }
            }
        }
        refused?.let { throw PreconditionFailed(it) }
        val gone = removed ?: return false
        write(IndexRecord.Deleted(bucket, key))
        Files.deleteIfExists(pathOf(gone.fileId))
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

    // --- multipart upload (M7) ----------------------------------------------------------------

    /**
     * An upload in progress: a key that does not exist yet and the parts collected towards it.
     *
     * Kept in the index log like everything else, and for the same reason: a multipart upload of a
     * five-gigabyte object runs for minutes and a restart in the middle must not silently discard
     * the parts a client has already been told were accepted.
     */
    data class Upload(
        val id: String,
        val bucket: String,
        val key: ObjectKey,
        val metadata: Metadata,
        val startedAt: Instant,
    )

    data class Part(
        val number: Int,
        val fileId: String,
        val size: Long,
        val eTag: String,
        val lastModified: Instant,
        /** What the client stated about this part, kept so the finished object can answer for it. */
        val checksum: Metadata.Checksum? = null,
    )

    /** Why a completion cannot happen. The S3 layer turns each of these into a code and a status. */
    class CompletionRefused(
        val reason: Reason,
        override val message: String,
    ) : RuntimeException(message) {
        enum class Reason { NO_SUCH_UPLOAD, NO_PARTS, INVALID_PART, INVALID_PART_ORDER, ENTITY_TOO_SMALL }
    }

    private class UploadState(
        val upload: Upload,
    ) {
        val parts = ConcurrentSkipListMap<Int, Part>()
    }

    fun createUpload(
        bucket: String,
        key: ObjectKey,
        metadata: Metadata,
    ): Upload {
        val upload = Upload(UUID.randomUUID().toString(), bucket, key, metadata, Instant.now())
        uploads[upload.id] = UploadState(upload)
        write(
            IndexRecord.UploadStarted(
                bucket = bucket,
                key = key,
                uploadId = upload.id,
                startedAtMillis = upload.startedAt.toEpochMilli(),
                metadata = metadata,
            ),
        )
        return upload
    }

    fun upload(id: String): Upload? = uploads[id]?.upload

    fun uploads(bucket: String): List<Upload> =
        uploads.values
            .map { it.upload }
            .filter { it.bucket == bucket }
            .sortedWith(compareBy({ it.key }, { it.id }))

    fun parts(id: String): List<Part> = uploads[id]?.parts?.values?.toList() ?: emptyList()

    /**
     * Writes one part.
     *
     * A part with a number that is already there replaces it — that is S3's rule, and the file of
     * the old one goes only after the index stops pointing at it, exactly as for an object.
     */
    suspend fun putPart(
        uploadId: String,
        number: Int,
        write: suspend (Sink) -> Unit,
    ): Part = commitPart(uploadId, number, stage(write))

    /** The same, for bytes already staged — the request path checks them before keeping them. */
    fun commitPart(
        uploadId: String,
        number: Int,
        staged: Staged,
        checksum: Metadata.Checksum? = null,
    ): Part {
        val state = uploads[uploadId] ?: throw CompletionRefused(CompletionRefused.Reason.NO_SUCH_UPLOAD, uploadId)
        val part = Part(number, staged.fileId, staged.size, staged.eTag, Instant.now(), checksum)
        val previous = state.parts.put(number, part)
        this.write(
            IndexRecord.UploadPart(
                bucket = state.upload.bucket,
                uploadId = uploadId,
                number = number,
                fileId = staged.fileId,
                size = staged.size,
                eTag = staged.eTag,
                lastModifiedMillis = part.lastModified.toEpochMilli(),
            ),
        )
        if (previous != null) Files.deleteIfExists(pathOf(previous.fileId))
        return part
    }

    fun abortUpload(uploadId: String): Boolean {
        val state = uploads.remove(uploadId) ?: return false
        write(IndexRecord.UploadEnded(state.upload.bucket, uploadId))
        for (part in state.parts.values) Files.deleteIfExists(pathOf(part.fileId))
        return true
    }

    /**
     * Joins the parts into one object.
     *
     * Everything that can be refused is refused **before** a byte is copied: an upload that does
     * not exist, an empty list, parts out of order, a part whose `ETag` does not match, a part
     * below the minimum that is not the last. That ordering is why this server never needs S3's
     * other shape for this operation — the one where a `200 OK` carries an `<Error>` in its body
     * because the answer was not known when the response started (protocol-s3.md §4).
     *
     * The copy itself is `transferFrom` between two files, which is the one direction of it that
     * takes a fast path: the JDK requires the **source** to be a real `FileChannelImpl` (§1.6.2),
     * which is exactly a part on disk and is exactly not a socket.
     */
    fun completeUpload(
        uploadId: String,
        requested: List<Pair<Int, String>>,
        /**
         * How to combine the parts' checksums into the object's, when they have any.
         *
         * A function rather than a computation, because the algorithms live one layer up: `crc32c`
         * and its siblings are S3's vocabulary, and this module deliberately knows nothing about
         * S3. What it does know is which parts went in and in what order, so it hands that over
         * and takes back an answer or a `null`.
         */
        combineChecksums: (List<Metadata.Checksum>) -> Metadata.Checksum? = { null },
    ): Stored {
        val state =
            uploads[uploadId] ?: throw CompletionRefused(CompletionRefused.Reason.NO_SUCH_UPLOAD, uploadId)
        if (requested.isEmpty()) {
            throw CompletionRefused(CompletionRefused.Reason.NO_PARTS, "a completion with no parts in it")
        }
        for (i in 1 until requested.size) {
            if (requested[i].first <= requested[i - 1].first) {
                throw CompletionRefused(
                    CompletionRefused.Reason.INVALID_PART_ORDER,
                    "part ${requested[i].first} came after ${requested[i - 1].first}",
                )
            }
        }

        val chosen =
            requested.map { (number, eTag) ->
                val part =
                    state.parts[number]
                        ?: throw CompletionRefused(CompletionRefused.Reason.INVALID_PART, "no part $number")
                if (part.eTag.trim('"') != eTag.trim('"')) {
                    throw CompletionRefused(
                        CompletionRefused.Reason.INVALID_PART,
                        "part $number has ${part.eTag}, the completion says $eTag",
                    )
                }
                part
            }
        for (part in chosen.dropLast(1)) {
            if (part.size < MIN_PART_SIZE) {
                throw CompletionRefused(
                    CompletionRefused.Reason.ENTITY_TOO_SMALL,
                    "part ${part.number} is ${part.size} bytes, the minimum is $MIN_PART_SIZE",
                )
            }
        }

        val fileId = UUID.randomUUID().toString()
        val target = pathOf(fileId)
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.partial")
        var size = 0L
        FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            for (part in chosen) {
                FileChannel.open(pathOf(part.fileId), StandardOpenOption.READ).use { source ->
                    var moved = 0L
                    while (moved < part.size) {
                        val n = out.transferFrom(source, size + moved, part.size - moved)
                        if (n <= 0) throw java.io.EOFException("part ${part.number} ended early")
                        moved += n
                    }
                    size += moved
                }
            }
            if (durability == Durability.FSYNC) out.force(true)
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)

        val summaries =
            chosen.map { PartSummary(number = it.number, size = it.size, eTag = it.eTag, checksum = it.checksum) }
        val stored =
            commit(
                bucket = state.upload.bucket,
                key = state.upload.key,
                metadata =
                    state.upload.metadata.copy(
                        // Only when **every** part carried a checksum: a mixture has no answer,
                        // and inventing one would be worse than saying nothing.
                        checksum =
                            chosen
                                .map { it.checksum }
                                .takeIf { all -> all.all { it != null } }
                                ?.filterNotNull()
                                ?.let(combineChecksums),
                    ),
                staged = Staged(fileId, size, multipartETag(chosen)),
                parts = summaries,
            )
        uploads.remove(uploadId)
        write(IndexRecord.UploadEnded(state.upload.bucket, uploadId))
        for (part in state.parts.values) Files.deleteIfExists(pathOf(part.fileId))
        return stored
    }

    /**
     * Forgets uploads nobody finished.
     *
     * An abandoned upload holds its parts on the disk for ever otherwise, and there is no other
     * moment to notice: a client that stops calling says nothing (M-57).
     */
    fun sweepUploads(olderThanMillis: Long = 7 * 24 * 60 * 60 * 1000L): Int {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        var removed = 0
        for (state in uploads.values.toList()) {
            if (state.upload.startedAt.toEpochMilli() > cutoff) continue
            if (abortUpload(state.upload.id)) removed++
        }
        return removed
    }

    /**
     * `"<md5 of the parts' md5s>-<count>"`, which is what makes a multipart `ETag` recognisable.
     *
     * Not the MD5 of the object: a client that computed one over the whole file and compared would
     * find they disagree, which is exactly why the suffix is there — it says "this is not that
     * kind of ETag".
     */
    private fun multipartETag(parts: List<Part>): String {
        val digest = MessageDigest.getInstance("MD5")
        for (part in parts) digest.update(unhex(part.eTag.trim('"')))
        return "\"" + digest.digest().joinToString("") { "%02x".format(it) } + "-" + parts.size + "\""
    }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { ((text[it * 2].digitToInt(16) shl 4) or text[it * 2 + 1].digitToInt(16)).toByte() }

    // --- compaction (M9) ----------------------------------------------------------------------

    /** How large the log is right now, and how much of it is worth keeping. */
    val logSizeBytes: Long get() = log.sizeBytes

    /** What one compaction would leave: one record per bucket, object and upload with its parts. */
    private fun liveRecords(): Long = buckets.size.toLong() + objects.size + uploads.values.sumOf { 1L + it.parts.size }

    /**
     * Compacts when the log has grown to [factor] times what it needs to be, and not before.
     *
     * A store that compacted on every write would spend its life rewriting the index; one that
     * never compacted would take longer to start every day — measured, and it is not subtle:
     * three generations of half a million keys open in 3.5 seconds against 0.76 after compaction,
     * because recovery is proportional to the **log**, not to the live set.
     *
     * The floor exists so an empty store does not compact on every housekeeping tick, where the
     * ratio between one record and two is infinite and meaningless.
     */
    fun compactIfWorthwhile(
        factor: Double = 3.0,
        floor: Long = 1000,
    ): Compaction? {
        val live = liveRecords()
        val written = recordsSinceCompaction.get()
        if (written < floor || written < live * factor) return null
        return compact()
    }

    /**
     * Rewrites the log as the shortest one that would recover to the state it is in now.
     *
     * A log of mutations grows for ever otherwise: every overwrite leaves the record it replaced,
     * every delete leaves both the put and the tombstone. Compaction writes one record per living
     * thing — a bucket, an object, an upload in flight — and drops the rest.
     *
     * ## Why it is bounded by keys and not by data
     *
     * The bitcask shape (Р1): the log holds index records, not object bytes, so a compaction is
     * proportional to how many keys exist, never to how many gigabytes they point at. A terabyte
     * of objects under a thousand keys compacts in the time it takes to write a thousand records.
     * That is also the reason the published ceiling is a count and not a volume.
     *
     * ## Why the whole thing is under the writer's lock
     *
     * A compaction that ran alongside writers would have to catch up with whatever they appended
     * while it worked, and there is no point at which "caught up" is true without stopping them.
     * Holding the lock makes the pause the length of writing one record per key, which is the
     * cost the design already accepts everywhere else. A pause bounded by the key count is a
     * property to publish; a race in a store's index is not a trade at all.
     *
     * ## What a crash leaves
     *
     * The new log is built under another name and renamed over the old one, which is atomic. A
     * kill before the rename leaves the old log untouched and a stray file for the next start to
     * ignore; a kill after it leaves the new log, complete and `fsync`ed. There is no moment at
     * which the store's own state is half of each (M-65).
     */
    fun compact(): Compaction =
        writing.withLock {
            val before = log.sizeBytes
            val temp = root.resolve("index.log.compacting")
            Files.deleteIfExists(temp)

            var records = 0L
            RecordLog(temp).use { fresh ->
                for (bucket in buckets) {
                    fresh.append(IndexRecord.encode(IndexRecord.BucketCreated(bucket)))
                    records++
                }
                for ((located, stored) in objects) {
                    fresh.append(
                        IndexRecord.encode(
                            IndexRecord.Put(
                                bucket = located.bucket,
                                key = located.key,
                                fileId = stored.fileId,
                                size = stored.size,
                                eTag = stored.eTag,
                                lastModifiedMillis = stored.lastModified.toEpochMilli(),
                                metadata = stored.metadata,
                                parts = stored.parts,
                            ),
                        ),
                    )
                    records++
                }
                // Uploads in flight are state too, and a compaction that dropped them would lose
                // parts a client has already been told were accepted.
                for (state in uploads.values) {
                    fresh.append(
                        IndexRecord.encode(
                            IndexRecord.UploadStarted(
                                bucket = state.upload.bucket,
                                key = state.upload.key,
                                uploadId = state.upload.id,
                                startedAtMillis = state.upload.startedAt.toEpochMilli(),
                                metadata = state.upload.metadata,
                            ),
                        ),
                    )
                    records++
                    for (part in state.parts.values) {
                        fresh.append(
                            IndexRecord.encode(
                                IndexRecord.UploadPart(
                                    bucket = state.upload.bucket,
                                    uploadId = state.upload.id,
                                    number = part.number,
                                    fileId = part.fileId,
                                    size = part.size,
                                    eTag = part.eTag,
                                    lastModifiedMillis = part.lastModified.toEpochMilli(),
                                ),
                            ),
                        )
                        records++
                    }
                }
                fresh.force()
            }

            val after = Files.size(temp)
            log.close()
            Files.move(temp, logPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory()
            log = RecordLog(logPath).also { it.recover { } }
            recordsSinceCompaction.set(records)
            Compaction(before, after, records)
        }

    data class Compaction(
        val bytesBefore: Long,
        val bytesAfter: Long,
        val records: Long,
    )

    /**
     * `fsync` on the directory, which is what makes the rename itself durable.
     *
     * The file's contents being on the disk is not the same as the name pointing at them: a
     * rename lives in the directory, and a directory that was not synced can come back after a
     * power cut still naming the old inode. Best-effort because opening a directory as a channel
     * is a POSIX thing and this project is also edited on a Mac; the run that matters is Linux.
     */
    private fun syncDirectory() {
        runCatching {
            FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
        }
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
        // A part of an upload in progress is pointed at by the upload rather than by an object,
        // and a sweep that did not know about them would collect a client's work mid-upload.
        for (state in uploads.values) for (part in state.parts.values) referenced.add(part.fileId)
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
            recordsSinceCompaction.incrementAndGet()
        }
    }

    private fun firstKeyOf(bucket: String): ObjectKey? {
        val entry = objects.ceilingEntry(Located(bucket, ObjectKey(ByteArray(0)))) ?: return null
        return if (entry.key.bucket == bucket) entry.key.key else null
    }

    /** `data/ab/cd/<uuid>` — two levels so that no directory ever holds a million entries. */
    private fun pathOf(fileId: String): Path =
        data.resolve(fileId.substring(0, 2)).resolve(fileId.substring(2, 4)).resolve(fileId)

    /** Refused because the index is full — at startup, or at the write that would overflow it. */
    class CeilingExceeded(
        val objects: Int,
        val ceiling: Int,
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        /**
         * The floor on every part but the last, from the AWS documentation's own table
         * ("Amazon S3 multipart upload limits"). Closed by a live request in a neighbouring
         * project: two parts of one megabyte each answered `EntityTooSmall`.
         */
        private const val MIN_PART_SIZE = 5 * 1024 * 1024L

        /**
         * Measured, not guessed: 584 bytes for a forty-byte key and 646 for a hundred-byte one,
         * half a million objects at a time on ext4 (`docs/measurements.md`, M-64). The larger of
         * the two is taken, because a ceiling derived from the cheaper case is a ceiling that is
         * wrong for the customer who has long keys.
         */
        const val BYTES_PER_OBJECT = 650

        /**
         * How much of the heap the index is allowed to be.
         *
         * Half, and the other half is not spare: it is the request paths, the buffers and the
         * headroom a collector needs to not spend the process's life collecting. A store sized so
         * that the index alone fits works right up to the moment it has traffic.
         */
        const val INDEX_HEAP_FRACTION = 0.5

        fun heapMiB(): Long = Runtime.getRuntime().maxMemory() / (1024 * 1024)

        fun ceilingForHeap(heapBytes: Long = Runtime.getRuntime().maxMemory()): Int =
            (heapBytes * INDEX_HEAP_FRACTION / BYTES_PER_OBJECT)
                .toLong()
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (prefix.size > size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
