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
import java.util.concurrent.atomic.AtomicLong
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
     * The published ceiling: how many **versions** this store will manage (M-105).
     *
     * Not a limit invented to be safe — a consequence of the shape. Every key lives in memory
     * (Р1), so the number of index entries is bounded by the heap whether anybody says so or not,
     * and the only question is whether it is a stated characteristic or a slow slide into swap
     * that looks like the disk being slow. It is stated, and it is enforced twice: the store
     * refuses to **open** a log that is already over it, and refuses a new entry once it is
     * reached.
     *
     * It counted objects until versioning arrived, and the number did not change — what it
     * measures did. In a bucket that versions, ten writes to one key are ten entries, so a
     * consumer who read the old number and sized a deployment by it would meet the difference as
     * a refusal rather than as a note. Which is why the word in the message, in the `README` and
     * in this name is now the same word.
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
        /**
         * The version id a client sees, or [NULL_VERSION] for anything written to a bucket that is
         * not versioning.
         *
         * The literal string `null` is S3's, not an absence: a bucket that was never versioned
         * answers `versionId=null` for its objects, and a client may pass it back. Modelling it as
         * a Kotlin `null` would make "no version" and "the version called null" the same thing, and
         * they are not — the second one can be deleted by name.
         */
        val versionId: String = NULL_VERSION,
        /**
         * A tombstone: a version that says the key is gone without saying anything about bytes.
         *
         * It has no file, and every read that lands on it answers `404` with
         * `x-amz-delete-marker: true`. Deleting it by version id is how a key comes back.
         */
        val deleteMarker: Boolean = false,
        /** Retention on this version, when it has any (M-110). */
        val retention: Retention? = null,
        /** A legal hold: on until somebody turns it off, independent of [retention] (M-111). */
        val legalHold: Boolean = false,
        /**
         * How this version is encrypted, when the client brought a key (M26).
         *
         * Not part of [metadata] deliberately, and for the same reason as [retention]: metadata is
         * what the client said **about** the object and gets replayed verbatim, while this is what
         * the server did **to** it. Absent means the bytes on the disk are the bytes of the object.
         */
        val encryption: Encryption? = null,
    )

    /**
     * What the index remembers about an object encrypted with a customer key, and it is deliberately
     * not the key.
     *
     * The MD5 is here to tell a right key from a wrong one — without it a wrong key decrypts to
     * rubbish and the client is handed rubbish instead of a refusal. The IV is not a secret and
     * cannot be derived from anything else: deriving it from [Stored.fileId] was considered and
     * rejected, because the file id's job is to be a name, and a name that is also a cryptographic
     * input can never be changed afterwards.
     */
    data class Encryption(
        val algorithm: String,
        val keyMd5: String,
        val iv: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is Encryption &&
                        algorithm == other.algorithm &&
                        keyMd5 == other.keyMd5 &&
                        iv.contentEquals(other.iv)
                )

        override fun hashCode(): Int = (algorithm.hashCode() * 31 + keyMd5.hashCode()) * 31 + iv.contentHashCode()
    }

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
        /** The IV this stretch of the object was encrypted with, when it was (M-189). */
        val iv: ByteArray? = null,
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

    /**
     * Where an object lives in the index: a bucket, a key, and **which version**.
     *
     * The order of the three is the order a listing is defined in, and the third component is
     * ordered **backwards** on purpose. Versions of one key come out newest first — that is what
     * `ListObjectVersions` returns and what "the current version" means — and a structure that had
     * to reverse a range could not paginate it: a page would have to know where the range ends
     * before it could emit its beginning.
     *
     * [sequence] and not a timestamp. Two versions written in the same millisecond are two
     * versions, and a clock that steps backwards would reorder history.
     */
    private data class Located(
        val bucket: String,
        val key: ObjectKey,
        val sequence: Long,
    ) : Comparable<Located> {
        override fun compareTo(other: Located): Int {
            val byBucket = bucket.compareTo(other.bucket)
            if (byBucket != 0) return byBucket
            val byKey = key.compareTo(other.key)
            if (byKey != 0) return byKey
            return other.sequence.compareTo(sequence)
        }
    }

    /**
     * The greatest [Located] of a key, which sorts **first** among its versions.
     *
     * `ceilingEntry` of this is the current version of [key], or — when the key has none — the
     * first entry of whatever key comes next. Both walks in this file want exactly that.
     */
    private fun headOf(
        bucket: String,
        key: ObjectKey,
    ) = Located(bucket, key, Long.MAX_VALUE)

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

    /**
     * The buckets, and when each was made.
     *
     * A map rather than a set because `ListAllMyBucketsResult` has a `CreationDate` and a server
     * that does not keep one has to answer something: this one answered the epoch, for every
     * bucket, for ever. That is not a missing feature so much as a wrong answer — a client sorting
     * its buckets by age gets them in map order and no way to tell.
     */
    private val buckets = ConcurrentHashMap<String, Instant>()

    /**
     * Именованные настройки бакета — теги, CORS и что появится дальше — байтами, как они пришли.
     *
     * Ядро знает, что у бакета есть настройки, и не знает, что означает `tagging`: разбирает их
     * слой S3, которому и положено знать этот словарь. Ключ внешней карты — имя бакета, чтобы
     * удаление бакета уносило настройки одним движением.
     */
    private val subresources = ConcurrentHashMap<String, ConcurrentHashMap<String, ByteArray>>()

    // Beside [subresources] and not beside [setVersioning], for the reason [uploads] gives below:
    // the log is replayed into this map by a property initialiser, and initialisers run in
    // declaration order. Declared after that one, it is still null when recovery reaches it.
    private val versioningStates = ConcurrentHashMap<String, Versioning>()

    private val objectLocks = ConcurrentHashMap<String, ObjectLock>()

    // Beside the maps for the reason given above: recovery runs in a property initialiser, and
    // initialisers run in declaration order. This one is raised to one past the highest sequence
    // the log holds, so version order survives a restart.
    private val sequences = AtomicLong(0)

    /** One writer at a time on the log: its records must land in the order they were decided. */
    private val writing = ReentrantLock()

    // Declared here rather than beside the rest of the multipart code because property
    // initialisers run in declaration order and `init` replays the log into this one.
    private val uploads = ConcurrentHashMap<String, UploadState>()

    /**
     * What the last few completions produced, so a repeat of one can be answered.
     *
     * `CompleteMultipartUpload` is the one operation a client is most likely to send twice: it is
     * the last call of an upload that may have run for minutes, and a connection that drops while
     * the answer travels back leaves the client with no way to tell "it worked" from "it did not".
     * Every SDK retries. Without this the retry gets `NoSuchUpload`, which says the upload is gone
     * — the one thing that is certainly untrue, because the object is on the disk.
     *
     * Bounded and in memory, and both halves of that are a decision rather than an oversight. The
     * alternative is a record in the index log, which would make every completed upload cost index
     * space against the declared object ceiling (Р1) for ever, to answer a retry that arrives
     * within seconds or never. So: the last [REMEMBERED_COMPLETIONS] of them, forgotten on
     * restart. A retry that outlives either bound is answered `NoSuchUpload` again, which is what
     * this server has always said and is what S3 says once an upload has aged out.
     */
    private val completions = ConcurrentHashMap<String, Completion>()

    /** Insertion order for [completions], so the oldest can be dropped without scanning the map. */
    private val completionOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()

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
                        buckets[record.bucket] = Instant.ofEpochMilli(record.createdAtMillis)
                    }

                    is IndexRecord.BucketSubresource -> {
                        if (record.document == null) {
                            subresources[record.bucket]?.remove(record.name)
                        } else {
                            subresources.computeIfAbsent(record.bucket) { ConcurrentHashMap() }[record.name] =
                                record.document
                        }
                    }

                    is IndexRecord.BucketObjectLock -> {
                        objectLocks[record.bucket] =
                            ObjectLock(record.defaultMode, record.days, record.years)
                    }

                    is IndexRecord.BucketVersioning -> {
                        versioningStates[record.bucket] = record.state
                    }

                    is IndexRecord.BucketDeleted -> {
                        buckets.remove(record.bucket)
                        // Настройки уходят вместе с бакетом: иначе бакет, созданный заново под тем
                        // же именем, унаследовал бы чужие теги и CORS, и узнали бы об этом не сразу.
                        subresources.remove(record.bucket)
                        versioningStates.remove(record.bucket)
                        objectLocks.remove(record.bucket)
                    }

                    is IndexRecord.Deleted -> {
                        // Every version of the key, because that is what this record has always
                        // meant: it was written when a key had exactly one entry, and it is still
                        // written by a bucket that is not versioning.
                        for (located in locatedVersions(record.bucket, record.key)) objects.remove(located)
                    }

                    is IndexRecord.DeletedVersion -> {
                        objects.remove(Located(record.bucket, record.key, record.sequence))
                    }

                    is IndexRecord.Put -> {
                        val stored =
                            Stored(
                                fileId = record.fileId,
                                size = record.size,
                                eTag = record.eTag,
                                lastModified = Instant.ofEpochMilli(record.lastModifiedMillis),
                                metadata = record.metadata,
                                parts = record.parts,
                                versionId = record.versionId,
                                deleteMarker = record.deleteMarker,
                                retention =
                                    record.retentionMode?.let {
                                        Retention(it, record.retentionUntilMillis)
                                    },
                                legalHold = record.legalHold,
                                encryption =
                                    record.encryptionAlgorithm?.let { algorithm ->
                                        Encryption(
                                            algorithm,
                                            record.encryptionKeyMd5.orEmpty(),
                                            record.encryptionIv ?: ByteArray(0),
                                        )
                                    },
                            )
                        // A record from before versions carries sequence 0 and the `null` version,
                        // and every write of that key carried the same pair. Replayed as an insert
                        // they would pile up as versions of a key that never had any; replaced,
                        // they reproduce exactly the one entry the log was written to mean.
                        if (record.versionId == NULL_VERSION) {
                            for (located in locatedVersions(record.bucket, record.key)) {
                                if (objects[located]?.versionId == NULL_VERSION) objects.remove(located)
                            }
                        }
                        objects[Located(record.bucket, record.key, record.sequence)] = stored
                        // One past the highest sequence seen, so a restart does not hand out an id
                        // that sorts underneath history.
                        sequences.updateAndGet { maxOf(it, record.sequence + 1) }
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
                                    checksumAlgorithm = record.checksumAlgorithm,
                                    checksumType = record.checksumType,
                                    retention =
                                        record.retentionMode?.let {
                                            Retention(it, record.retentionUntilMillis)
                                        },
                                    legalHold = record.legalHold,
                                    encryption =
                                        record.encryptionAlgorithm?.let {
                                            Encryption(it, record.encryptionKeyMd5.orEmpty(), ByteArray(0))
                                        },
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
                                checksum = record.checksum,
                                iv = record.iv,
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
                "the index holds ${objects.size} versions and this heap allows $maxObjects " +
                    "($BYTES_PER_OBJECT bytes each, half of ${heapMiB()} MiB). " +
                    "Start with a larger -Xmx, or move objects off this node.",
            )
        }
    }

    val objectCount: Int get() = objects.size

    fun createBucket(name: String): Boolean {
        val createdAt = Instant.now()
        if (buckets.putIfAbsent(name, createdAt) != null) return false
        write(IndexRecord.BucketCreated(name, createdAt.toEpochMilli()))
        return true
    }

    fun hasBucket(name: String): Boolean = buckets.containsKey(name)

    /** A bucket and the moment it was created, which is the pair a listing has to answer with. */
    data class Bucket(
        val name: String,
        val createdAt: Instant,
    )

    /**
     * Меняет теги существующего объекта, не трогая его байты.
     *
     * Это единственная операция, которая переписывает метаданные уже лежащего объекта, и потому
     * она делается тем же `compute`, что и запись: между чтением и записью иначе появляется окно,
     * в котором объект успевает смениться, и теги достанутся не тому.
     *
     * `false` — объекта нет; теги существуют только у него.
     */
    fun setTags(
        bucket: String,
        key: ObjectKey,
        tags: Map<String, String>,
    ): Boolean {
        val entry = currentEntry(bucket, key)?.takeIf { !it.value.deleteMarker } ?: return false
        val stored = entry.value.copy(metadata = entry.value.metadata.copy(tags = tags))
        objects[entry.key] = stored
        // Rewritten at its own sequence, not at a new one: tagging changes what a version says
        // about itself, not which version is current. A new sequence would make `PutObjectTagging`
        // quietly promote an old version over a newer one.
        write(putRecord(bucket, key, entry.key.sequence, stored))
        return true
    }

    /**
     * Забывает всё: бакеты, объекты, настройки, загрузки в полёте — и **удаляет их файлы**.
     *
     * Для тестового двойника это операция между тестами: перезапуск стоит нового стора, нового
     * сокета и нового журнала, а сброс — очистки структур и одного обхода каталога. Тысяча
     * раундов без удаления файлов заполнила бы диск тем, на что никто не смотрит.
     *
     * Журнал при этом **пишется заново с нуля**, а не дополняется надгробиями: смысл сброса в том,
     * что состояния больше нет, а журнал из миллиона удалений — это состояние, которое придётся
     * переигрывать при следующем открытии.
     */
    fun reset() {
        writing.withLock {
            for (stored in objects.values) runCatching { Files.deleteIfExists(pathOf(stored.fileId)) }
            for (state in uploads.values) {
                for (part in state.parts.values) runCatching { Files.deleteIfExists(pathOf(part.fileId)) }
            }
            versioningStates.clear()
            objectLocks.clear()
            sequences.set(0)
            objects.clear()
            buckets.clear()
            subresources.clear()
            uploads.clear()
            completions.clear()
            completionOrder.clear()
            log.close()
            Files.deleteIfExists(logPath)
            log = RecordLog(logPath).also { it.recover { } }
            recordsSinceCompaction.set(0)
        }
    }

    /** Документ настройки, или `null`, если её не клали или сняли. */
    fun bucketSubresource(
        bucket: String,
        name: String,
    ): ByteArray? = subresources[bucket]?.get(name)

    /**
     * Кладёт настройку или снимает её (`document == null`).
     *
     * Пишется в журнал, потому что это состояние бакета: конфигурация, пережившая перезапуск
     * только в памяти, — это конфигурация, о которой клиенту сказали неправду.
     */
    fun putBucketSubresource(
        bucket: String,
        name: String,
        document: ByteArray?,
    ) {
        if (document == null) {
            subresources[bucket]?.remove(name)
        } else {
            subresources.computeIfAbsent(bucket) { ConcurrentHashMap() }[name] = document
        }
        write(IndexRecord.BucketSubresource(bucket, name, document))
    }

    /**
     * Whether a bucket keeps versions, and it has **three** states rather than two.
     *
     * [NONE] is not [SUSPENDED]: a bucket that was never configured answers `GetBucketVersioning`
     * with an empty document, while a suspended one answers `Suspended` — and the difference is
     * load-bearing, because a suspended bucket may still hold versions made while it was enabled.
     * S3 has no way back to [NONE] once versioning has been switched on, and neither has this.
     */
    enum class Versioning {
        NONE,
        ENABLED,
        SUSPENDED,
    }

    fun versioning(bucket: String): Versioning = versioningStates[bucket] ?: Versioning.NONE

    /**
     * A fresh version id: opaque to the client, unique to this store.
     *
     * Derived from a random UUID rather than from [sequences], and deliberately: a client that
     * could read the order of writes out of a version id would be reading how much traffic the
     * store has seen. The order lives in the index, where it is nobody's business but ours.
     */
    private fun mintVersionId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * Switches versioning on or off for a bucket.
     *
     * [Versioning.NONE] is refused rather than accepted and ignored: S3 offers no way back to
     * "never configured", and a store that pretended otherwise would answer an empty document for
     * a bucket that still holds versions.
     */
    fun setVersioning(
        bucket: String,
        state: Versioning,
    ) {
        require(state != Versioning.NONE) { "versioning cannot be switched back off, only suspended" }
        versioningStates[bucket] = state
        write(IndexRecord.BucketVersioning(bucket, state))
    }

    /**
     * Object lock on a bucket: whether versions can be locked at all, and for how long by default.
     *
     * Enabling it is a property of **creation** — S3 offers no way to turn it on afterwards, and
     * neither does this. It also forces versioning on, because a retention on something that can
     * be overwritten in place protects nothing.
     */
    data class ObjectLock(
        val defaultMode: String? = null,
        val days: Int? = null,
        val years: Int? = null,
    )

    /**
     * Retention on one version: a mode and the moment it stops applying.
     *
     * The two modes are not two strengths of the same thing. `GOVERNANCE` can be stepped over by a
     * caller who says so out loud; `COMPLIANCE` cannot be stepped over by anybody, including the
     * account that set it, and that is the entire point of it — a promise that is breakable by its
     * author is not a promise a regulator accepts.
     */
    data class Retention(
        val mode: String,
        val untilMillis: Long,
    )

    /** Refused because a version is under retention or a legal hold. */
    class Locked(
        override val message: String,
    ) : RuntimeException(message)

    fun objectLock(bucket: String): ObjectLock? = objectLocks[bucket]

    /**
     * Turns object lock on for a bucket, or replaces its default rule.
     *
     * Versioning comes with it and is not optional: [Versioning.ENABLED] is written here rather
     * than left to the caller, so a locked bucket cannot exist in a state where a write silently
     * replaces the version somebody locked.
     */
    fun setObjectLock(
        bucket: String,
        lock: ObjectLock,
    ) {
        objectLocks[bucket] = lock
        if (versioning(bucket) != Versioning.ENABLED) setVersioning(bucket, Versioning.ENABLED)
        write(IndexRecord.BucketObjectLock(bucket, lock.defaultMode, lock.days, lock.years))
    }

    /**
     * Puts retention on a version, refusing the changes S3 refuses.
     *
     * Weakening is the whole rule: extending a retention is always allowed, shortening it or
     * dropping the mode is what the lock exists to prevent. `GOVERNANCE` yields to a caller who
     * passes [bypass]; `COMPLIANCE` yields to nobody, so [bypass] is deliberately not consulted
     * for it.
     *
     * **Changing the mode is weakening too, and that is the half this missed** (M-175). The first
     * version of this compared only dates, so `GOVERNANCE` → `COMPLIANCE` with the same date was
     * not "weakened" and went through — and so did `COMPLIANCE` → `GOVERNANCE`, which turns a
     * promise nobody can break into one anybody can. The date was unchanged in both, which is
     * exactly why comparing dates saw nothing.
     */
    fun setRetention(
        bucket: String,
        key: ObjectKey,
        versionId: String?,
        retention: Retention?,
        bypass: Boolean = false,
        now: Instant = Instant.now(),
    ): Boolean =
        writing.withLock {
            val entry = versionEntry(bucket, key, versionId) ?: return@withLock false
            val existing = entry.value.retention
            if (existing != null && existing.untilMillis > now.toEpochMilli()) {
                val weakened =
                    retention == null ||
                        retention.untilMillis < existing.untilMillis ||
                        retention.mode != existing.mode
                if (weakened && (existing.mode == "COMPLIANCE" || !bypass)) {
                    throw Locked("the version is under ${existing.mode} retention until ${existing.untilMillis}")
                }
            }
            val stored = entry.value.copy(retention = retention)
            objects[entry.key] = stored
            write(putRecord(bucket, key, entry.key.sequence, stored))
            true
        }

    fun setLegalHold(
        bucket: String,
        key: ObjectKey,
        versionId: String?,
        held: Boolean,
    ): Boolean =
        writing.withLock {
            val entry = versionEntry(bucket, key, versionId) ?: return@withLock false
            val stored = entry.value.copy(legalHold = held)
            objects[entry.key] = stored
            write(putRecord(bucket, key, entry.key.sequence, stored))
            true
        }

    /** The named version, or the current one when no name was given. */
    private fun versionEntry(
        bucket: String,
        key: ObjectKey,
        versionId: String?,
    ): Map.Entry<Located, Stored>? {
        if (versionId == null) return currentEntry(bucket, key)
        return objects
            .tailMap(headOf(bucket, key), true)
            .asSequence()
            .takeWhile { it.key.bucket == bucket && it.key.key == key }
            .firstOrNull { it.value.versionId == versionId }
    }

    /**
     * What stands between a version and its deletion, if anything.
     *
     * A legal hold answers first and is not a duration: it is on until somebody turns it off, and
     * it does not care what the retention says. That independence is why the two are separate
     * fields rather than one state.
     */
    private fun lockRefusal(
        stored: Stored,
        bypass: Boolean,
        now: Instant,
    ): String? {
        if (stored.legalHold) return "the version is under a legal hold"
        val retention = stored.retention ?: return null
        if (retention.untilMillis <= now.toEpochMilli()) return null
        if (retention.mode == "GOVERNANCE" && bypass) return null
        return "the version is under ${retention.mode} retention"
    }

    /** Every bucket, in name order — which is the order `ListBuckets` pages through. */
    fun bucketList(): List<Bucket> = buckets.entries.map { Bucket(it.key, it.value) }.sortedBy { it.name }

    fun bucketNames(): List<String> = buckets.keys.sorted()

    fun deleteBucket(name: String): Boolean {
        if (firstKeyOf(name) != null) return false
        if (buckets.remove(name) == null) return false
        subresources.remove(name)
        versioningStates.remove(name)
        objectLocks.remove(name)
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
    data class Precondition(
        /** The object must exist, and its `ETag` must be one of these. `*` means "must exist". */
        val ifMatch: List<String>? = null,
        /** `*` means "must not exist"; a tag means "must not be this one". */
        val ifNoneMatch: List<String>? = null,
        /** The object must be exactly this long (`x-amz-if-match-size`). */
        val size: Long? = null,
        /** The object must carry exactly this modification time, to the second. */
        val lastModifiedMillis: Long? = null,
    ) {
        /**
         * Whether anything here is a claim about an object that exists.
         *
         * `If-None-Match` is the one condition satisfied by absence, so a request carrying only
         * that one has nothing to say about a missing key. Everything else does, and the caller
         * needs to know which — a `PUT` turns "absent" into `NoSuchKey` and a `DELETE` turns it
         * into success, and neither is `PreconditionFailed`.
         */
        val needsTheObject: Boolean get() = ifMatch != null || size != null || lastModifiedMillis != null

        companion object {
            val NONE = Precondition()
        }
    }

    /**
     * How a precondition came out, and it has three answers rather than two.
     *
     * The third one is the whole of M-84 and M-85: a condition that fails because the object is
     * **not there** is a different fact from one that fails because the object is not what the
     * client thought, and the two operations that take these headers disagree about what to do
     * with it. `s3-service-2.json` spells the delete side out — *"If the Size matches or if the
     * object doesn't exist, the operation returns 204"* — and a model with two answers cannot
     * express that without the caller guessing.
     */
    enum class Outcome { HELD, MISMATCH, ABSENT }

    class PreconditionFailed(
        val outcome: Outcome,
        override val message: String,
    ) : RuntimeException(message)

    private fun Precondition.holdsFor(existing: Stored?): Outcome {
        if (existing == null) {
            return if (needsTheObject) Outcome.ABSENT else Outcome.HELD
        }
        val matches =
            (ifMatch == null || "*" in ifMatch || ifMatch.any { it.trim('"') == existing.eTag.trim('"') }) &&
                (
                    ifNoneMatch == null ||
                        ("*" !in ifNoneMatch && ifNoneMatch.none { it.trim('"') == existing.eTag.trim('"') })
                ) &&
                (size == null || size == existing.size) &&
                // To the second, because that is the resolution the object's timestamp is
                // published at: `Last-Modified` and `rfc822` both drop the milliseconds, so a
                // client can only ever have seen a whole second and comparing finer would make
                // this condition impossible to satisfy from outside.
                (
                    lastModifiedMillis == null ||
                        lastModifiedMillis / 1000 == existing.lastModified.toEpochMilli() / 1000
                )
        return if (matches) Outcome.HELD else Outcome.MISMATCH
    }

    /**
     * Publishes staged bytes as a version of a key.
     *
     * ## Why this holds a lock and the previous one did not
     *
     * It used to be a single `compute` on the map, which made the precondition and the write one
     * step for free: the map held the slot while the check ran. With versions there is no slot —
     * the condition is about the **newest** version and the write adds a different entry — so the
     * two would come apart, and `If-None-Match: *` would stop meaning what it exists to mean.
     * The lock is on index mutation only: the bytes are already on disk by the time this is
     * called, so what it serialises is a map insert and a journal append.
     */
    @Suppress("LongParameterList")
    fun commit(
        bucket: String,
        key: ObjectKey,
        metadata: Metadata,
        staged: Staged,
        precondition: Precondition = Precondition.NONE,
        parts: List<PartSummary> = emptyList(),
        /**
         * A lock the version is born with, rather than one put on it a moment later.
         *
         * One index record instead of two, and no window: a crash between "the object exists" and
         * "the object is protected" leaves an unprotected object, which is the one outcome a lock
         * is bought to prevent. Used by the multipart completion, where the lock was stated
         * minutes earlier on the request that started the upload (M-175).
         */
        retention: Retention? = null,
        legalHold: Boolean = false,
        /** Set when the client brought a key: the algorithm, the key's MD5 and the IV (M26). */
        encryption: Encryption? = null,
    ): Stored =
        writing.withLock {
            val state = versioning(bucket)
            val outcome = precondition.holdsFor(get(bucket, key))
            if (outcome != Outcome.HELD) {
                throw PreconditionFailed(
                    outcome,
                    if (outcome == Outcome.ABSENT) {
                        "there is no object at this key"
                    } else {
                        "the object is not the one described"
                    },
                )
            }

            // The ceiling counts entries, and only a write that adds one is refused: overwriting
            // costs nothing, and refusing it would make a full store unable to shrink. A
            // versioning bucket always adds one, which is exactly why the published number is a
            // number of versions now (M-105).
            val addsEntry = state == Versioning.ENABLED || currentEntry(bucket, key) == null
            if (objects.size >= maxObjects && addsEntry) {
                throw CeilingExceeded(
                    objects.size,
                    maxObjects,
                    "the index is at its ceiling of $maxObjects versions",
                )
            }

            val sequence = sequences.getAndIncrement()
            val stored =
                Stored(
                    fileId = staged.fileId,
                    size = staged.size,
                    eTag = staged.eTag,
                    lastModified = Instant.now(),
                    metadata = metadata,
                    parts = parts,
                    versionId = if (state == Versioning.ENABLED) mintVersionId() else NULL_VERSION,
                    retention = retention,
                    legalHold = legalHold,
                    encryption = encryption,
                )

            // Not versioning means the write **replaces** the null version rather than joining it,
            // and suspended means the same thing: S3 keeps at most one version called `null`, and
            // versions made while the bucket was enabled survive beside it.
            val replaced = if (state == Versioning.ENABLED) emptyList() else dropNullVersions(bucket, key)

            objects[Located(bucket, key, sequence)] = stored
            write(putRecord(bucket, key, sequence, stored))

            // The replaced file goes after the index stops pointing at it, and a reader that opened
            // it before that keeps reading — the descriptor outlives the name (Р2, M-44).
            for (gone in replaced) Files.deleteIfExists(pathOf(gone.fileId))
            stored
        }

    /**
     * Removes every version of [key] called `null`, and writes **nothing** to the log.
     *
     * Both callers follow this with a `Put` whose version is `null`, and replay already treats
     * such a record as replacing every null version of its key — so a removal record would say a
     * second time what the next record says anyway.
     *
     * It journalled them at first, and the cost was not subtle: every index record costs an
     * `fsync`, so an ordinary overwrite paid two where it used to pay one, and a thousand-key
     * batch delete paid two thousand. The server stayed up and answered nothing for minutes,
     * which reads from outside as a hang rather than as a server doing twice the work
     * (`bochka-app` tests all passed — they are too small to feel it).
     *
     * Called under [writing]. There is at most one null version in practice; the loop is here
     * because "at most one" is an invariant of the code above rather than of the map.
     */
    private fun dropNullVersions(
        bucket: String,
        key: ObjectKey,
    ): List<Stored> {
        val doomed =
            objects
                .tailMap(headOf(bucket, key), true)
                .asSequence()
                .takeWhile { it.key.bucket == bucket && it.key.key == key }
                .filter { it.value.versionId == NULL_VERSION }
                .map { it.key to it.value }
                .toList()
        for ((located, _) in doomed) objects.remove(located)
        // A tombstone has no file, so there is nothing for the caller to unlink.
        return doomed.map { it.second }.filter { !it.deleteMarker }
    }

    private fun putRecord(
        bucket: String,
        key: ObjectKey,
        sequence: Long,
        stored: Stored,
    ) = IndexRecord.Put(
        bucket = bucket,
        key = key,
        fileId = stored.fileId,
        size = stored.size,
        eTag = stored.eTag,
        lastModifiedMillis = stored.lastModified.toEpochMilli(),
        metadata = stored.metadata,
        parts = stored.parts,
        sequence = sequence,
        versionId = stored.versionId,
        deleteMarker = stored.deleteMarker,
        retentionMode = stored.retention?.mode,
        retentionUntilMillis = stored.retention?.untilMillis ?: 0,
        legalHold = stored.legalHold,
        encryptionAlgorithm = stored.encryption?.algorithm,
        encryptionKeyMd5 = stored.encryption?.keyMd5,
        encryptionIv = stored.encryption?.iv,
    )

    /** Throws away bytes that were written and turned out not to be wanted. */
    fun discard(staged: Staged) {
        Files.deleteIfExists(pathOf(staged.fileId))
    }

    /**
     * The current version of a key, delete marker included.
     *
     * Callers that want "the object" want [get], which answers `null` for a tombstone. This one is
     * for the two places that need to know a tombstone is there: a read that must say
     * `x-amz-delete-marker`, and a write that must know what it is replacing.
     */
    private fun currentEntry(
        bucket: String,
        key: ObjectKey,
    ): Map.Entry<Located, Stored>? {
        val entry = objects.ceilingEntry(headOf(bucket, key)) ?: return null
        return if (entry.key.bucket == bucket && entry.key.key == key) entry else null
    }

    /** The index keys of every version of one key, newest first. Callers under [writing]. */
    private fun locatedVersions(
        bucket: String,
        key: ObjectKey,
    ): List<Located> =
        objects
            .tailMap(headOf(bucket, key), true)
            .asSequence()
            .takeWhile { it.key.bucket == bucket && it.key.key == key }
            .map { it.key }
            .toList()

    /** Every version of a key, newest first — the order `ListObjectVersions` is defined in. */
    fun versions(
        bucket: String,
        key: ObjectKey,
    ): List<Stored> =
        objects
            .tailMap(headOf(bucket, key), true)
            .asSequence()
            .takeWhile { it.key.bucket == bucket && it.key.key == key }
            .map { it.value }
            .toList()

    /**
     * The current version of a key, or `null` when there is none **or** when it is a tombstone.
     *
     * A delete marker answering `null` here is the point of it: every caller that asks for an
     * object and gets nothing already knows what to do, and a caller that has to remember to check
     * a flag is a caller that will forget once.
     */
    fun get(
        bucket: String,
        key: ObjectKey,
    ): Stored? = currentEntry(bucket, key)?.value?.takeIf { !it.deleteMarker }

    /** The current version whether or not it is a tombstone — for the answer that says so. */
    fun currentVersion(
        bucket: String,
        key: ObjectKey,
    ): Stored? = currentEntry(bucket, key)?.value

    /**
     * One named version, tombstone included.
     *
     * A linear walk of the key's versions rather than an index on the id: version ids are opaque
     * and a second index would have to be kept true through every write and every compaction, to
     * make a lookup faster that is bounded by how many versions one key has.
     */
    fun get(
        bucket: String,
        key: ObjectKey,
        versionId: String,
    ): Stored? = versions(bucket, key).firstOrNull { it.versionId == versionId }

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

    /**
     * The directory every object file sits under.
     *
     * Exposed for one caller: a deployment that hands the file to a terminator in front rather
     * than sending it itself (`X-Accel-Redirect`) has to name the file **relative** to a root that
     * the terminator also knows. The absolute path from [pathOf] is this server's view of it, and
     * the two are the same directory only by arrangement.
     */
    val dataRoot: Path get() = data

    /**
     * What a `DELETE` did, which in a versioning bucket is not what the word suggests.
     *
     * [existed] answers the batch delete, which reports per key. [marker] is the tombstone that was
     * laid down, and it is `null` in a bucket without versioning — there the bytes really are gone.
     */
    data class Deletion(
        val existed: Boolean,
        val marker: Stored? = null,
    )

    /**
     * Deletes a key, or says it is deleted, depending on the bucket.
     *
     * Without versioning the entry and its file go. With versioning nothing goes: a delete marker
     * becomes the newest version, reads answer `404`, and every version underneath is still there
     * to be named. Suspended sits between the two — a marker is laid down, but it is the `null`
     * version, so it replaces the previous `null` one instead of stacking.
     */
    fun delete(
        bucket: String,
        key: ObjectKey,
        precondition: Precondition = Precondition.NONE,
    ): Deletion =
        writing.withLock {
            val current = get(bucket, key)
            // Nothing to protect, so nothing the condition can be wrong about: deleting a key that
            // is not there is already a success, and `s3-service-2.json` says the conditional form
            // answers `204` too. This is why the outcome has three values.
            if (current != null && precondition.holdsFor(current) != Outcome.HELD) {
                throw PreconditionFailed(
                    Outcome.MISMATCH,
                    "the object is ${current.eTag} and ${current.size} bytes",
                )
            }

            val state = versioning(bucket)
            if (state == Versioning.NONE) {
                val entry = currentEntry(bucket, key) ?: return@withLock Deletion(existed = false)
                objects.remove(entry.key)
                write(IndexRecord.Deleted(bucket, key))
                Files.deleteIfExists(pathOf(entry.value.fileId))
                return@withLock Deletion(existed = true)
            }

            val existed = current != null
            val sequence = sequences.getAndIncrement()
            val marker =
                Stored(
                    fileId = "",
                    size = 0,
                    eTag = "",
                    lastModified = Instant.now(),
                    metadata = Metadata(),
                    versionId = if (state == Versioning.ENABLED) mintVersionId() else NULL_VERSION,
                    deleteMarker = true,
                )
            if (state == Versioning.SUSPENDED) dropNullVersions(bucket, key)
            objects[Located(bucket, key, sequence)] = marker
            write(putRecord(bucket, key, sequence, marker))
            Deletion(existed, marker)
        }

    /**
     * Deletes one named version, for good.
     *
     * The only operation in this store that loses data on purpose, and the only one a versioning
     * bucket has for it. Deleting a delete marker by id is how a key is brought back: the version
     * underneath becomes current again.
     */
    fun deleteVersion(
        bucket: String,
        key: ObjectKey,
        versionId: String,
        bypass: Boolean = false,
        now: Instant = Instant.now(),
    ): Stored? =
        writing.withLock {
            val entry =
                objects
                    .tailMap(headOf(bucket, key), true)
                    .asSequence()
                    .takeWhile { it.key.bucket == bucket && it.key.key == key }
                    .firstOrNull { it.value.versionId == versionId }
                    ?: return@withLock null

            lockRefusal(entry.value, bypass, now)?.let { throw Locked(it) }
            objects.remove(entry.key)
            write(IndexRecord.DeletedVersion(bucket, key, entry.key.sequence))
            if (!entry.value.deleteMarker) Files.deleteIfExists(pathOf(entry.value.fileId))
            entry.value
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
            val entry = objects.ceilingEntry(headOf(bucket, ObjectKey(cursor))) ?: break
            read++
            if (entry.key.bucket != bucket) break
            val bytes = entry.key.key.toByteArray()
            if (!bytes.startsWith(prefix)) break

            // A key whose current version is a tombstone is not in this listing at all. The cursor
            // steps past the **key**, not past the entry: its older versions are still in the map,
            // and stepping one entry would walk into them and list a deleted object as present.
            if (entry.value.deleteMarker) {
                cursor = justAfter(bytes)
                continue
            }

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

    /**
     * One entry of `ListObjectVersions`: a version, and whether it is the current one.
     *
     * Separate from the pair a listing of objects yields, because the two answer different
     * questions. A listing of objects has one row per key and never mentions a tombstone; this has
     * one row per version and must mention them, since a tombstone is what a client is looking for
     * when it wants to know why its object is gone.
     */
    data class VersionEntry(
        val key: ObjectKey,
        val stored: Stored,
        val isLatest: Boolean,
    )

    data class VersionPage(
        val versions: List<VersionEntry>,
        val commonPrefixes: List<ByteArray>,
        val isTruncated: Boolean,
        val nextKeyMarker: ByteArray?,
        val nextVersionIdMarker: String?,
    )

    /**
     * A page of every version in a bucket, newest first within each key (M-107).
     *
     * The walk steps **entry by entry** rather than key by key, which is the whole difference from
     * [page]: there the cursor jumps past a key once its current version has been emitted, and
     * here the older ones are the point. A delimiter still collapses a whole group in one step,
     * versions and all — a rolled-up prefix says nothing about how many versions are under it.
     *
     * Resuming takes two markers because one is not enough: a page can end in the middle of a
     * key's versions, and `key-marker` alone could only resume at a key boundary, which would
     * either repeat versions or skip them.
     */
    fun versionPage(
        bucket: String,
        prefix: ByteArray = ByteArray(0),
        delimiter: ByteArray? = null,
        keyMarker: ByteArray? = null,
        versionIdMarker: String? = null,
        maxKeys: Int = 1000,
    ): VersionPage {
        val versions = ArrayList<VersionEntry>()
        val groups = ArrayList<ByteArray>()
        var truncated = false
        var lastKey: ByteArray? = null
        var lastVersion: String? = null

        // Where to start. Without a version marker the previous page ended on a whole key, so the
        // next one starts after it; with one it ended inside a key, and the versions before the
        // marker have already been sent.
        var cursor: ByteArray = if (keyMarker != null && versionIdMarker == null) justAfter(keyMarker) else prefix
        if (keyMarker != null && versionIdMarker != null && Arrays.compareUnsigned(keyMarker, prefix) >= 0) {
            cursor = keyMarker
        }
        var skippingTo: String? = versionIdMarker
        var seenKey: ByteArray? = null

        var entry = objects.ceilingEntry(headOf(bucket, ObjectKey(cursor)))
        while (entry != null && entry.key.bucket == bucket) {
            val bytes = entry.key.key.toByteArray()
            if (!bytes.startsWith(prefix)) break

            val group = delimiter?.let { groupOf(bytes, prefix.size, it) }
            if (group != null) {
                if (groups.none { it.contentEquals(group) }) {
                    if (versions.size + groups.size == maxKeys) {
                        truncated = true
                        break
                    }
                    groups.add(group)
                    lastKey = group
                    lastVersion = null
                }
                val next = past(group) ?: break
                entry = objects.ceilingEntry(headOf(bucket, ObjectKey(next)))
                continue
            }

            val isLatest = !bytes.contentEquals(seenKey)
            seenKey = bytes

            // Everything up to and including the marked version belongs to the previous page —
            // but the marked version may be **gone**, and that is the normal case rather than the
            // odd one: the canonical consumer of this listing is a cleanup that deletes each
            // version as it pages. Scanning for the marker then walks past it and eats the first
            // version of the next page, one per page, silently. So the skip stops at the marked
            // **key**: once the walk is past it, whatever comes next belongs to this page.
            if (skippingTo != null) {
                if (keyMarker != null && bytes.contentEquals(keyMarker)) {
                    val reached = entry.value.versionId == skippingTo
                    entry = objects.higherEntry(entry.key)
                    if (reached) skippingTo = null
                    continue
                }
                skippingTo = null
            }

            if (versions.size + groups.size == maxKeys) {
                truncated = true
                break
            }
            versions.add(VersionEntry(entry.key.key, entry.value, isLatest))
            lastKey = bytes
            lastVersion = entry.value.versionId
            entry = objects.higherEntry(entry.key)
        }

        return VersionPage(
            versions,
            groups,
            truncated,
            if (truncated) lastKey else null,
            if (truncated) lastVersion else null,
        )
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
        /**
         * What the parts are to be checksummed with, and how those combine into the object's.
         *
         * Chosen on the request that starts the upload and not on any later one, which is why it
         * lives here: the completion happens minutes afterwards and has to answer the question the
         * client asked at the beginning. Both are S3's own words, kept verbatim, because this
         * module has no opinion about what `FULL_OBJECT` means — it stores it and hands it back.
         */
        val checksumAlgorithm: String? = null,
        val checksumType: String? = null,
        /**
         * The customer key this upload was started with, as its algorithm and MD5 (M-189).
         *
         * Here for the same reason as [checksumAlgorithm]: `CreateMultipartUpload` takes the SSE-C
         * headers, the parts arrive minutes later, and every one of them has to be checked against
         * what the upload was started with. Never the key itself — the parts bring it again, which
         * is what S3 requires of them.
         */
        val encryption: Encryption? = null,
        /**
         * The lock stated on the request that started the upload, held until there is something to
         * put it on.
         *
         * `CreateMultipartUpload` takes `x-amz-object-lock-*` exactly as `PutObject` does, and the
         * object it locks does not exist for another few minutes. Dropping them here — which is
         * what happened until M-175 — produces a finished object with no lock at all, and the
         * client that asked for one is told the upload succeeded.
         */
        val retention: Retention? = null,
        val legalHold: Boolean = false,
    )

    data class Part(
        val number: Int,
        val fileId: String,
        val size: Long,
        val eTag: String,
        val lastModified: Instant,
        /** What the client stated about this part, kept so the finished object can answer for it. */
        val checksum: Metadata.Checksum? = null,
        /**
         * The initialisation vector this part was encrypted with, when the upload is encrypted.
         *
         * **Per part and not per object, and the alternative was the obvious one.** A completion
         * could decrypt every part and re-encrypt it into the object under a single IV, which would
         * leave the read path with one cipher and no boundaries — and would turn the join, today a
         * kernel-level `transferFrom`, into a read-transform-write pass over the whole object with
         * two AES passes on top. Keeping an IV per part costs a cipher restart per five mebibytes
         * on a read path that is already off the fast path, and costs the join nothing.
         */
        val iv: ByteArray? = null,
    )

    /**
     * What a completion answered, kept so the same question gets the same answer.
     *
     * Deliberately not a [Stored]: that carries the part list, which for a ten-thousand-part
     * object is the largest thing in the index, and none of it is in the response. What a repeat
     * of `CompleteMultipartUpload` has to be told is where the object went and what it hashed to.
     */
    data class Completion(
        val bucket: String,
        val key: ObjectKey,
        val eTag: String,
        val checksum: Metadata.Checksum?,
    )

    /** Why a completion cannot happen. The S3 layer turns each of these into a code and a status. */
    class CompletionRefused(
        val reason: Reason,
        override val message: String,
    ) : RuntimeException(message) {
        enum class Reason {
            NO_SUCH_UPLOAD,
            NO_PARTS,
            INVALID_PART,
            INVALID_PART_ORDER,
            ENTITY_TOO_SMALL,

            /** The client said what the finished object would hash to, and it does not. */
            CHECKSUM_MISMATCH,
        }
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
        checksumAlgorithm: String? = null,
        checksumType: String? = null,
        retention: Retention? = null,
        legalHold: Boolean = false,
        encryption: Encryption? = null,
    ): Upload {
        val upload =
            Upload(
                id = UUID.randomUUID().toString(),
                bucket = bucket,
                key = key,
                metadata = metadata,
                startedAt = Instant.now(),
                checksumAlgorithm = checksumAlgorithm,
                checksumType = checksumType,
                retention = retention,
                legalHold = legalHold,
                encryption = encryption,
            )
        uploads[upload.id] = UploadState(upload)
        write(
            IndexRecord.UploadStarted(
                bucket = bucket,
                key = key,
                uploadId = upload.id,
                startedAtMillis = upload.startedAt.toEpochMilli(),
                metadata = metadata,
                checksumAlgorithm = checksumAlgorithm,
                checksumType = checksumType,
                retentionMode = retention?.mode,
                retentionUntilMillis = retention?.untilMillis ?: 0,
                legalHold = legalHold,
                encryptionAlgorithm = encryption?.algorithm,
                encryptionKeyMd5 = encryption?.keyMd5,
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
        iv: ByteArray? = null,
    ): Part {
        val state = uploads[uploadId] ?: throw CompletionRefused(CompletionRefused.Reason.NO_SUCH_UPLOAD, uploadId)
        val part = Part(number, staged.fileId, staged.size, staged.eTag, Instant.now(), checksum, iv)
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
                checksum = checksum,
                iv = iv,
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
         * How to combine the parts into the object's checksum, when they have one.
         *
         * A function rather than a computation, because the algorithms live one layer up: `crc32c`
         * and `FULL_OBJECT` are S3's vocabulary, and this module deliberately knows nothing about
         * S3. What it does know is which parts went in, in what order and how long each was — the
         * lengths matter, because combining CRCs into a checksum of the whole needs them — and
         * which type the upload was started with. It hands all of that over and takes an answer or
         * a `null`.
         */
        combineChecksums: (parts: List<PartSummary>, checksumType: String?) -> Metadata.Checksum? = { _, _ -> null },
        /**
         * What has to be true of the key before the assembled object takes it.
         *
         * A completion is a write like any other, so `If-Match` and `If-None-Match` mean here what
         * they mean on a `PUT` — and they have to be applied at the same instant the key changes
         * hands, not before the parts are joined, or two uploads racing for one key with
         * `If-None-Match: *` both pass their check.
         */
        precondition: Precondition = Precondition.NONE,
        /**
         * What the client says the finished object will hash to, when it says anything.
         *
         * Checked **before** a byte is joined, which is the rule this operation is built on: S3 has
         * a second response shape for a completion whose outcome is not known when the status went
         * out, and this server does not need it because everything refusable is refusable first.
         */
        expectedChecksum: Metadata.Checksum? = null,
    ): Stored {
        val state =
            uploads[uploadId] ?: throw CompletionRefused(CompletionRefused.Reason.NO_SUCH_UPLOAD, uploadId)
        if (requested.isEmpty()) {
            throw CompletionRefused(CompletionRefused.Reason.NO_PARTS, "a completion with no parts in it")
        }
        for (i in 1 until requested.size) {
            if (requested[i].first < requested[i - 1].first) {
                throw CompletionRefused(
                    CompletionRefused.Reason.INVALID_PART_ORDER,
                    "part ${requested[i].first} came after ${requested[i - 1].first}",
                )
            }
        }

        // A number listed twice is one part described twice, and the last description wins — the
        // same rule `commitPart` already applies to a resent part, and for the same reason: the
        // later entry is the client's later knowledge of it. Refusing the repeat as "out of order"
        // reads two entries as two parts, and a client that resent a part while the first send was
        // still being read would be told its perfectly ordered list is backwards (M-89).
        val listed = requested.associateTo(LinkedHashMap()) { it }.toList()

        val chosen =
            listed.map { (number, eTag) ->
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

        val summaries =
            chosen.map {
                PartSummary(number = it.number, size = it.size, eTag = it.eTag, checksum = it.checksum, iv = it.iv)
            }

        // Only when **every** part carried a checksum: a mixture has no answer, and inventing one
        // would be worse than saying nothing.
        val objectChecksum =
            summaries
                .takeIf { all -> all.all { it.checksum != null } }
                ?.let { combineChecksums(it, state.upload.checksumType) }

        // Before the join rather than after it, and that is the same rule the rest of this
        // function follows: nothing is written until nothing can refuse it.
        if (expectedChecksum != null && expectedChecksum != objectChecksum) {
            throw CompletionRefused(
                CompletionRefused.Reason.CHECKSUM_MISMATCH,
                "the completion says ${expectedChecksum.value}, these parts make ${objectChecksum?.value}",
            )
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

        val stored =
            commitAssembled(
                bucket = state.upload.bucket,
                key = state.upload.key,
                metadata = state.upload.metadata.copy(checksum = objectChecksum),
                staged = Staged(fileId, size, multipartETag(chosen)),
                parts = summaries,
                precondition = precondition,
                retention = state.upload.retention,
                legalHold = state.upload.legalHold,
                // The object's own IV is empty on purpose: an assembled object has one per part,
                // and the reader picks by offset. A single IV here would be a field nobody uses,
                // which is the kind of field somebody later mistakes for the answer.
                encryption = state.upload.encryption,
            )
        uploads.remove(uploadId)
        remember(uploadId, Completion(state.upload.bucket, state.upload.key, stored.eTag, stored.metadata.checksum))
        write(IndexRecord.UploadEnded(state.upload.bucket, uploadId))
        for (part in state.parts.values) Files.deleteIfExists(pathOf(part.fileId))
        return stored
    }

    /**
     * [commit], with the joined file cleaned up if the key refuses it.
     *
     * A refused completion has already paid for the assembly, and what it leaves on the disk is a
     * file nothing points at. The background sweep would collect it — but the sweep exists for
     * what a crash leaves behind, and this is a refusal the server is awake for and can tidy after
     * itself.
     */
    @Suppress("LongParameterList")
    private fun commitAssembled(
        bucket: String,
        key: ObjectKey,
        metadata: Metadata,
        staged: Staged,
        parts: List<PartSummary>,
        precondition: Precondition,
        retention: Retention?,
        legalHold: Boolean,
        encryption: Encryption?,
    ): Stored =
        try {
            commit(bucket, key, metadata, staged, precondition, parts, retention, legalHold, encryption)
        } catch (e: Throwable) {
            Files.deleteIfExists(pathOf(staged.fileId))
            throw e
        }

    /**
     * What [completeUpload] answered for this id, if it is still remembered.
     *
     * Answers nothing about the object at that key **now** — it may have been overwritten or
     * deleted since, and this still says what the upload produced. That is the right claim to
     * make: the question a retry asks is "did my completion happen", not "what is there".
     */
    fun completion(uploadId: String): Completion? = completions[uploadId]

    private fun remember(
        uploadId: String,
        completion: Completion,
    ) {
        completions[uploadId] = completion
        completionOrder.add(uploadId)
        while (completionOrder.size > REMEMBERED_COMPLETIONS) {
            completionOrder.poll()?.let(completions::remove)
        }
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
                for ((bucket, createdAt) in buckets) {
                    fresh.append(IndexRecord.encode(IndexRecord.BucketCreated(bucket, createdAt.toEpochMilli())))
                    records++
                    // Настройки — тоже живое состояние: уплотнение, потерявшее их, потеряло бы
                    // конфигурацию, о которой клиенту уже ответили.
                    for ((name, document) in subresources[bucket].orEmpty()) {
                        fresh.append(IndexRecord.encode(IndexRecord.BucketSubresource(bucket, name, document)))
                        records++
                    }
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
                                checksumAlgorithm = state.upload.checksumAlgorithm,
                                checksumType = state.upload.checksumType,
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
                                    checksum = part.checksum,
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
        val entry = objects.ceilingEntry(headOf(bucket, ObjectKey(ByteArray(0)))) ?: return null
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
         * The version id of anything written to a bucket that is not versioning.
         *
         * The literal four letters, not an absence: S3 answers `versionId=null` for such objects,
         * clients pass it back, and `?versionId=null` deletes it permanently. A sentinel that
         * looked like a Kotlin `null` would collapse "has no version" into "has no value".
         */
        const val NULL_VERSION: String = "null"

        /**
         * The floor on every part but the last, from the AWS documentation's own table
         * ("Amazon S3 multipart upload limits"). Closed by a live request in a neighbouring
         * project: two parts of one megabyte each answered `EntityTooSmall`.
         */
        private const val MIN_PART_SIZE = 5 * 1024 * 1024L

        /**
         * How many finished uploads are remembered so their completion can be repeated.
         *
         * Sized for the shape of the retry rather than for a workload: a client retries a
         * completion seconds after the first attempt, not hours later, so what matters is that the
         * window survives a burst of concurrent uploads rather than that it is long. A thousand of
         * them is under a hundred kilobytes and does not count against the object ceiling, which
         * is why this is not in the index log.
         */
        private const val REMEMBERED_COMPLETIONS = 1024

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
