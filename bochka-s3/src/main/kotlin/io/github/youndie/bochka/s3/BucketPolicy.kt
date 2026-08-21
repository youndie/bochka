package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.json.Json
import io.github.youndie.bochka.s3.json.JsonSyntaxException
import io.github.youndie.bochka.s3.json.JsonValue
import io.github.youndie.bochka.s3.sigv4.S3Error

/**
 * A bucket policy: the document, and everything this server refuses to store (M-201а).
 *
 * The grammar is the one the suite writes (`s3tests/functional/policy.py`): a `Version`, and a
 * `Statement` that is one object or a list of them, each carrying `Effect`, `Principal`, `Action`
 * and `Resource`, optionally `Sid` and `Condition`.
 *
 * **This is the layer that grants**, and it is the first one here that does. The order recorded in
 * M27 — signature, then key scope, then ACL, each able only to take away — gains a term:
 * a policy hands a capability to a key the ACL never mentioned (`test_bucket_policy` lists a
 * private bucket as `alt_client`). The key scope still bounds it; a policy cannot widen a `ro` key.
 *
 * **Refusing is the safety property.** A policy accepted and not enforced reads stricter than it
 * is, and its author finds out through a leak rather than through an error — the same rule that
 * refuses an unknown checksum algorithm by name (M5) and kept `PutBucketPolicy` refused until this
 * milestone. So an action this server cannot enforce, a principal shape it cannot evaluate and a
 * condition it does not implement are all `MalformedPolicy` **naming the offending text**, never a
 * silently ignored member.
 */
