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

    /**
     * One test inside a `Condition` block: an operator, a key of the request, and the values.
     *
     * The three flags are what the operator's name says about it, unpacked once at decode time so
     * that the decision does not re-read strings. `StringNotEquals` is [negated];
     * `StringLikeIfExists` is [glob] and [ifExists]; `Null` is none of them and is handled apart,
     * because it asks about the key's presence rather than about its value.
     */
    data class Condition(
        val operator: String,
        val key: String,
        val values: List<String>,
        val glob: Boolean,
        val negated: Boolean,
        val ifExists: Boolean,
    )

    data class Statement(
        val sid: String?,
        val effect: Effect,
        /** Access key ids, or `*`. An empty set is impossible: a statement without a principal is refused. */
        val principals: Set<String>,
        /** Patterns such as `s3:GetObject` or `s3:*`, matched with [matches]. */
        val actions: List<String>,
        /** ARN patterns, always beginning `arn:aws:s3:::`. */
        val resources: List<String>,
        /** Every test in the statement's `Condition` block; all of them must hold. */
        val conditions: List<Condition> = emptyList(),
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
            "s3:GetBucketLogging",
            "s3:GetBucketObjectLockConfiguration",
            "s3:GetBucketPolicy",
            "s3:GetBucketPolicyStatus",
            "s3:GetBucketPublicAccessBlock",
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
            "s3:PutBucketLogging",
            "s3:PutBucketObjectLockConfiguration",
            "s3:PutBucketPolicy",
            "s3:PutBucketPublicAccessBlock",
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

    /**
     * Condition keys this server can answer, and the reason a policy naming another is refused.
     *
     * A condition on a key nobody fills would be **true of everything** — an `Allow` that grants
     * more than its author wrote, or a `Deny` that stops nothing. Refusing by name is the only
     * answer that cannot silently mean either.
     *
     * `s3:ExistingObjectTag/<name>` and `s3:RequestObjectTag/<name>` carry the tag's name after
     * the slash, so they are matched by prefix rather than by equality.
     */
    val KNOWN_CONDITION_KEYS =
        setOf(
            "s3:prefix",
            "s3:delimiter",
            "s3:max-keys",
            "s3:x-amz-acl",
            "s3:x-amz-copy-source",
            "s3:x-amz-metadata-directive",
            "s3:x-amz-server-side-encryption",
            "s3:x-amz-server-side-encryption-aws-kms-key-id",
            // The algorithm of an SSE-C upload, which the server reads anyway (M26). The customer
            // **key** header is deliberately not a condition key beside it: a policy compares
            // values, and a comparison against a secret is a way to ask questions about it.
            "s3:x-amz-server-side-encryption-customer-algorithm",
            "s3:x-amz-storage-class",
            "s3:VersionId",
            "aws:Referer",
            "aws:UserAgent",
            // Filled only when this server delivers an access log on a bucket's behalf (M-202):
            // the source bucket's ARN, and the account that owns it.
            "aws:SourceArn",
            "aws:SourceAccount",
        )

    /*
     * Three keys AWS defines are deliberately **not** above, and each for the same reason:
     * this server cannot answer them, so a condition naming one would be true of every request.
     *
     * `aws:SourceIp` — the peer address does not reach the access decision, and behind the
     * terminator this design requires (research §1.7) it would be the proxy's address anyway.
     * `aws:SecureTransport` — TLS is terminated outside this process, which cannot tell an
     * `https` client from an `http` one; `X-Forwarded-Proto` is a header anybody may send.
     * `aws:username` — there are no users here, only access keys (§3.6).
     */

    /** Prefixed keys: everything after the slash names a tag rather than a key of its own. */
    val KNOWN_CONDITION_KEY_PREFIXES = setOf("s3:ExistingObjectTag/", "s3:RequestObjectTag/")

    /** The prefix every S3 resource ARN carries; there is no account or region in an S3 ARN. */
    const val ARN_PREFIX = "arn:aws:s3:::"

    /**
     * How a `{"Service": …}` principal is spelled inside a statement, so that no access key can
     * ever equal one: a key id is a word, and this is a word with a colon in front of it.
     */
    const val SERVICE_PREFIX = "service:"

    /** The one service this server ever acts as: the delivery of a bucket's access log. */
    const val LOGGING_SERVICE = SERVICE_PREFIX + "logging.s3.amazonaws.com"

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
            // A pattern is taken as written, and `"Action": "*"` — no service prefix at all — is
            // one of them: it is what `test_multipart_upload_on_a_bucket_with_policy:14469` sends
            // and a form AWS documents. Refusing it as "not an s3: action" was this reader being
            // stricter than the language, which is its own kind of wrong answer.
            if ('*' in action) continue
            if (!action.startsWith("s3:")) refuse("'$action' is not an s3: action")
            if (action !in KNOWN_ACTIONS) refuse("'$action' is not an action this server enforces")
        }
        // A statement with no `Resource` is accepted and **matches nothing**, which is the only
        // reading that fits `test_bucket_policy_status`'s neighbour `test_bucket_logging_owner`:
        // it puts exactly such a statement, requires 204 back, and then requires the permission it
        // appears to grant to still be refused. Inert rather than refused, and inert in the safe
        // direction — it grants less than it looks like, never more.
        val resources = statement.members["Resource"]?.let { textsOf(it, "Resource") } ?: emptyList()
        for (resource in resources) {
            if (!resource.startsWith(ARN_PREFIX)) refuse("'$resource' is not an $ARN_PREFIX… resource")
        }
        return Statement(
            sid = (statement.members["Sid"] as? JsonValue.Str)?.value,
            effect = effect,
            principals = principals,
            actions = actions,
            resources = resources,
            conditions = statement.members["Condition"]?.let { conditionsOf(it) } ?: emptyList(),
        )
    }

    /**
     * `{"StringEquals": {"s3:prefix": "public/"}}` — an operator, then a key, then one value or
     * several.
     *
     * Every operator and every key is checked against what this server can actually decide, and an
     * unknown one is a refusal naming it (M-201в). The alternative — ignoring the test — turns a
     * narrow permission into a broad one without saying so.
     */
    private fun conditionsOf(block: JsonValue): List<Condition> {
        if (block !is JsonValue.Obj) refuse("Condition is not an object")
        val conditions = ArrayList<Condition>()
        for ((operator, tests) in block.members) {
            val bare = operator.removeSuffix("IfExists")
            val ifExists = bare != operator
            val glob =
                when (bare) {
                    "StringEquals", "StringNotEquals", "Null" -> false

                    // ArnLike is a glob over an ARN. Kept as the same operator rather than given a
                    // path of its own: they differ in AWS only over ARN-shaped wildcards, and the
                    // policies that use it here name a whole bucket ARN
                    // (`_set_log_bucket_policy_tenant:15353`).
                    "StringLike", "StringNotLike", "ArnLike" -> true

                    else -> refuse("'$operator' is not a condition operator this server evaluates")
                }
            if (bare == "Null" && ifExists) refuse("'Null' has no IfExists form")
            if (tests !is JsonValue.Obj) refuse("the tests under '$operator' are not an object")
            for ((key, value) in tests.members) {
                if (key !in KNOWN_CONDITION_KEYS && KNOWN_CONDITION_KEY_PREFIXES.none { key.startsWith(it) }) {
                    refuse("'$key' is not a condition key this server can answer")
                }
                val values =
                    when (value) {
                        is JsonValue.Bool -> listOf(value.value.toString())
                        else -> textsOf(value, "Condition")
                    }
                conditions +=
                    Condition(
                        operator = bare,
                        key = key,
                        values = values,
                        glob = glob,
                        negated = bare.startsWith("StringNot"),
                        ifExists = ifExists,
                    )
            }
        }
        return conditions
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
                when (value.members.keys) {
                    setOf("AWS") -> {
                        textsOf(value.members.getValue("AWS"), "Principal").map { principalOf(it) }.toSet()
                    }

                    // A service is not an access key, so it can never match a signed request. The
                    // one thing that matches it is this server delivering an access log on a
                    // bucket's behalf (M-202) — understood here because that enforces it.
                    setOf("Service") -> {
                        textsOf(value.members.getValue("Service"), "Principal").map { SERVICE_PREFIX + it }.toSet()
                    }

                    else -> {
                        refuse("a Principal names ${value.members.keys}; only AWS and Service are understood here")
                    }
                }
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
        /**
         * The value of a condition key for this request, or `null` when the request does not carry
         * one. Asked lazily: a tag lookup costs a read, and most statements never mention tags.
         */
        keys: (String) -> String? = { null },
    ): Decision {
        var allowed = false
        for (statement in policy.statements) {
            if (!statement.principals.any { matchesPrincipal(it, principal) }) continue
            if (!statement.actions.any { matches(it, action) }) continue
            if (!statement.resources.any { matches(it, resource) }) continue
            if (!statement.conditions.all { holds(it, keys) }) continue
            // Not returned early: a later Deny outranks an earlier Allow, and reading the whole
            // document is the only way to know there is not one.
            if (statement.effect == Effect.DENY) return Decision.DENY
            allowed = true
        }
        return if (allowed) Decision.ALLOW else Decision.NEUTRAL
    }

    /**
     * Whether a statement's principal covers whoever is asking.
     *
     * `*` means **any caller**, and a service is not a caller: when this server delivers an access
     * log it acts as `logging.s3.amazonaws.com`, and a policy that says "everybody may write here"
     * has not said that the logging service may. `test_put_bucket_logging_permissions:16692` walks
     * exactly that difference — it swaps the service principal for `{"AWS": "*"}` and requires the
     * configuration to still be refused.
     */
    private fun matchesPrincipal(
        stated: String,
        principal: String?,
    ): Boolean =
        when {
            principal != null && principal.startsWith(SERVICE_PREFIX) -> stated == principal
            stated == "*" -> true
            else -> principal != null && stated == principal
        }

    /**
     * Whether one test holds for this request.
     *
     * Three rules here are easy to get backwards, and each is the difference between a permission
     * and a hole:
     *
     * * **an absent key fails a positive test and passes a negated one.** `StringEquals` on a
     *   header nobody sent is false; `StringNotEquals` on it is true, because nothing there
     *   equals the forbidden value;
     * * **`…IfExists` passes when the key is absent**, which is what the suffix means and the only
     *   reason to write it;
     * * **`Null` asks about presence, not value.** `"true"` means "this key must be absent",
     *   `"false"` means "it must be there" — the sense reads backwards to most people, including
     *   whoever writes the next condition here.
     */
    private fun holds(
        condition: Condition,
        keys: (String) -> String?,
    ): Boolean {
        val value = keys(condition.key)
        if (condition.operator == "Null") {
            val wantsAbsent = condition.values.any { it.equals("true", ignoreCase = true) }
            return (value == null) == wantsAbsent
        }
        if (value == null) return condition.ifExists || condition.negated
        val hit =
            condition.values.any { if (condition.glob) matches(it, value) else it == value }
        return hit != condition.negated
    }

    /**
     * Whether this document opens the bucket to everybody (M-227, M-228).
     *
     * **One definition, read by two features that ask the same question from opposite ends.**
     * `GetBucketPolicyStatus` reports it and `BlockPublicPolicy` refuses a document that has it,
     * and AWS spells them with a single rule for a reason: a server that reported one answer and
     * enforced the other would be telling the truth about a permission nobody has. They were
     * written twice here, in parallel, by work that could not see itself — and the two were close
     * enough to look identical and different enough to disagree about a statement carrying a
     * condition.
     */
    fun isPublic(policy: Policy): Boolean = policy.statements.any { isPublic(it) }

    /**
     * Whether one statement, by itself, makes the bucket public. Four things, all of them required.
     *
     * * **`Allow`.** A `Deny` grants nothing, so it cannot open anything. No case pins this, and
     *   saying so is part of the rule: it is derived from what the word means. It is derived
     *   safely only because getting it wrong would raise a false alarm rather than hide a leak.
     * * **`*` among the principals** (`test_get_publicpolicy_acl_bucket_policy_status:14107`).
     *   A named principal is not public even when it is the only one
     *   (`test_get_nonpublicpolicy_principal_bucket_policy_status:14167`), and a `{"Service": …}`
     *   principal is not `*` here either — `BucketPolicy.matchesPrincipal` already refuses to let
     *   `*` stand for the logging service, and a report that disagreed with the evaluator would
     *   describe a permission nobody has.
     * * **at least one action and at least one resource.** A statement with no `Resource` is
     *   stored and matches **nothing** (M-202): inert, granting nothing, and therefore incapable
     *   of making a bucket public. The two readings have to agree.
     * * **no condition at all** (`test_get_nonpublicpolicy_acl_bucket_policy_status:14135`).
     *
     * That last rule is deliberately coarser than AWS's, which separates condition keys that
     * narrow access from keys a caller can forge. The case pins exactly one direction — that a
     * condition makes an otherwise public statement non-public — and inventing the distinction
     * without a source would be common sense wearing a specification's clothes. Coarser in the
     * direction of a false alarm, again, which is the only direction this file may err in.
     *
     * The case itself does not run against this server, and not because of this operation: its
     * policy carries `IpAddress` over `aws:SourceIp`, both refused by name since M-201в because
     * nothing here can evaluate them (§3.8). The rule is enforced on the conditions that can be.
     */
    fun isPublic(statement: Statement): Boolean =
        statement.effect == BucketPolicy.Effect.ALLOW &&
            "*" in statement.principals &&
            statement.actions.isNotEmpty() &&
            statement.resources.isNotEmpty() &&
            statement.conditions.isEmpty()

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
