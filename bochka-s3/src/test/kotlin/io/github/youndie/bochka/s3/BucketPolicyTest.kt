package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The policy document and the decision it makes (M-201а, M-201б).
 *
 * The grammar under test is the one the suite writes — `s3tests/functional/policy.py` — and the
 * cases named in the assertions are the ones this has to satisfy. Half the tests here are about
 * refusing, and that is the milestone's safety property rather than an accident of coverage:
 * a policy stored and not enforced reads stricter than it is.
 */
class BucketPolicyTest {
    private fun document(
        action: String = "\"s3:ListBucket\"",
        principal: String = """{"AWS": "*"}""",
        effect: String = "Allow",
        resource: String = """["arn:aws:s3:::photos", "arn:aws:s3:::photos/*"]""",
        extra: String = "",
    ) = """{"Version": "2012-10-17", "Statement": [{"Effect": "$effect", "Principal": $principal, """ +
        """"Action": $action, "Resource": $resource$extra}]}"""

    // --- the document ----------------------------------------------------------------------

    @Test
    fun `the shape the suite writes is read`() {
        val policy = BucketPolicy.decode(document())

        assertEquals("2012-10-17", policy.version)
        val statement = policy.statements.single()
        assertEquals(BucketPolicy.Effect.ALLOW, statement.effect)
        assertEquals(setOf("*"), statement.principals)
        assertEquals(listOf("s3:ListBucket"), statement.actions)
        assertEquals(listOf("arn:aws:s3:::photos", "arn:aws:s3:::photos/*"), statement.resources)
    }

    @Test
    fun `a single statement need not be wrapped in a list`() {
        val policy =
            BucketPolicy.decode(
                """{"Statement": {"Effect": "Deny", "Principal": "*", "Action": "s3:*", "Resource": "arn:aws:s3:::*"}}""",
            )

        assertEquals(BucketPolicy.Effect.DENY, policy.statements.single().effect)
    }

    @Test
    fun `an account arn is the access key it names`() {
        val policy = BucketPolicy.decode(document(principal = """{"AWS": "arn:aws:iam::alice:root"}"""))

        assertEquals(setOf("alice"), policy.statements.single().principals)
    }

    @Test
    fun `NotPrincipal is refused rather than ignored`() {
        // `test_bucket_policy_allow_notprincipal:14216` asks for exactly this: 400, and either
        // InvalidArgument or MalformedPolicy. Ignoring the member would turn "everyone except
        // alice" into "everyone", which is the opposite of what its author wrote.
        val thrown =
            assertFailsWith<BucketPolicy.Refused> {
                BucketPolicy.decode(
                    """{"Statement": [{"Effect": "Allow", "NotPrincipal": {"AWS": "arn:aws:iam::alice:root"}, """ +
                        """"Action": "s3:ListBucket", "Resource": "arn:aws:s3:::photos"}]}""",
                )
            }

        assertTrue(thrown.message.contains("NotPrincipal"), thrown.message)
    }

    @Test
    fun `an action this server cannot enforce is refused by name`() {
        val thrown =
            assertFailsWith<BucketPolicy.Refused> { BucketPolicy.decode(document(action = "\"s3:RestoreObject\"")) }

        assertTrue(thrown.message.contains("s3:RestoreObject"), thrown.message)
    }

    @Test
    fun `a wildcard action is taken as written`() {
        // Refusing `s3:*` to catch a typo would refuse the way most real policies are written.
        assertEquals(
            listOf("s3:Get*"),
            BucketPolicy
                .decode(document(action = "\"s3:Get*\""))
                .statements
                .single()
                .actions,
        )
    }

    @Test
    fun `an action outside s3 is refused`() {
        assertFailsWith<BucketPolicy.Refused> { BucketPolicy.decode(document(action = "\"iam:PassRole\"")) }
    }

    @Test
    fun `a resource that is not an s3 arn is refused`() {
        assertFailsWith<BucketPolicy.Refused> { BucketPolicy.decode(document(resource = "\"arn:aws:sqs:::queue\"")) }
    }