object BucketPolicy {
    class Refused(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    enum class Effect { ALLOW, DENY }

    data class Statement(
        val sid: String?,
        val effect: Effect,
        /** Access key ids, or `*`. An empty set is impossible: a statement without a principal is refused. */
        val principals: Set<String>,
        /** Patterns such as `s3:GetObject` or `s3:*`, matched with [matches]. */
        val actions: List<String>,
        /** ARN patterns, always beginning `arn:aws:s3:::`. */
        val resources: List<String>,
    )

    data class Policy(
        val version: String?,
        val statements: List<Statement>,
    )

    /**
     * The ceiling on a stored document.
     *
     * AWS documents 20 KB for a bucket policy; this is that number, and it is enforced because the
     * document is held in memory per bucket for the life of the process, not because S3 says so.
     */
    const val MAX_BYTES = 20 * 1024

    /**
     * Every action this server can decide, and the whole reason [decode] can refuse by name.
     *
     * A literal action outside this set is a refusal. A pattern (anything holding `*`) is accepted
     * and matched against this set at decision time, because `s3:*` is how most real policies are
     * written and refusing it would refuse the common case to catch the rare typo.
     */
    val KNOWN_ACTIONS =
        setOf(
            "s3:AbortMultipartUpload",
            "s3:CreateBucket",
            "s3:DeleteBucket",
            "s3:DeleteBucketPolicy",
            "s3:DeleteObject",
            "s3:DeleteObjectTagging",
            "s3:DeleteObjectVersion",
            "s3:DeleteObjectVersionTagging",
            "s3:GetBucketAcl",
            "s3:GetBucketCORS",
            "s3:GetBucketLocation",
            "s3:GetBucketObjectLockConfiguration",
            "s3:GetBucketPolicy",
            "s3:GetBucketPolicyStatus",
            "s3:GetBucketTagging",
            "s3:GetBucketVersioning",
            "s3:GetLifecycleConfiguration",
            "s3:GetObject",
            "s3:GetObjectAcl",
            "s3:GetObjectAttributes",
            "s3:GetObjectLegalHold",
            "s3:GetObjectRetention",
            "s3:GetObjectTagging",
            "s3:GetObjectVersion",
            "s3:GetObjectVersionAcl",
            "s3:GetObjectVersionAttributes",
            "s3:GetObjectVersionTagging",
            "s3:ListAllMyBuckets",
            "s3:ListBucket",
            "s3:ListBucketMultipartUploads",
            "s3:ListBucketVersions",
            "s3:ListMultipartUploadParts",
            "s3:PutBucketAcl",
            "s3:PutBucketCORS",
            "s3:PutBucketObjectLockConfiguration",
            "s3:PutBucketPolicy",
            "s3:PutBucketTagging",
            "s3:PutBucketVersioning",
            "s3:PutLifecycleConfiguration",
            "s3:PutObject",
            "s3:PutObjectAcl",
            "s3:PutObjectLegalHold",
            "s3:PutObjectRetention",
            "s3:PutObjectTagging",
            "s3:PutObjectVersionAcl",
            "s3:PutObjectVersionTagging",
        )

    /** The prefix every S3 resource ARN carries; there is no account or region in an S3 ARN. */
    const val ARN_PREFIX = "arn:aws:s3:::"

    fun decode(text: String): Policy {
        if (text.toByteArray().size > MAX_BYTES) {
            refuse("the policy is longer than $MAX_BYTES bytes")
        }
        val root =
            try {
                Json.parse(text)
            } catch (e: JsonSyntaxException) {
                refuse("the policy is not JSON: ${e.message}")
            }
        if (root !is JsonValue.Obj) refuse("the policy is not a JSON object")

        val version = (root.members["Version"] as? JsonValue.Str)?.value
        // Only the dated language exists; anything else names a grammar this reader is not.
        if (version != null && version != "2012-10-17" && version != "2008-10-17") {
            refuse("'$version' is not a policy language version this server knows")
        }
        val statements =
            when (val member = root.members["Statement"]) {
                null -> {
                    refuse("the policy carries no Statement")
                }

                is JsonValue.Obj -> {
                    listOf(statementOf(member))
                }

                is JsonValue.Arr -> {
                    member.items.map {
                        statementOf(it as? JsonValue.Obj ?: refuse("a Statement is not an object"))
                    }
                }

                else -> {
                    refuse("Statement is neither an object nor a list of them")
                }
            }
        if (statements.isEmpty()) refuse("the policy carries no statements")
        return Policy(version, statements)
    }

    private fun statementOf(statement: JsonValue.Obj): Statement {
        // Named first so that the refusal says which member is the problem rather than which
        // member is missing: a policy using NotAction is a policy whose author meant the opposite
        // of what this server would enforce by ignoring it.
        for (unsupported in listOf("NotPrincipal", "NotAction", "NotResource")) {
            if (unsupported in statement.members) {
                refuse("$unsupported is not enforced here, and a policy is not stored half-understood")
            }
        }
        if ("Condition" in statement.members) {
            refuse("Condition is not enforced yet (M-201в), and an ignored condition would read stricter than it is")
        }

        val effect =
            when ((statement.members["Effect"] as? JsonValue.Str)?.value) {
                "Allow" -> Effect.ALLOW
                "Deny" -> Effect.DENY
                null -> refuse("a statement carries no Effect")
                else -> refuse("'${(statement.members["Effect"] as JsonValue.Str).value}' is not Allow or Deny")
            }

        val principals = principalsOf(statement.members["Principal"] ?: refuse("a statement carries no Principal"))
        val actions = textsOf(statement.members["Action"] ?: refuse("a statement carries no Action"), "Action")
        for (action in actions) {
            if (!action.startsWith("s3:")) refuse("'$action' is not an s3: action")
            if ('*' !in action && action !in KNOWN_ACTIONS) refuse("'$action' is not an action this server enforces")
        }
        val resources = textsOf(statement.members["Resource"] ?: refuse("a statement carries no Resource"), "Resource")
        for (resource in resources) {
            if (!resource.startsWith(ARN_PREFIX)) refuse("'$resource' is not an $ARN_PREFIX… resource")
        }
        return Statement(
            sid = (statement.members["Sid"] as? JsonValue.Str)?.value,
            effect = effect,
            principals = principals,
            actions = actions,
            resources = resources,
        )
    }

    /**
     * `"*"`, `{"AWS": "*"}`, `{"AWS": "arn:aws:iam::key:root"}` or a list of those.
     *
     * The identity in an S3 policy is an account, and here an account is an access key: the suite
     * says so itself — its `user_id` is the key id (`make-conf.py`). So the ARN is unwrapped to the
     * id it names, and a principal naming a service or a federated identity is refused rather than
     * quietly never matching, which would look like a policy that works and denies everyone.
     */
    private fun principalsOf(value: JsonValue): Set<String> =
        when (value) {
            is JsonValue.Str -> {
                setOf(principalOf(value.value))
            }

            is JsonValue.Obj -> {
                val aws =
                    value.members["AWS"]
                        ?: refuse("a Principal names ${value.members.keys}, and only AWS is understood here")
                if (value.members.keys != setOf("AWS")) {
                    refuse("a Principal names ${value.members.keys}, and only AWS is understood here")
                }
                textsOf(aws, "Principal").map { principalOf(it) }.toSet()
            }

            else -> {
                refuse("a Principal is neither a string nor an object")
            }
        }

    private fun principalOf(text: String): String =
        when {
            text == "*" -> {
                "*"
            }

            // arn:aws:iam::<account>:root, and <account> is the access key id here.
            text.startsWith("arn:aws:iam::") && text.endsWith(":root") -> {
                text.removePrefix("arn:aws:iam::").removeSuffix(":root")
            }

            text.startsWith("arn:") -> {
                refuse("'$text' is not a principal this server can evaluate")
            }

            else -> {
                text
            }
        }

    private fun textsOf(
        value: JsonValue,
        member: String,
    ): List<String> =
        when (value) {
            is JsonValue.Str -> {
                listOf(value.value)
            }

            is JsonValue.Arr -> {
                value.items.map {
                    (it as? JsonValue.Str)?.value
                        ?: refuse("$member holds something that is not a string")
                }
            }

            else -> {
                refuse("$member is neither a string nor a list of strings")
            }
        }

    /**
     * What a policy has to say about one request (M-201б).
     *
     * [NEUTRAL] is the value that keeps the other layers honest: a policy about listing must not
     * start refusing reads it never mentioned, so "no statement matched" is a different answer
     * from "denied" and the ACL still decides. [DENY] is stronger than any [ALLOW], including one
     * in the same document — that is the whole reason an explicit deny is worth writing.
     */
    enum class Decision { ALLOW, DENY, NEUTRAL }

    /**
     * Whether [policy] says anything about [principal] doing [action] to [resource].
     *
     * [principal] is an access key id, or `null` for a request that carried no credentials — and
     * nobody matches `*` and nothing else, because every other principal names a key that a
     * request without credentials is not.
     *
     * [resource] is the full ARN: `arn:aws:s3:::bucket` for the bucket itself,
     * `arn:aws:s3:::bucket/key` for one of its objects.
     */
    fun evaluate(
        policy: Policy,
        principal: String?,
        action: String,
        resource: String,
    ): Decision {
        var allowed = false
        for (statement in policy.statements) {
            if (!statement.principals.any { it == "*" || (principal != null && it == principal) }) continue
            if (!statement.actions.any { matches(it, action) }) continue
            if (!statement.resources.any { matches(it, resource) }) continue
            // Not returned early: a later Deny outranks an earlier Allow, and reading the whole
            // document is the only way to know there is not one.
            if (statement.effect == Effect.DENY) return Decision.DENY
            allowed = true
        }
        return if (allowed) Decision.ALLOW else Decision.NEUTRAL
    }

    /**
     * The wildcard match an S3 policy uses: `*` for any run of characters, `?` for one.
     *
     * Written out rather than compiled to a regex, because the text being matched is a key that may
     * hold any byte — and a key holding `.*(a+)+` handed to a regex engine is a request that never
     * ends. Linear, backtracking only on `*`, which cannot blow up: the classic two-pointer glob.
     */
    fun matches(
        pattern: String,
        text: String,
    ): Boolean {
        var p = 0
        var t = 0
        var star = -1
        var mark = 0
        while (t < text.length) {
            when {
                p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) -> {
                    p++
                    t++
                }

                p < pattern.length && pattern[p] == '*' -> {
                    star = p++
                    mark = t
                }

                star >= 0 -> {
                    p = star + 1
                    t = ++mark
                }

                else -> {
                    return false
                }
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    private fun refuse(message: String): Nothing = throw Refused(S3Error.MALFORMED_POLICY, message)
}
