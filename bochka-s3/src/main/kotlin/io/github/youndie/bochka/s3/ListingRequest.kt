package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.S3Error
import java.util.Base64

/**
 * The query parameters of a listing, and where the next page resumes from.
 *
 * Two operations share this: `ListObjectsV2` (`list-type=2`) resumes with an opaque
 * `continuation-token`, `ListObjects` resumes with a `marker` that is the key itself. The
 * difference is only in what the string looks like on the wire — both mean "start after this
 * position in key order" — so it is resolved here into one [startAfter] and the rest of the server
 * never learns which version it is answering.
 *
 * ## What the token means when somebody else is writing
 *
 * Open question 1 of the research, and the answer chosen here (M-52): the token is a **position in
 * key order**, not a snapshot. A key written before that position after the page was served will
 * not appear in any later page of the same walk; a key written after it will. Nothing is ever
 * returned twice, and nothing that existed unchanged for the whole of the walk is ever missed.
 *
 * The alternative — a token that pins a view of the bucket — needs the index to keep every version
 * a running listing might still want, which is versioning by another name, and this store keeps one
 * version of a key on purpose. S3 itself promises no more than this.
 */
class ListingRequest(
    val prefix: ByteArray,
    val delimiter: ByteArray?,
    /** What the page is actually bounded by: the requested value, capped at [MAX_KEYS_LIMIT]. */
    val maxKeys: Int,
    /** What goes back in `MaxKeys`, which is what the client asked for even when it was too much. */
    val requestedMaxKeys: Int,
    val startAfter: ByteArray?,
    val encodeKeys: Boolean,
    /** The token exactly as it arrived, so it can be echoed back in `ContinuationToken`. */
    val continuationToken: String?,
    /** `start-after` as it arrived; `StartAfter` is echoed even when a token overrode it. */
    val startAfterParameter: ByteArray?,
    val marker: ByteArray?,
) {
    class Malformed(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        /** AWS caps a page at 1000 whatever `max-keys` says, and so does every reference server. */
        const val MAX_KEYS_LIMIT = 1000

        fun of(params: Map<String, ByteArray>): ListingRequest {
            val requested =
                params["max-keys"]?.let { raw ->
                    val text = String(raw).trim()
                    text.toIntOrNull()
                        ?: throw Malformed(S3Error.INVALID_ARGUMENT, "max-keys is not a number: '$text'")
                }
            if (requested != null && requested < 0) {
                throw Malformed(S3Error.INVALID_ARGUMENT, "max-keys must not be negative")
            }

            val token = params["continuation-token"]?.let(::String)?.takeIf { it.isNotEmpty() }
            val startAfterParameter = params["start-after"]?.takeIf { it.isNotEmpty() }
            val marker = params["marker"]?.takeIf { it.isNotEmpty() }

            // A token wins over start-after: the client is continuing a walk it began, and the
            // parameter it sent on the first request is still on the query string of every later
            // one because that is how the SDKs build them.
            val startAfter = token?.let(::decodeToken) ?: startAfterParameter ?: marker

            return ListingRequest(
                prefix = params["prefix"] ?: ByteArray(0),
                delimiter = params["delimiter"]?.takeIf { it.isNotEmpty() },
                maxKeys = (requested ?: MAX_KEYS_LIMIT).coerceAtMost(MAX_KEYS_LIMIT),
                requestedMaxKeys = requested ?: MAX_KEYS_LIMIT,
                startAfter = startAfter,
                encodeKeys = params["encoding-type"]?.let { String(it) }.equals("url", ignoreCase = true),
                continuationToken = token,
                startAfterParameter = startAfterParameter,
                marker = marker,
            )
        }

        /**
         * A position, encoded so that it is opaque and survives a query string.
         *
         * Base64 without padding, because the token travels as a query parameter and an `=` there
         * is a parameter separator to something in every deployment. It is not encryption and does
         * not pretend to be: what it buys is that a client cannot build one by hand and then depend
         * on the shape of it.
         */
        fun encodeToken(position: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(position)

        private fun decodeToken(token: String): ByteArray =
            try {
                Base64.getUrlDecoder().decode(token)
            } catch (_: IllegalArgumentException) {
                throw Malformed(S3Error.INVALID_ARGUMENT, "continuation-token is not one this server issued")
            }
    }
}