    @Test
    fun `an operator this server cannot evaluate is refused by name`() {
        val thrown =
            assertFailsWith<BucketPolicy.Refused> {
                BucketPolicy.decode(
                    document(extra = """, "Condition": {"IpAddress": {"aws:SourceIp": "10.0.0.0/8"}}"""),
                )
            }

        assertTrue(thrown.message.contains("IpAddress"), thrown.message)
    }

    @Test
    fun `a condition key nobody fills is refused by name`() {
        // The refusal is the point: a test on a key this server never answers is true of every
        // request, so an `Allow` carrying it grants more than its author wrote.
        val thrown =
            assertFailsWith<BucketPolicy.Refused> {
                BucketPolicy.decode(
                    document(extra = """, "Condition": {"StringEquals": {"s3:LocationConstraint": "eu"}}"""),
                )
            }

        assertTrue(thrown.message.contains("s3:LocationConstraint"), thrown.message)
    }

    // --- conditions (M-201в) -----------------------------------------------------------------

    private fun conditioned(block: String) = BucketPolicy.decode(document(extra = """, "Condition": $block"""))

    private fun decide(
        policy: BucketPolicy.Policy,
        keys: Map<String, String>,
    ) = BucketPolicy.evaluate(
        policy,
        principal = "alt",
        action = "s3:ListBucket",
        resource = "arn:aws:s3:::photos",
        keys = { keys[it] },
    )

