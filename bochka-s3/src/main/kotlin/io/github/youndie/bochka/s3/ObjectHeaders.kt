package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.s3.sigv4.S3Error
import java.nio.charset.StandardCharsets

/**
 * The headers that travel with an object, in both directions.
 *
 * The list is the model's, not a guess: `PutObjectRequest.members` marks `CacheControl`,
 * `ContentDisposition`, `ContentEncoding`, `ContentLanguage`, `ContentType` and `Expires` with
 * `"location": "header"`, and `Metadata` with `"location": "headers", "locationName": "x-amz-meta-"`
 * — the plural is the model's way of saying "every header with this prefix".
 * `GetObjectOutput.members` names the same set on the way back.
 *
 * What is deliberately **not** replayed: `Content-Length` and `ETag` describe the stored object
 * rather than what the client said about it, and `Content-MD5` is a statement about one transfer,
 * not a property of the object.
 */
object ObjectHeaders {
    const val USER_PREFIX = "x-amz-meta-"

    /**
     * AWS caps user metadata at 2 KiB, "measured as the sum of the number of bytes in the UTF-8
     * encoding of each key and value" (`docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html`).
     * Enforced because the index holds it in memory for the life of the object: an unbounded map
     * per key turns the published object ceiling (Р1) into a number that depends on the client.
     */
    const val MAX_USER_BYTES = 2 * 1024

    data class Rejection(
        val error: S3Error,
        val detail: String,
    )

    /** Collects what the head says about the object being written. */
    fun read(headers: List<Pair<String, String>>): Metadata {
        fun one(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second?.trim()

        val user = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val lower = name.lowercase()
            if (lower.startsWith(USER_PREFIX) && lower.length > USER_PREFIX.length) {
                // Repeats of one name join with a comma, which is what HTTP says a repeated
                // field means; dropping one of them would lose data the client sent.
                user[lower] = user[lower]?.let { "$it,${value.trim()}" } ?: value.trim()
            }
        }

        return Metadata(
            contentType = one("content-type"),
            cacheControl = one("cache-control"),
            contentDisposition = one("content-disposition"),
            // `aws-chunked` describes how this one request framed its body, not how the object is
            // encoded, so it is dropped rather than stored — otherwise an object uploaded by
            // aws-cli comes back claiming an encoding no client can undo.
            contentEncoding =
                one("content-encoding")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.equals("aws-chunked", ignoreCase = true) }
                    ?.joinToString(", ")
                    ?.takeIf { it.isNotEmpty() },
            contentLanguage = one("content-language"),
            expires = one("expires"),
            user = user,
        )
    }

    fun checkSize(metadata: Metadata): Rejection? {
        val bytes =
            metadata.user.entries.sumOf {
                it.key.toByteArray(StandardCharsets.UTF_8).size + it.value.toByteArray(StandardCharsets.UTF_8).size
            }
        return if (bytes > MAX_USER_BYTES) {
            Rejection(S3Error.METADATA_TOO_LARGE, "$bytes bytes of user metadata, the limit is $MAX_USER_BYTES")
        } else {
            null
        }
    }

    /**
     * What a `GET` or `HEAD` answers with.
     *
     * `Content-Encoding` is here despite what it usually means to a client. S3 stores it as
     * metadata and replays it: the object was uploaded already encoded, and the server never
     * encodes or decodes anything. A response that dropped it would hand back bytes the client
     * cannot interpret.
     */
    fun write(metadata: Metadata): List<Pair<String, String>> =
        buildList {
            metadata.contentType?.let { add("Content-Type" to it) }
            metadata.cacheControl?.let { add("Cache-Control" to it) }
            metadata.contentDisposition?.let { add("Content-Disposition" to it) }
            metadata.contentEncoding?.let { add("Content-Encoding" to it) }
            metadata.contentLanguage?.let { add("Content-Language" to it) }
            metadata.expires?.let { add("Expires" to it) }
            for ((name, value) in metadata.user) add(name to value)
        }
}
