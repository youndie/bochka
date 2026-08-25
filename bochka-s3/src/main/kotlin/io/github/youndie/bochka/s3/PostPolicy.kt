package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.S3Error
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * The policy of a POST form: what the client is **allowed to put**, signed by the key's owner.
 *
 * The document is JSON in base64, and it is signed whole, as a string. Hence a rule that is easy to
 * break: **what must be checked is the string that arrived**, not the result of parsing it. JSON
 * reassembled from a parse differs from what was sent in whitespace and order, and the signature
 * will not match it — and if the signature were computed over the reassembled form, the signature
 * would be over our representation rather than the client's.
 *
 * The parser here is hand-written and deliberately narrow: what is needed is `expiration` and
 * `conditions`, where a condition is either an object of one pair, or a triple
 * `["starts-with", "$field", "prefix"]`, or `["content-length-range", from, to]`. Dragging a
 * general-purpose JSON parser in here would mean a dependency for the sake of five written forms.
 */
object PostPolicy {
    class Refused(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    sealed interface Condition {
        data class Exact(
            val field: String,
            val value: String,
        ) : Condition

        data class StartsWith(
            val field: String,
            val prefix: String,
        ) : Condition

        data class LengthRange(
            val from: Long,
            val to: Long,
        ) : Condition
    }

    data class Policy(
        val expiration: Instant,
        val conditions: List<Condition>,
    )

    /**
     * The fields that take no part in the conditions.
     *
     * They are sent by the browser or by whoever signed, and demanding a condition for them would
     * mean demanding that the client sign its own signature.
     *
     * **`x-amz-checksum-*` is here for a different reason, and it is worth naming** (M-158). The
     * list exists so that an uploader cannot attach something to a signed policy that the signer
     * did not allow: an uncovered field is a field somebody can make use of. There is nothing to
     * make use of in a checksum: it widens nothing and names nothing, and the worst it can do is
     * refuse the upload. So no condition is required for it, while the checksum itself **is**
     * verified (`test_post_object_upload_checksum:15299` sends it with no condition and then sends
     * a wrong one).
     */
    private val NOT_CONDITIONED =
        setOf(
            "file",
            "policy",
            "signature",
            "awsaccesskeyid",
            "x-amz-signature",
            "x-amz-algorithm",
            "x-amz-credential",
            "x-amz-date",
            "x-amz-checksum-",
            "x-ignore-",
        )

    fun decode(encoded: String): Policy {
        val json =
            try {
                String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "the policy is not base64")
            }

        // Both keys are required, and both are read case-sensitively. `EXPIRATION` is not "a policy
        // without an expiry" but a policy its signer believed was time-limited: accepting it would
        // mean handing an unlimited pass to somebody who asked for a one-day one.
        // `test_post_object_expires_is_case_sensitive:2631` and `…_condition_is_case_sensitive:2598`
        // demand `400` for both, and demand it for this reason.
        val stated =
            valueOf(json, "expiration")
                ?: throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "the policy has no expiration")
        val expiration =
            try {
                Instant.parse(stated)
            } catch (_: DateTimeParseException) {
                throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "expiration does not parse: '$stated'")
            }
        return Policy(expiration, conditionsOf(json))
    }

    /**
     * Checks a form against a policy.
     *
     * The order of the checks runs from cheap to expensive and from general to specific: the expiry
     * first, then the conditions, then the length. A client that sent a stale policy should not
     * learn about it after ten conditions have been evaluated.
     *
     * @param fields the form's fields **plus `bucket`**, which is not among them: the bucket of a
     *   POST upload travels in the URL, while a condition on it is in every policy. The caller
     *   supplies it, because only the route knows it.
     */
    fun check(
        policy: Policy,
        fields: Map<String, String>,
        fileLength: Long,
        now: Instant,
    ) {
        if (now.isAfter(policy.expiration)) {
            throw Refused(S3Error.ACCESS_DENIED, "the policy expired ${policy.expiration}")
        }

        val covered = HashSet<String>()
        for (condition in policy.conditions) {
            when (condition) {
                is Condition.Exact -> {
                    covered += condition.field
                    val actual =
                        fields[condition.field]
                            ?: throw Refused(S3Error.ACCESS_DENIED, "the policy requires the field ${condition.field}")
                    if (actual != condition.value) {
                        throw Refused(
                            S3Error.ACCESS_DENIED,
                            "${condition.field} is '$actual', the policy allows '${condition.value}'",
                        )
                    }
                }

                is Condition.StartsWith -> {
                    covered += condition.field
                    val actual =
                        fields[condition.field]
                            ?: throw Refused(S3Error.ACCESS_DENIED, "the policy requires the field ${condition.field}")
                    if (!actual.startsWith(condition.prefix)) {
                        throw Refused(
                            S3Error.ACCESS_DENIED,
                            "${condition.field} is '$actual', the policy requires it to start with '${condition.prefix}'",
                        )
                    }
                }

                is Condition.LengthRange -> {
                    if (fileLength < condition.from || fileLength > condition.to) {
                        throw Refused(
                            S3Error.ENTITY_TOO_LARGE,
                            "a file of $fileLength bytes is outside ${condition.from}..${condition.to}",
                        )
                    }
                }
            }
        }

        // A field the policy did not cover is a field the signer did not allow. Letting it through
        // means letting an uploader attach anything at all to a signed policy, and that is a hole
        // rather than a leniency.
        for (name in fields.keys) {
            if (name in covered) continue
            if (name in NOT_CONDITIONED || NOT_CONDITIONED.any { it.endsWith("-") && name.startsWith(it) }) continue
            throw Refused(S3Error.ACCESS_DENIED, "the field '$name' is not allowed by the policy")
        }
    }

    // --- parsing, exactly as narrow as it needs to be ---------------------------------------------

    private fun valueOf(
        json: String,
        key: String,
    ): String? {
        val marker = json.indexOf("\"$key\"")
        if (marker < 0) return null
        val colon = json.indexOf(':', marker + key.length + 2)
        if (colon < 0) return null
        val quote = json.indexOf('"', colon)
        if (quote < 0) return null
        val end = json.indexOf('"', quote + 1)
        if (end < 0) return null
        return json.substring(quote + 1, end)
    }

    private fun conditionsOf(json: String): List<Condition> {
        val marker = json.indexOf("\"conditions\"")
        if (marker < 0) throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "the policy has no conditions")
        val open = json.indexOf('[', marker)
        if (open < 0) throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "conditions is not a list")

        val conditions = ArrayList<Condition>()
        var i = open + 1
        var depth = 1
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '[' -> {
                    val end = matching(json, i, '[', ']')
                    conditions += tripleOf(json.substring(i + 1, end))
                    i = end + 1
                }

                '{' -> {
                    val end = matching(json, i, '{', '}')
                    conditions += pairOf(json.substring(i + 1, end))
                    i = end + 1
                }

                ']' -> {
                    depth--
                    i++
                }

                else -> {
                    i++
                }
            }
        }
        return conditions
    }

    private fun matching(
        json: String,
        from: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var i = from
        while (i < json.length) {
            when (json[i]) {
                open -> {
                    depth++
                }

                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "a condition is not closed")
    }

    private fun pairOf(inner: String): Condition {
        val parts = quoted(inner)
        if (parts.size !=
            2
        ) {
            throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "an object condition is not a single pair")
        }
        return Condition.Exact(parts[0].lowercase(), parts[1])
    }

    private fun tripleOf(inner: String): Condition {
        val parts = quoted(inner)
        if (parts.size >= 1 && parts[0].equals("content-length-range", ignoreCase = true)) {
            val numbers = Regex("-?\\d+").findAll(inner).map { it.value.toLong() }.toList()
            if (numbers.size <
                2
            ) {
                throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "content-length-range without bounds")
            }
            return Condition.LengthRange(numbers[0], numbers[1])
        }
        if (parts.size == 3 && parts[0].equals("starts-with", ignoreCase = true)) {
            return Condition.StartsWith(parts[1].removePrefix("$").lowercase(), parts[2])
        }
        if (parts.size == 3 && parts[0].equals("eq", ignoreCase = true)) {
            return Condition.Exact(parts[1].removePrefix("$").lowercase(), parts[2])
        }
        throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "unknown condition: $inner")
    }

    /**
     * The strings of a condition, already free of JSON escaping.
     *
     * Unescaping here is not decoration: `test_post_object_escaped_field_values:2257` signs a
     * condition on the prefix `\$foo`, and in the document it sits as `\\$foo`. Comparing that
     * against the field as it stands would demand an extra backslash from the client — that is,
     * refuse the very form it allowed.
     */
    private fun quoted(inner: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(inner)
            .map { unescape(it.groupValues[1]) }
            .toList()

    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = text[i + 1]) {
                'n' -> {
                    out.append('\n')
                }

                't' -> {
                    out.append('\t')
                }

                'r' -> {
                    out.append('\r')
                }

                'u' -> {
                    if (i + 5 >= text.length) throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "a truncated \\u")
                    out.append(text.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 4
                }

                else -> {
                    out.append(next)
                }
            }
            i += 2
        }
        return out.toString()
    }
}
