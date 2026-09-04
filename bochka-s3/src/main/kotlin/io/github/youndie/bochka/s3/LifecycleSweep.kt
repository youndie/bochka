package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import java.time.Duration
import java.time.Instant

/**
 * What the rules are accepted for in the first place: a sweep that deletes.
 *
 * A configuration the server stores and hands back but does not apply is the `PutBucketPolicy` of
 * the "what not to do" list: the client believes the rule is in place and learns otherwise from a
 * storage bill rather than from an error. So parsing the rules and this file are one milestone, and
 * the first of them alone would have been harmful.
 *
 * ## What happens here and what does not
 *
 * Four actions, and all four are deletions: the current version, noncurrent versions, an orphaned
 * tombstone, an abandoned multipart upload. There are no transitions between storage classes
 * because there is one class; a rule carrying `<Transition>` never reaches this file — it is
 * refused on the write.
 *
 * The sweep goes **through the store's public API** rather than through the index. Not for
 * tidiness: rules are the S3 protocol, and `ObjectStore` knows nothing about S3 and must not, or
 * tags, sizes and tombstones would have to be explained to the core. The cost is one extra
 * `versions()` pass per key, and it is paid by a background thread once a period.
 */
class LifecycleSweep(
    private val store: ObjectStore,
    private val lifecycles: Lifecycles,
    /**
     * How long a rule's "day" lasts.
     *
     * Twenty-four hours as shipped. Shorter in the embedded mode and in a run of the foreign suite,
     * where a rule saying "after a day" is otherwise untestable: nobody runs a test that waits a
     * day.
     */
    private val day: Duration = Lifecycle.DAY,
) {
    /** What the sweep did. Zero on all four is the ordinary outcome and not worth printing. */
    data class Report(
        val objects: Int = 0,
        val versions: Int = 0,
        val markers: Int = 0,
        val uploads: Int = 0,
    ) {
        val empty: Boolean get() = objects == 0 && versions == 0 && markers == 0 && uploads == 0

        override fun toString(): String =
            "expired $objects objects, $versions noncurrent versions, $markers delete markers, " +
                "aborted $uploads uploads"
    }

    /**
     * [now] defaults to the **store's** clock rather than to this JVM's: a term is measured from a
     * `lastModified` the store stamped, and the two sides of that subtraction have to be the same
     * clock.
     */
    fun sweep(now: Instant = store.clock()): Report {
        var report = Report()
        for (bucket in store.bucketNames()) {
            val lifecycle = lifecycles.of(bucket) ?: continue
            val rules = lifecycle.enabled
            if (rules.isEmpty()) continue
            report = report + sweepBucket(bucket, lifecycle, rules, now)
        }
        return report
    }

    private fun sweepBucket(
        bucket: String,
        lifecycle: Lifecycle,
        rules: List<Lifecycle.Rule>,
        now: Instant,
    ): Report {
        var report = Report()
        var marker: ByteArray? = null
        while (true) {
            // The page is only here to enumerate **keys**: the versions of each are fetched
            // separately and in full. Otherwise continuation would have to happen inside a key, on
            // a pair of markers — and the sweep deletes as it goes, so a marker pointing at a
            // deleted version finds nothing. Another housekeeper already stepped on that (M-107);
            // here it is avoided by keeping continuation always on a key boundary.
            val page = store.versionPage(bucket, keyMarker = marker)
            val keys = LinkedHashSet(page.versions.map { it.key })
            for (key in keys) report = report + sweepKey(bucket, lifecycle, rules, key, now)
            if (!page.isTruncated) break
            marker = page.nextKeyMarker ?: break
        }
        return report + sweepUploads(bucket, rules, now)
    }

    private fun sweepKey(
        bucket: String,
        lifecycle: Lifecycle,
        rules: List<Lifecycle.Rule>,
        key: ObjectKey,
        now: Instant,
    ): Report {
        var versions = 0
        // Noncurrent versions first, and the order carries meaning: while even one version remains
        // under a tombstone it is not orphaned, and the rule about orphaned tombstones does not
        // apply. `test_lifecycle_deletemarker_expiration:9361` checks exactly this sequence — the
        // version expires, the tombstone is left alone, and only then goes itself.
        val stored = store.versions(bucket, key)
        for ((index, version) in stored.drop(1).withIndex()) {
            val rule =
                rules.firstOrNull {
                    it.noncurrent != null && it.matches(key, version.size, version.metadata.tags)
                } ?: continue
            val noncurrent = rule.noncurrent!!
            // `NewerNoncurrentVersions`: that many of the newest noncurrent versions outlive the
            // term regardless of age. Counted from the current one down, so the index in the list
            // is the number.
            if (noncurrent.newerVersions != null && index < noncurrent.newerVersions) continue
            if (version.lastModified.plus(day.multipliedBy(noncurrent.days.toLong())).isAfter(now)) continue
            if (remove(bucket, key, version.versionId)) versions++
        }

        val remaining = store.versions(bucket, key)
        val current = remaining.firstOrNull() ?: return Report(versions = versions)

        if (!current.deleteMarker) {
            val due =
                lifecycle.expiryOf(key, current.size, current.metadata.tags, current.lastModified, day)
                    ?: return Report(versions = versions)
            if (due.first.isAfter(now)) return Report(versions = versions)
            // `delete` rather than `deleteVersion`: in a versioning bucket the current version
            // reaching its term lays a tombstone and leaves the version under it, exactly as an
            // ordinary delete does. A term is not "erase", it is "treat as deleted".
            //
            // And **conditional on the very version** the sweep looked at. Between the read and the
            // delete a client has time to write a new one: without the condition the sweep would
            // delete a fresh object on a term that expired for its predecessor — quietly, rarely
            // and unreproducibly. The condition turns the race into a skipped round, and the next
            // round is a second away.
            return try {
                store.delete(
                    bucket,
                    key,
                    ObjectStore.Precondition(ifMatch = listOf(current.eTag), size = current.size),
                )
                Report(objects = 1, versions = versions)
            } catch (_: ObjectStore.PreconditionFailed) {
                // **This refusal and no other** (M-207). What stood here was
                // `runCatching { … }.fold({ deleted }, { did not delete })`, and it turned any
                // exception into "there was nothing to delete": a genuinely broken sweep reported a
                // zero exactly as a sweep with no work does — quietly, every round, while objects
                // with a term stopped disappearing.
                //
                // A precondition that did not hold is not an error: between the read and the delete
                // the client wrote a new version, the sweep skips a round, and the next one is a
                // second away. Everything else — a broken disk, a damaged index, a mistake in the
                // code — goes out, where the background loop prints it and carries on
                // (`Main.startLifecycle`). `remove()` sixty lines below catches just as narrowly
                // and for the same reason.
                Report(versions = versions)
            }
        }

        // An orphaned tombstone: nothing is left under it, and it is itself the only trace of the
        // key. `ExpiredObjectDeleteMarker` takes it away at once; `Days`/`Date` do so on their term.
        if (remaining.size != 1) return Report(versions = versions)
        val rule =
            rules.firstOrNull {
                it.expiration != null && it.matches(key, 0, emptyMap())
            } ?: return Report(versions = versions)
        val expiration = rule.expiration!!
        val due =
            if (expiration.expiredObjectDeleteMarker) {
                current.lastModified
            } else {
                Lifecycle.expiresAt(expiration, current.lastModified, day) ?: return Report(versions = versions)
            }
        if (due.isAfter(now)) return Report(versions = versions)
        return Report(versions = versions, markers = if (remove(bucket, key, current.versionId)) 1 else 0)
    }

    /**
     * Abandoned uploads, and of a filter only its prefix reaches them.
     *
     * An upload that has begun has neither a size nor tags: there is no object yet. A rule naming a
     * tag or a size therefore does not apply to it **at all** — rather than applying by halves. The
     * difference shows on `ObjectSizeLessThan`: an upload that has written nothing "weighs" zero,
     * so it would match any such threshold and be aborted on a condition nobody ever evaluated
     * about it.
     */
    private fun sweepUploads(
        bucket: String,
        rules: List<Lifecycle.Rule>,
        now: Instant,
    ): Report {
        var aborted = 0
        for (upload in store.uploads(bucket)) {
            val rule =
                rules.firstOrNull { rule ->
                    rule.abortIncompleteUploadDays != null &&
                        rule.aboutKeysOnly() &&
                        rule.matches(upload.key, 0, emptyMap())
                } ?: continue
            val days = rule.abortIncompleteUploadDays!!
            if (upload.startedAt.plus(day.multipliedBy(days.toLong())).isAfter(now)) continue
            if (store.abortUpload(upload.id)) aborted++
        }
        return Report(uploads = aborted)
    }

    /**
     * Deleting a version, quietly passing over the protected ones.
     *
     * A version under retention or under a legal hold is not deleted by this sweep or by anything
     * else, and a lifecycle rule is no exception: a lock is a promise that outranks a term. A
     * refusal here is not an error of the sweep, so it does not stop the remaining versions.
     */
    private fun remove(
        bucket: String,
        key: ObjectKey,
        versionId: String,
    ): Boolean =
        try {
            store.deleteVersion(bucket, key, versionId) != null
        } catch (_: ObjectStore.Locked) {
            false
        }

    /** A rule that selects by key name alone: no tags and no sizes at any level. */
    private fun Lifecycle.Rule.aboutKeysOnly(): Boolean {
        val f = filter ?: return true
        if (f.tags.isNotEmpty() || f.sizeGreaterThan != null || f.sizeLessThan != null) return false
        val and = f.and ?: return true
        return and.tags.isEmpty() && and.sizeGreaterThan == null && and.sizeLessThan == null
    }

    private operator fun Report.plus(other: Report): Report =
        Report(
            objects + other.objects,
            versions + other.versions,
            markers + other.markers,
            uploads + other.uploads,
        )
}