    @Test
    fun `StringEquals holds only for the value it names`() {
        val policy = conditioned("""{"StringEquals": {"s3:prefix": "public/"}}""")

        assertEquals(BucketPolicy.Decision.ALLOW, decide(policy, mapOf("s3:prefix" to "public/")))
        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(policy, mapOf("s3:prefix" to "private/")))
    }

    @Test
    fun `a key the request does not carry fails a positive test`() {
        // `test_head_object_404_with_policy_prefix` turns on this: the grant is conditional, so a
        // request that says nothing about the key is not covered by it.
        val policy = conditioned("""{"StringLike": {"s3:prefix": "public/*"}}""")

        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(policy, emptyMap()))
        assertEquals(BucketPolicy.Decision.ALLOW, decide(policy, mapOf("s3:prefix" to "public/holiday.jpg")))
    }

    @Test
    fun `and passes a negated one`() {
        // Nothing there equals the forbidden value, so the test holds — which is the AWS rule and
        // the one most likely to be written backwards.
        val policy = conditioned("""{"StringNotEquals": {"s3:x-amz-acl": "public-read"}}""")

        assertEquals(BucketPolicy.Decision.ALLOW, decide(policy, emptyMap()))
        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(policy, mapOf("s3:x-amz-acl" to "public-read")))
        assertEquals(BucketPolicy.Decision.ALLOW, decide(policy, mapOf("s3:x-amz-acl" to "private")))
    }

    @Test
    fun `IfExists passes when the key is absent`() {
        // `test_bucket_policy_set_condition_operator_end_with_IfExists:11898`.
        val policy = conditioned("""{"StringLikeIfExists": {"aws:Referer": "http://www.example.com/*"}}""")

        assertEquals(BucketPolicy.Decision.ALLOW, decide(policy, emptyMap()))
        assertEquals(BucketPolicy.Decision.ALLOW, decide(policy, mapOf("aws:Referer" to "http://www.example.com/x")))
        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(policy, mapOf("aws:Referer" to "http://elsewhere/x")))
    }

    @Test
    fun `Null asks about presence and reads backwards`() {
        val mustBeAbsent = conditioned("""{"Null": {"s3:x-amz-server-side-encryption": "true"}}""")
        val mustBePresent = conditioned("""{"Null": {"s3:x-amz-server-side-encryption": "false"}}""")

        assertEquals(BucketPolicy.Decision.ALLOW, decide(mustBeAbsent, emptyMap()))
        assertEquals(
            BucketPolicy.Decision.NEUTRAL,
            decide(
                mustBeAbsent,
                mapOf("s3:x-amz-server-side-encryption" to "AES256"),
            ),
        )
        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(mustBePresent, emptyMap()))
        assertEquals(
            BucketPolicy.Decision.ALLOW,
            decide(
                mustBePresent,
                mapOf("s3:x-amz-server-side-encryption" to "AES256"),
            ),
        )
    }

    @Test
    fun `a tag key carries the tag name after the slash`() {
        // `test_bucket_policy_get_obj_existing_tag:12455`: three objects, one tag value each, and
        // only the one tagged `public` may be read.
        val policy = conditioned("""{"StringEquals": {"s3:ExistingObjectTag/security": "public"}}""")

        assertEquals(BucketPolicy.Decision.ALLOW, decide(policy, mapOf("s3:ExistingObjectTag/security" to "public")))
        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(policy, mapOf("s3:ExistingObjectTag/security" to "private")))
        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(policy, mapOf("s3:ExistingObjectTag/other" to "public")))
    }

    @Test
    fun `every test in a block has to hold`() {
        val policy =
            conditioned(
                """{"StringEquals": {"s3:prefix": "public/", "s3:delimiter": "/"}}""",
            )

        assertEquals(
            BucketPolicy.Decision.ALLOW,
            decide(policy, mapOf("s3:prefix" to "public/", "s3:delimiter" to "/")),
        )
        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(policy, mapOf("s3:prefix" to "public/")))
    }

    @Test
    fun `a list of values means any of them`() {
        val policy = conditioned("""{"StringEquals": {"s3:prefix": ["public/", "shared/"]}}""")

        assertEquals(BucketPolicy.Decision.ALLOW, decide(policy, mapOf("s3:prefix" to "shared/")))
        assertEquals(BucketPolicy.Decision.NEUTRAL, decide(policy, mapOf("s3:prefix" to "secret/")))
    }

    @Test
    fun `a document longer than the ceiling is refused before it is parsed`() {
        val huge = document(resource = "[" + (1..2000).joinToString(", ") { """"arn:aws:s3:::bucket$it"""" } + "]")

        assertFailsWith<BucketPolicy.Refused> { BucketPolicy.decode(huge) }
    }

    // --- the decision ----------------------------------------------------------------------

    @Test
    fun `a statement everybody matches allows a stranger`() {
        // `test_bucket_policy`: the bucket is private, the policy names `*`, and the second key
        // lists it. This is the first layer here that **grants** rather than takes away.
        val policy = BucketPolicy.decode(document())

        assertEquals(
            BucketPolicy.Decision.ALLOW,
            BucketPolicy.evaluate(
                policy,
                principal = "alt",
                action = "s3:ListBucket",
                resource = "arn:aws:s3:::photos",
            ),
        )
    }

    @Test
    fun `a policy that names nothing relevant decides nothing`() {
        val policy = BucketPolicy.decode(document())

        // NEUTRAL and not DENY: the ACL still has its say, and a policy about listing must not
        // start refusing reads it never mentioned.
        assertEquals(
            BucketPolicy.Decision.NEUTRAL,
            BucketPolicy.evaluate(
                policy,
                principal = "alt",
                action = "s3:GetObject",
                resource = "arn:aws:s3:::photos/a",
            ),
        )
    }

    @Test
    fun `an explicit deny beats an allow in the same document`() {
        val policy =
            BucketPolicy.decode(
                """{"Statement": [
                    {"Effect": "Allow", "Principal": "*", "Action": "s3:*", "Resource": "arn:aws:s3:::photos/*"},
                    {"Effect": "Deny", "Principal": "*", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::photos/secret"}
                ]}""",
            )

        assertEquals(
            BucketPolicy.Decision.DENY,
            BucketPolicy.evaluate(
                policy,
                principal = "alt",
                action = "s3:GetObject",
                resource = "arn:aws:s3:::photos/secret",
            ),
        )
        assertEquals(
            BucketPolicy.Decision.ALLOW,
            BucketPolicy.evaluate(
                policy,
                principal = "alt",
                action = "s3:GetObject",
                resource = "arn:aws:s3:::photos/open",
            ),
        )
    }

    @Test
    fun `a named principal does not answer for another key`() {
        val policy = BucketPolicy.decode(document(principal = """{"AWS": "arn:aws:iam::alice:root"}"""))

        assertEquals(
            BucketPolicy.Decision.ALLOW,
            BucketPolicy.evaluate(
                policy,
                principal = "alice",
                action = "s3:ListBucket",
                resource = "arn:aws:s3:::photos",
            ),
        )
        assertEquals(
            BucketPolicy.Decision.NEUTRAL,
            BucketPolicy.evaluate(
                policy,
                principal = "bob",
                action = "s3:ListBucket",
                resource = "arn:aws:s3:::photos",
            ),
        )
    }

    @Test
    fun `nobody matches the wildcard principal and nothing else`() {
        val everyone = BucketPolicy.decode(document())
        val alice = BucketPolicy.decode(document(principal = """{"AWS": "arn:aws:iam::alice:root"}"""))

        assertEquals(
            BucketPolicy.Decision.ALLOW,
            BucketPolicy.evaluate(
                everyone,
                principal = null,
                action = "s3:ListBucket",
                resource = "arn:aws:s3:::photos",
            ),
        )
        assertEquals(
            BucketPolicy.Decision.NEUTRAL,
            BucketPolicy.evaluate(alice, principal = null, action = "s3:ListBucket", resource = "arn:aws:s3:::photos"),
        )
    }

    @Test
    fun `one document works in two buckets when its arn is a wildcard`() {
        // `test_bucket_policy_another_bucket` reads a policy out of one bucket and puts the same
        // text into a second one, then expects both to answer.
        val policy = BucketPolicy.decode(document(resource = """["arn:aws:s3:::*", "arn:aws:s3:::*/*"]"""))

        for (bucket in listOf("photos", "videos")) {
            assertEquals(
                BucketPolicy.Decision.ALLOW,
                BucketPolicy.evaluate(
                    policy,
                    principal = "alt",
                    action = "s3:ListBucket",
                    resource = "arn:aws:s3:::$bucket",
                ),
            )
        }
    }

    @Test
    fun `a wildcard action covers the actions it spells`() {
        val policy = BucketPolicy.decode(document(action = "\"s3:Get*\"", resource = "\"arn:aws:s3:::photos/*\""))

        assertEquals(
            BucketPolicy.Decision.ALLOW,
            BucketPolicy.evaluate(
                policy,
                principal = "alt",
                action = "s3:GetObject",
                resource = "arn:aws:s3:::photos/a",
            ),
        )
        assertEquals(
            BucketPolicy.Decision.NEUTRAL,
            BucketPolicy.evaluate(
                policy,
                principal = "alt",
                action = "s3:PutObject",
                resource = "arn:aws:s3:::photos/a",
            ),
        )
    }

    @Test
    fun `a star in a resource does not stop at the slash`() {
        // S3's wildcard is not a path glob: `bucket/*` covers `bucket/a/b/c`, and a policy author
        // who writes it means every key in the bucket.
        val policy = BucketPolicy.decode(document(action = "\"s3:GetObject\"", resource = "\"arn:aws:s3:::photos/*\""))

        assertEquals(
            BucketPolicy.Decision.ALLOW,
            BucketPolicy.evaluate(
                policy,
                principal = "alt",
                action = "s3:GetObject",
                resource = "arn:aws:s3:::photos/a/b/c",
            ),
        )
    }

    @Test
    fun `matching is linear on a key that looks like a pattern`() {
        // A key may hold any bytes, including the shape that makes a backtracking regex engine run
        // for ever. This matcher is a two-pointer glob for that reason.
        val evil = "a".repeat(4000)

        assertTrue(BucketPolicy.matches("*a", evil))
        assertTrue(!BucketPolicy.matches("*b", evil))
    }
}
