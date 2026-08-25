package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A bucket's lifecycle rules, and the two questions asked of them: does the object match, and when
 * is its term.
 *
 * The shape is in `docs/spec/s3-service-2.json`: `BucketLifecycleConfiguration` (`:2127`) holds
 * `LifecycleRule` (`:7896`), which requires one `Status` (`ExpirationStatus`, `:4881` — exactly
 * `Enabled` or `Disabled`) and nothing else.
 *
 * ## Why a rule is kept in the shape it arrived in
 *
 * A rule's prefix lives in two places: `<Prefix>` in the rule itself (marked `deprecated` in the
 * model) and `<Prefix>` inside `<Filter>`. Those are two different documents, and
 * `GetBucketLifecycle` has to return the one that was sent: `test_lifecycle_get:8451` compares
 * whole rules, and a rule that arrived with the old prefix and left with a filter is a different
 * rule. Hence [Rule.prefix] and [Filter.prefix] are separate fields rather than one normalised one.
 *
 * ## Filter conditions add up, all of them
 *
 * S3 requires a `<Filter>` to hold **exactly one** member: a prefix, a tag, or an `<And>` with a
 * list. The suite sends documents that disobey that — `Prefix` together with `Tag`
 * (`test_lifecycle_expiration_tags1:8620`), or even `Prefix`, `Tag` and `And` all three
 * (`setup_lifecycle_tags2:8667`); both cases are marked `fails_on_aws`, so the real S3 refuses
 * them. Here they are accepted, and the object has to satisfy **every** condition named.
 *
 * That is a choice in favour of the only sensible reading rather than a leniency: a filter
 * enumerates properties of an object, and an object either has all of them or does not match.
 * Refusing the document would also have been defensible — but then a rule the client believes is in
 * place is not in place, and it finds out from a deletion that never happened.
 */
