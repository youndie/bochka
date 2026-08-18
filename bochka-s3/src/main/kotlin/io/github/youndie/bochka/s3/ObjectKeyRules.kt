package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey

/**
 * What bochka is willing to accept as an object key.
 *
 * The list is short, and that is the point. A store that lays objects out under their keys has to
 * forbid whatever its filesystem cannot represent, and the reference implementation ships exactly
 * that as API errors: `XMinioObjectExistsAsDirectory` when a key would be both a file and a
 * directory, `XMinioInvalidObjectName` for `a//b`, `XMinioInvalidResourceName` for a `.` or `..`
 * segment (`minio/minio`, `cmd/api-errors.go:1309-1325`) — all keys real S3 accepts.
 *
 * Here the key never reaches the filesystem: an object is a UUID on disk and the key lives in the
 * index (research, Р2). So none of those restrictions apply, and the tests say so by name — the
 * whole payoff of that decision is the keys this file does *not* reject.
 *
 * What remains are the two limits that come from S3 itself rather than from a disk.
 */
object ObjectKeyRules {
    /**
     * 1024 bytes, and note that it is bytes and not characters — a key of 400 emoji is over the
     * limit. Absent from the machine model (`shapes.ObjectKey` states only `min: 1`), so it comes
     * from the prose documentation; recorded here rather than in a comment somewhere because it is
     * the kind of number that otherwise gets re-invented.
     */
    const val MAX_LENGTH_BYTES: Int = 1024

    /** Why a key was refused. `null` from [check] means it was not. */
    enum class Rejection(
        val code: String,
        val message: String,
    ) {
        /**
         * The model gives `ObjectKey` a `min: 1`. In practice an empty key is unreachable through
         * routing — `PUT /bucket/` is a bucket request — but the storage layer must never be handed
         * one, and "unreachable" is not a property worth relying on.
         */
        EMPTY("InvalidURI", "Couldn't parse the specified URI"),

        /** `KeyTooLongError`, 400 — the same code the reference server returns. */
        TOO_LONG("KeyTooLongError", "Your key is too long"),

        /**
         * The bytes are not well-formed UTF-8.
         *
         * This is not a retreat from "a key is a byte string" (Р3) — it is the other half of it.
         * The **order** of keys is defined by their bytes, which is why nothing here sorts or
         * compares them as text; their **validity** is defined by UTF-8, because S3 says so ("a
         * sequence of Unicode characters whose UTF-8 encoding is at most 1024 bytes"). Two
         * different questions about the same bytes, and conflating them is how a store ends up
         * either sorting wrongly or accepting keys no client can ask for again.
         *
         * `ceph/s3-tests`, `test_object_read_unreadable`: a `GET` of `\xae\x8a-` is a `400`, and
         * this server answered `404` — which tells the client the key is merely absent and that
         * writing it would work.
         */
        NOT_UTF8("InvalidURI", "Couldn't parse the specified URI"),
    }

    fun check(key: ObjectKey): Rejection? =
        when {
            key.size == 0 -> Rejection.EMPTY
            key.size > MAX_LENGTH_BYTES -> Rejection.TOO_LONG
            !isWellFormedUtf8(key.toByteArray()) -> Rejection.NOT_UTF8
            else -> null
        }

    /**
     * The JDK's decoder rather than a hand-rolled scan, set to report instead of replace.
     *
     * `String(bytes, UTF_8)` cannot answer this question: it substitutes `U+FFFD` for anything
     * malformed and returns successfully, so a check written that way says every byte string is
     * fine. The decoder configured to report is the same code path with the failure left in.
     */
    private fun isWellFormedUtf8(bytes: ByteArray): Boolean =
        try {
            java.nio.charset.StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }
}
