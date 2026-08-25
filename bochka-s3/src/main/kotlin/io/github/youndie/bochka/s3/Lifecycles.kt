package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.s3.xml.S3Requests
import java.util.concurrent.ConcurrentHashMap

/**
 * A bucket's rules, parsed once per document.
 *
 * The core stores a setting as bytes and does not know what `lifecycle` means — that is its
 * decision and it is the right one. The price of that decision is that everybody who needs the
 * rules parses XML, and they are needed **on the read path**: `x-amz-expiration` is computed on
 * every `GET` and `HEAD`.
 *
 * Hence a cache, keyed not by the bucket's name but by the byte array itself, by reference. The
 * store hands back the same reference until the setting is rewritten, so `===` answers exactly the
 * question that matters ("is this the same document I parsed?") and needs neither a version, nor an
 * invalidation, nor a notification on write. Comparing by content would cost a walk of the
 * document, which is about what parsing costs.
 */
class Lifecycles(
    private val store: ObjectStore,
) {
    private val parsed = ConcurrentHashMap<String, Pair<ByteArray, Lifecycle>>()

    /** A bucket's rules, or `null` if it has none. */
    fun of(bucket: String): Lifecycle? {
        val document = store.bucketSubresource(bucket, NAME) ?: return null
        parsed[bucket]?.let { (from, lifecycle) -> if (from === document) return lifecycle }
        // The document in the journal was rendered by this same server, so it has to parse. Has to
        // does not mean will: a journal outlives upgrades, and a rule that is refused today may have
        // been written by yesterday's version. A bucket with no rules is better than a store that
        // answers no request at all.
        val lifecycle = runCatching { S3Requests.parseLifecycle(document) }.getOrNull() ?: return null
        parsed[bucket] = document to lifecycle
        return lifecycle
    }

    companion object {
        /** The setting's name in the store, which is also the subresource's name in a request. */
        const val NAME: String = "lifecycle"
    }
}
