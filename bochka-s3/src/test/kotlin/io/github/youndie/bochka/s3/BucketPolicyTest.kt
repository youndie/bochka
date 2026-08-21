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
    fun `a condition is refused while nothing enforces it`() {
        val thrown =
            assertFailsWith<BucketPolicy.Refused> {
                BucketPolicy.decode(document(extra = """, "Condition": {"StringEquals": {"s3:prefix": "public/"}}"""))
            }

        assertTrue(thrown.message.contains("Condition"), thrown.message)
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
