package io.github.youndie.bochka.core

import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * An object key: a string of bytes, ordered the way S3 orders it.
 *
 * Bytes rather than [String], and that is a decision rather than a detail (research, Р3). Two
 * independent reasons:
 *
 * * **ordering.** `ListObjectsV2` returns keys "in lexicographical order based on their key names"
 *   (`docs/spec/s3-service-2.json`, "Sorting order of returned objects"), which for byte strings is
 *   unsigned byte order. `String.compareTo` compares UTF-16 code units and disagrees with it on
 *   every key outside the BMP — silently, and only on keys nobody puts in a test. Pagination is
 *   defined in terms of that order, so getting it wrong is not "listings look odd", it is objects
 *   skipped and objects repeated between pages;
 * * **representation.** A key may hold bytes that are not valid UTF-8 and bytes that XML 1.0 cannot
 *   represent — which is why `encoding-type=url` exists in the response at all. A key that has been
 *   through `String` has already lost them.
 *
 * The type carries no policy: what bochka is willing to accept as a key — length, empty, `//`, the
 * `.` and `..` segments — is decided where requests are parsed, not here, because those are
 * protocol answers and this is storage.
 *
 * Instances are immutable: the array is copied in and copied out. The copy is paid once per key at
 * parse time, and it buys the only thing that makes a key usable as a map key at all.
 */
class ObjectKey(
    bytes: ByteArray,
) : Comparable<ObjectKey> {
    private val bytes: ByteArray = bytes.copyOf()

    // Keys are compared and hashed far more often than they are created, and the bytes never
    // change. Not lazy on purpose: a race on a lazy field would be a lock in the hot path.
    private val hash: Int = this.bytes.contentHashCode()

    val size: Int get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    /**
     * Unsigned byte order, which is what "lexicographical" means for a byte string.
     *
     * `Arrays.compareUnsigned` and not a hand-written loop: the signed comparison a loop over
     * `ByteArray` invites puts every byte above 0x7F before `a`, so the first non-ASCII key in a
     * bucket lands at the top of the listing.
     */
    override fun compareTo(other: ObjectKey): Int = Arrays.compareUnsigned(bytes, other.bytes)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ObjectKey && hash == other.hash && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = hash

    /**
     * For diagnostics only, and lossy on purpose: a key need not be text, so this replaces what it
     * cannot decode. Never use it to reconstruct a key — that is what [toByteArray] is for.
     */
    override fun toString(): String = String(bytes, StandardCharsets.UTF_8)

    companion object {
        /** Encodes as UTF-8. Keys that arrive as text — from a test, or from a decoded URL path. */
        fun of(text: String): ObjectKey = ObjectKey(text.toByteArray(StandardCharsets.UTF_8))
    }
}
