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
    }

    fun check(key: ObjectKey): Rejection? =
        when {
            key.size == 0 -> Rejection.EMPTY
            key.size > MAX_LENGTH_BYTES -> Rejection.TOO_LONG
            else -> null
        }
}
