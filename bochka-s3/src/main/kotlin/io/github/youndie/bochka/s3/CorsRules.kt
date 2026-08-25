package io.github.youndie.bochka.s3

/**
 * A bucket's CORS rules and the one question asked of them: does the request match.
 *
 * The shape is in `docs/spec/s3-service-2.json`: `CORSConfiguration` (`:2241`) holds `CORSRule`
 * (`:2253`), which requires `AllowedMethods` and `AllowedOrigins`.
 *
 * Storing and parsing them is dull. One thing here is interesting — **matching the origin** — and
 * it is written by hand on purpose rather than as a regular expression.
 */
data class CorsRules(
    val rules: List<Rule>,
) {
    data class Rule(
        val id: String? = null,
        val allowedMethods: List<String> = emptyList(),
        val allowedOrigins: List<String> = emptyList(),
        val allowedHeaders: List<String> = emptyList(),
        val exposeHeaders: List<String> = emptyList(),
        val maxAgeSeconds: Int? = null,
    )

    /**
     * The first rule that allows this origin, this method **and every header asked about**.
     *
     * The third condition was added in M-177, and without it the server allowed a little more than
     * it was asked to: a preflight asks with `Access-Control-Request-Headers`, and every name in
     * there has to fall under an `AllowedHeader` (`test_cors_header_option:7016`). A rule that named
     * no header allows none — `ExposeHeader` does not help, it is about something else: what the
     * browser will be allowed to **read in the answer**, not what it may ask for.
     *
     * Names are compared case-insensitively because that is how HTTP compares them, and a pattern
     * with an asterisk is matched by the same [matches] the origin is.
     */
    fun matching(
        origin: String,
        method: String,
        requestedHeaders: List<String> = emptyList(),
    ): Rule? =
        rules.firstOrNull { rule ->
            rule.allowedMethods.any { it.equals(method, ignoreCase = true) } &&
                rule.allowedOrigins.any { matches(it, origin) } &&
                requestedHeaders.all { asked ->
                    rule.allowedHeaders.any { matches(it.lowercase(), asked.lowercase()) }
                }
        }

    companion object {
        /**
         * Matching an origin against a pattern in which `*` means "any sequence".
         *
         * **Written by hand, and that is a refusal to allow more rather than a wheel reinvented.**
         * Turning the pattern into a regular expression is the shortest route and it is wrong: in a
         * pattern like `*.example.com` the dot means "any character" to a regular expression, so
         * `appXexample.com` would match. For an access rule, "more matched than should have" is a
         * hole rather than an imprecision.
         *
         * (The examples here deliberately carry no scheme: a slash-slash-asterisk sequence inside a
         * comment opens a **nested** comment — Kotlin's block comments nest — and the rest of the
         * file stops compiling.)
         *
         * S3 allows exactly one asterisk in a pattern; everything else is compared literally, the
         * scheme and the dots included.
         */
        fun matches(
            pattern: String,
            origin: String,
        ): Boolean {
            val star = pattern.indexOf('*')
            if (star < 0) return pattern == origin
            val head = pattern.substring(0, star)
            val tail = pattern.substring(star + 1)
            // `head + tail` longer than the origin means even an empty substitution does not fit —
            // and it also guards against head and tail overlapping on a short origin.
            if (origin.length < head.length + tail.length) return false
            return origin.startsWith(head) && origin.endsWith(tail)
        }
    }
}
