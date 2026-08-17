package io.github.youndie.bochka.s3

/**
 * The `Range` header, resolved against the size of the object it asks about.
 *
 * Two sources, because the header is HTTP's and the answer to a bad one is S3's. The syntax and the
 * arithmetic are RFC 9110 §14.1.2 — `bytes=first-last`, `bytes=first-`, `bytes=-suffix`, positions
 * zero-based and `last` **inclusive**. What to do when it does not resolve is S3's: `416` with
 * `InvalidRange` (`ceph/s3-tests`, `test_ranged_request_invalid_range` and
 * `test_ranged_request_empty_object`).
 *
 * The third outcome is the one that reads as wrong and is not. A header that does not parse, or
 * that asks for several ranges, is **ignored** — the whole object comes back with `200`, not an
 * error. That is what RFC 9110 §14.2 requires of a recipient that cannot honour a range, and S3
 * takes it further by not supporting multiple ranges at all ("Amazon S3 doesn't support retrieving
 * multiple ranges of data per GET request", `GetObject` documentation). A server that answered
 * `416` here would fail requests that S3 serves.
 */
object ByteRanges {
    sealed interface Resolved {
        /** `206`, with `Content-Range: bytes <start>-<endInclusive>/<size>`. */
        data class Satisfiable(
            val start: Long,
            val endInclusive: Long,
        ) : Resolved {
            val length: Long get() = endInclusive - start + 1
        }

        /** `416`, with a `Content-Range` that names the size and no range — see [unsatisfiedRange]. */
        data object Unsatisfiable : Resolved

        /** `200` with everything, as if no range had been asked for. */
        data object Whole : Resolved
    }

    fun resolve(
        header: String?,
        size: Long,
    ): Resolved {
        val value = header?.trim() ?: return Resolved.Whole
        if (!value.startsWith(UNIT, ignoreCase = true)) return Resolved.Whole

        val spec = value.substring(UNIT.length).trim()
        // A comma means more than one range even when the second half is nonsense, and either way
        // this server does not serve multipart/byteranges.
        if (spec.isEmpty() || ',' in spec) return Resolved.Whole

        val dash = spec.indexOf('-')
        if (dash < 0) return Resolved.Whole
        val firstText = spec.substring(0, dash).trim()
        val lastText = spec.substring(dash + 1).trim()

        if (firstText.isEmpty()) {
            // `bytes=-N`: the last N bytes. N of zero asks for nothing, which is unsatisfiable
            // rather than an empty success — there is no way to say "no bytes" in a Content-Range.
            val suffix = lastText.toLongOrNull() ?: return Resolved.Whole
            if (suffix <= 0) return Resolved.Unsatisfiable
            if (size == 0L) return Resolved.Unsatisfiable
            val start = maxOf(0L, size - suffix)
            return Resolved.Satisfiable(start, size - 1)
        }

        val first = firstText.toLongOrNull() ?: return Resolved.Whole
        if (first < 0) return Resolved.Whole
        // Past the end of the object is the case `416` exists for: the client asked for bytes that
        // are not there, and answering `200` with different bytes would be worse than refusing.
        if (first >= size) return Resolved.Unsatisfiable

        if (lastText.isEmpty()) return Resolved.Satisfiable(first, size - 1)
        val last = lastText.toLongOrNull() ?: return Resolved.Whole
        if (last < first) return Resolved.Whole
        return Resolved.Satisfiable(first, minOf(last, size - 1))
    }

    fun contentRange(
        resolved: Resolved.Satisfiable,
        size: Long,
    ): String = "bytes ${resolved.start}-${resolved.endInclusive}/$size"

    /** What a `416` says instead: the range is unknown, the size is not. */
    fun unsatisfiedRange(size: Long): String = "bytes */$size"

    private const val UNIT = "bytes="
}
