package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.s3.sigv4.S3Error
import io.github.youndie.bochka.s3.xml.S3Requests
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

        // `x-amz-tagging: a=1&b=2` — теги формой запроса, а не документом
        // (`s3-service-2.json:3158`). Клиент, положивший объект одним запросом, иначе вынужден
        // делать второй только ради тегов.
        val tags =
            one("x-amz-tagging")
                ?.takeIf { it.isNotEmpty() }
                ?.let { stated ->
                    // Процентная раскодировка бросает на испорченной последовательности, и бросает
                    // отсюда — из `screen`, до чтения тела. Своего типа у отказа не было, поэтому он
                    // улетал мимо цикла запроса и клиент получал закрытый сокет вместо `400`.
                    try {
                        S3Requests.parseTaggingHeader(stated)
                    } catch (e: IllegalArgumentException) {
                        throw Malformed("x-amz-tagging: ${e.message}")
                    }
                }.orEmpty()

        return Metadata(
            tags = tags,
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

    /** A header that is present and cannot be read at all. Typed, so a caller can answer `400`. */
    class Malformed(
        override val message: String,
    ) : RuntimeException(message)

    /**
     * Everything about the head that can be refused before a byte of the body is read (§1.2).
     *
     * Both halves are limits on what the client said about the object rather than on the object,
     * so both are decidable here — and the tag half was not checked at all until M-176, which is
     * how `x-amz-tagging` with eleven tags became an object with eleven tags.
     */
    fun check(metadata: Metadata): Rejection? {
        val bytes =
            metadata.user.entries.sumOf {
                it.key.toByteArray(StandardCharsets.UTF_8).size + it.value.toByteArray(StandardCharsets.UTF_8).size
            }
        if (bytes > MAX_USER_BYTES) {
            return Rejection(S3Error.METADATA_TOO_LARGE, "$bytes bytes of user metadata, the limit is $MAX_USER_BYTES")
        }
        return TagRules.check(metadata.tags)?.let { Rejection(S3Error.INVALID_TAG, it.message) }
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
