package io.github.youndie.bochka.core

/**
 * What travelled with the object and has to come back with it.
 *
 * Two groups, and they are kept apart because they behave differently. The named fields are HTTP
 * response headers S3 promises to replay verbatim (`s3-service-2.json`, `GetObjectOutput.members`
 * — `CacheControl`, `ContentDisposition`, `ContentEncoding`, `ContentLanguage`, `ContentType`,
 * `Expires`); [user] is the open-ended `x-amz-meta-*` map, whose names the server never interprets.
 *
 * The user names are stored **lowercased**, which is not tidying: HTTP header names are
 * case-insensitive, so `x-amz-meta-Foo` and `x-amz-meta-foo` are one entry, and keeping both would
 * mean the object has two values for the same metadata depending on how the client asked.
 */
data class Metadata(
    val contentType: String? = null,
    val cacheControl: String? = null,
    val contentDisposition: String? = null,
    val contentEncoding: String? = null,
    val contentLanguage: String? = null,
    val expires: String? = null,
    val user: Map<String, String> = emptyMap(),
    /**
     * The checksum the client stated on upload, kept so `x-amz-checksum-mode: ENABLED` can answer
     * with it. Verified at upload time (M-47); stored because a `GET` cannot recompute a CRC32C of
     * five gigabytes to answer a header.
     */
    val checksum: Checksum? = null,
    /**
     * An object's tags are **not** the same thing as [user], though both look like strings mapped
     * to strings.
     *
     * They have a pair of operations of their own (`PutObjectTagging`/`GetObjectTagging`), bounds of
     * their own (ten tags against two kilobytes of metadata) and a life of their own: tags are
     * changed on an existing object without touching its bytes, while `x-amz-meta-*` arrive with
     * the body and do not change without it. Keeping them in one map would mean that
     * `PutObjectTagging` can rewrite `x-amz-meta-*`.
     */
    val tags: Map<String, String> = emptyMap(),
) {
    data class Checksum(
        /** Lowercase, as it appears in the header name: `crc32`, `crc32c`, `sha1`, `sha256`. */
        val algorithm: String,
        /** Base64, exactly as the client sent it. */
        val value: String,
    )

    companion object {
        val EMPTY = Metadata()
    }
}
