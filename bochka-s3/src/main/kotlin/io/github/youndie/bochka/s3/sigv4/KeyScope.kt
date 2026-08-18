package io.github.youndie.bochka.s3.sigv4

/**
 * What one access key is allowed to do, and where.
 *
 * This is not ACLs, not bucket policies and not IAM, and the distinction is the point of the whole
 * milestone. Behind "are there any permissions?" a single-node store is almost always asked one
 * concrete thing: **give the backup job a key that cannot delete and cannot see other people's
 * buckets.** That is two fields beside a key in configuration — no stored state, no policy
 * language, nothing to version or replicate.
 *
 * Deliberately absent: anything per-object, anything per-user, anything that grants **more** than
 * the key already has. A scope only ever narrows.
 */
data class KeyScope(
    val mode: Mode = Mode.RW,
    /** Empty means every bucket. A non-empty list is exhaustive: nothing outside it exists. */
    val buckets: Set<String> = emptySet(),
) {
    enum class Mode {
        RO,
        RW,
    }

    fun sees(bucket: String): Boolean = buckets.isEmpty() || bucket in buckets

    fun allows(need: Need): Boolean =
        when (need) {
            Need.READ -> true
            Need.WRITE -> mode == Mode.RW
        }

    /**
     * What an operation needs from a key.
     *
     * Two values and not three: there is no "admin". Creating a bucket and deleting an object are
     * the same requirement here, because a key that may do one and not the other is a policy
     * language, and the milestone exists precisely to not become one.
     */
    enum class Need {
        READ,
        WRITE,
    }

    companion object {
        /**
         * Parses `id=ro`, `id=rw`, `id=ro@photos|reports`.
         *
         * A **separate** setting from `keys`, and that is not tidiness. Today's `id:secret` splits
         * on the first colon and treats everything after it as the secret, so a secret may contain
         * one; adding suffixes there would change how an existing secret parses, and a format that
         * falls apart on somebody's secret is worse than not having the feature. Buckets are
         * separated by `|` because `,` already separates keys.
         *
         * A key absent from this setting keeps everything it had. Configuration that only ever
         * narrows cannot lock an operator out of a store by being written wrong.
         */
        fun parse(entries: List<String>): Map<String, KeyScope> =
            entries.filter { it.isNotBlank() }.associate { entry ->
                val equals = entry.indexOf('=')
                require(equals > 0) { "a key scope looks like id=ro or id=rw@bucket|bucket, got '$entry'" }
                val id = entry.substring(0, equals).trim()
                val rest = entry.substring(equals + 1).trim()
                val at = rest.indexOf('@')
                val modeText = (if (at < 0) rest else rest.substring(0, at)).trim().lowercase()
                val mode =
                    when (modeText) {
                        "ro" -> Mode.RO
                        "rw" -> Mode.RW
                        else -> throw IllegalArgumentException("a key scope mode is ro or rw, got '$modeText'")
                    }
                val buckets =
                    if (at < 0) {
                        emptySet()
                    } else {
                        rest
                            .substring(at + 1)
                            .split('|')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    }
                id to KeyScope(mode, buckets)
            }
    }
}