data class Lifecycle(
    val rules: List<Rule>,
) {
    data class Rule(
        val id: String,
        val enabled: Boolean,
        /** The rule's own `<Prefix>` — the old form, marked `deprecated` in the model. */
        val prefix: String? = null,
        val filter: Filter? = null,
        val expiration: Expiration? = null,
        val noncurrent: Noncurrent? = null,
        /** `AbortIncompleteMultipartUpload.DaysAfterInitiation` (`:1653`). */
        val abortIncompleteUploadDays: Int? = null,
    ) {
        /**
         * Whether an object matches the rule.
         *
         * Tags are a map rather than a list: on an object they are unique by key, and a condition
         * `tom=sawyer` means "there is a tag with this key and this value" rather than "such a pair
         * exists somewhere among the duplicates".
         */
        fun matches(
            key: ObjectKey,
            size: Long,
            tags: Map<String, String>,
        ): Boolean {
            val bytes = key.toByteArray()
            if (prefix != null && !startsWith(bytes, prefix)) return false
            val f = filter ?: return true
            if (f.prefix != null && !startsWith(bytes, f.prefix)) return false
            if (!f.tags.all { tags[it.key] == it.value }) return false
            if (f.sizeGreaterThan != null && size <= f.sizeGreaterThan) return false
            if (f.sizeLessThan != null && size >= f.sizeLessThan) return false
            val and = f.and ?: return true
            if (and.prefix != null && !startsWith(bytes, and.prefix)) return false
            if (!and.tags.all { tags[it.key] == it.value }) return false
            if (and.sizeGreaterThan != null && size <= and.sizeGreaterThan) return false
            if (and.sizeLessThan != null && size >= and.sizeLessThan) return false
            return true
        }

        /**
         * Which prefix the rule names, whichever place it names it in. For abandoned uploads: a
         * multipart upload has neither a size nor tags, so this is all of a filter that reaches it.
         */
        fun statedPrefix(): String? = prefix ?: filter?.prefix ?: filter?.and?.prefix
    }

    /** `LifecycleRuleFilter` (`:7960`). */
    data class Filter(
        val prefix: String? = null,
        val tags: List<Tag> = emptyList(),
        val sizeGreaterThan: Long? = null,
        val sizeLessThan: Long? = null,
        val and: And? = null,
    )

    /** `LifecycleRuleAndOperator` (`:7936`) — the same thing, with a list of tags. */
    data class And(
        val prefix: String? = null,
        val tags: List<Tag> = emptyList(),
        val sizeGreaterThan: Long? = null,
        val sizeLessThan: Long? = null,
    )

    data class Tag(
        val key: String,
        val value: String,
    )

    /**
     * `LifecycleExpiration` (`:7878`): a term in days, a term as a date, or the removal of an
     * orphaned tombstone.
     *
     * Three optional members and no required one, because the rule is about different things:
     * `Days`/`Date` are about the object itself, `ExpiredObjectDeleteMarker` about a tombstone with
     * no versions left under it.
     */
    data class Expiration(
        val days: Int? = null,
        val date: Instant? = null,
        val expiredObjectDeleteMarker: Boolean = false,
    )

    /**
     * `NoncurrentVersionExpiration` (`:9378`).
     *
     * [newerVersions] is how many of the **newest** noncurrent versions sit the term out regardless
     * of age: `NewerNoncurrentVersions: 5` with ten versions keeps the current one and the five
     * below it, and deletes the bottom four.
     */
    data class Noncurrent(
        val days: Int,
        val newerVersions: Int? = null,
    )

    /** The rules that currently do anything. A disabled rule is stored and not carried out. */
    val enabled: List<Rule> get() = rules.filter { it.enabled }

    /**
     * When an object's term expires and under which rule — or `null` if under none.
     *
     * This answers both the `x-amz-expiration` header and the sweep: one place, because a header
     * promising one term and a sweep deleting at another is the worst of the available outcomes.
     * The first matching rule rather than the earliest one: S3 forbids overlapping rules, and
     * choosing between them would be pretending that a document which should never have arrived
     * means something.
     */
    fun expiryOf(
        key: ObjectKey,
        size: Long,
        tags: Map<String, String>,
        created: Instant,
        day: Duration,
    ): Pair<Instant, Rule>? {
        for (rule in enabled) {
            val expiration = rule.expiration ?: continue
            if (!rule.matches(key, size, tags)) continue
            val at = expiresAt(expiration, created, day) ?: continue
            return at to rule
        }
        return null
    }

    companion object {
        /**
         * How long a "day" lasts by default — twenty-four hours, the only value at which rounding
         * to midnight means anything.
         */
        val DAY: Duration = Duration.ofDays(1)

        /** The `ID` length bound (`shapes.ID`, the `PutBucketLifecycleConfiguration` docs). */
        const val MAX_ID_LENGTH: Int = 255

        /**
         * The instant a rule's term expires, or `null` if the rule is about a tombstone rather than
         * about a term.
         *
         * **Rounding up to midnight UTC happens only when a "day" is a real twenty-four hours.** S3
         * rounds because its day is a calendar one: the expiry date is the creation date plus
         * `Days`, rounded up to the next midnight UTC. When the unit is shortened for a test (see
         * `BOCHKA_LIFECYCLE_DAY_SECONDS`) there is no calendar at all, and rounding to midnight
         * would push the term a whole day out — which would undo the shortening.
         */
        fun expiresAt(
            expiration: Expiration,
            created: Instant,
            day: Duration,
        ): Instant? {
            expiration.date?.let { return it }
            val days = expiration.days ?: return null
            val due = created.plus(day.multipliedBy(days.toLong()))
            if (day != DAY) return due
            val midnight = due.truncatedTo(ChronoUnit.DAYS)
            return if (midnight == due) due else midnight.plus(DAY)
        }

        private fun startsWith(
            key: ByteArray,
            prefix: String,
        ): Boolean {
            val bytes = prefix.toByteArray(StandardCharsets.UTF_8)
            if (key.size < bytes.size) return false
            for (i in bytes.indices) if (key[i] != bytes[i]) return false
            return true
        }
    }
}
