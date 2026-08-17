package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.ObjectKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * **Draft storage. Not the storage.**
 *
 * M3 is about the input path — one `PUT`, accepted four different ways, by four clients that share
 * no code with this one. It needs somewhere to put the bytes, and this is that somewhere: a file
 * per object under a technical name, an index in a map, no durability, no recovery, no crash test.
 * The real one is M4, and it starts with the crash test rather than with the write (Р12).
 *
 * Two things here are not draft, because getting them wrong now would teach the wrong shape:
 *
 * * the name on disk is a UUID and the key lives only in the index (Р2), so nothing above ever
 *   learns that a filesystem is involved;
 * * the file is written and renamed into place, so a reader never sees a half-written object.
 *   The `fsync` that makes that a durability property rather than a visibility one belongs to M4.
 */
class DraftStore(
    private val root: Path,
) {
    class StoredObject(
        val path: Path,
        val size: Long,
        val eTag: String,
        val lastModified: Instant,
        val contentType: String?,
    )

    private val buckets = ConcurrentHashMap.newKeySet<String>()
    private val objects = ConcurrentHashMap<Pair<String, ObjectKey>, StoredObject>()

    init {
        Files.createDirectories(root.resolve("data"))
    }

    fun createBucket(name: String): Boolean = buckets.add(name)

    fun hasBucket(name: String): Boolean = name in buckets

    fun bucketNames(): List<String> = buckets.sorted()

    fun deleteBucket(name: String): Boolean {
        if (objects.keys.any { it.first == name }) return false
        return buckets.remove(name)
    }

    /**
     * [write] suspends, and that is not decoration: it is fed from a socket, and a blocking bridge
     * here would park a dispatcher thread for the length of an upload. A handful of slow clients
     * would then be indistinguishable from a hung server.
     */
    suspend fun put(
        bucket: String,
        key: ObjectKey,
        contentType: String?,
        write: suspend (java.io.OutputStream) -> Unit,
    ): StoredObject {
        val target = root.resolve("data").resolve(UUID.randomUUID().toString())
        val temporary = Path.of("$target.partial")
        val digest = MessageDigest.getInstance("MD5")
        var size = 0L

        Files.newOutputStream(temporary).use { file ->
            val counting =
                object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        file.write(b)
                        digest.update(b.toByte())
                        size++
                    }

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        file.write(b, off, len)
                        digest.update(b, off, len)
                        size += len
                    }
                }
            write(counting)
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)

        val stored =
            StoredObject(
                path = target,
                size = size,
                eTag = "\"" + digest.digest().joinToString("") { "%02x".format(it) } + "\"",
                lastModified = Instant.now(),
                contentType = contentType,
            )
        objects.put(bucket to key, stored)?.let { previous ->
            // The replaced object's file is unlinked after the index no longer points at it, which
            // is the same order M4 will use for real — a reader holding it open keeps reading.
            Files.deleteIfExists(previous.path)
        }
        return stored
    }

    fun get(
        bucket: String,
        key: ObjectKey,
    ): StoredObject? = objects[bucket to key]

    fun delete(
        bucket: String,
        key: ObjectKey,
    ): Boolean {
        val removed = objects.remove(bucket to key) ?: return false
        Files.deleteIfExists(removed.path)
        return true
    }

    /** Keys of a bucket in the order S3 lists them, which is the order [ObjectKey] defines. */
    fun list(
        bucket: String,
        prefix: ByteArray,
        limit: Int,
    ): List<Pair<ObjectKey, StoredObject>> =
        objects
            .filterKeys { (name, key) -> name == bucket && key.toByteArray().startsWith(prefix) }
            .map { (id, stored) -> id.second to stored }
            .sortedBy { it.first }
            .take(limit)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (prefix.size > size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
