package io.github.youndie.bochka.s3

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The policy parser at its own boundaries (M-251).
 *
 * The document next door is the one the suite sends: expiration first, conditions second, every
 * bracket closed. Twenty-two mutations of the index arithmetic survived it, because that shape never
 * puts an index at the edge of the string — a member is always followed by another, a bracket is
 * always closed, an escape is never the last thing in a value.
 *
 * This is the only parser in the server whose input is **signed by somebody else**: the policy is
 * base64 inside a form, and what it says decides what the uploader is allowed to store. A parser
 * that reads one character too far here reads a value the signer did not write.
 */
class PostPolicyEdgeTest {
    private fun encode(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())

    private val now: Instant = Instant.parse("2026-08-18T12:00:00Z")

    private fun fields(vararg extra: Pair<String, String>) = mapOf("bucket" to "photos") + extra

    @Test
    fun `expiration is found wherever the client put it in the document`() {
        // The suite writes it first and nothing says it has to be. The search for its value starts
        // from the key that was found, and starting anywhere earlier finds the colon of a member
        // above it — which parses, and gives an expiry the signer never wrote.
        val second =
            """{"conditions": [{"bucket": "photos"}], "expiration": "2026-08-18T13:00:00Z"}"""

        val policy = PostPolicy.decode(encode(second))

        assertEquals(Instant.parse("2026-08-18T13:00:00Z"), policy.expiration)
        PostPolicy.check(policy, fields(), 0, now)
    }

    @Test
    fun `a member after the conditions is ignored rather than read as one`() {
        // The array ends at its closing bracket and the scan has to stop there. Reading on turns
        // whatever object follows into a condition, and a condition nobody signed is a condition
        // the upload is measured against.
        val trailing =
            """
            {"expiration": "2026-08-18T13:00:00Z",
             "conditions": [{"bucket": "photos"}],
             "x-ignored": {"acl": "public-read"}}
            """.trimIndent()

        val policy = PostPolicy.decode(encode(trailing))

        assertEquals(1, policy.conditions.size, "one condition was signed, one condition is enforced")
        PostPolicy.check(policy, fields(), 0, now)
    }

    @Test
    fun `an unterminated conditions list stops at the end of the document`() {
        // The scan walks until the list closes or the document does, and the second of those is
        // what this asks about: one step past the last character is not a refusal, it is an
        // exception thrown out of a parser whose whole job is to answer `400`.
        //
        // That an unclosed list is *accepted* rather than refused is a separate question and not
        // this one's: the policy is signed, so a truncated document fails the signature before
        // anything here matters.
        val unclosed = """{"expiration": "2026-08-18T13:00:00Z", "conditions": [{"bucket": "photos"}"""

        val policy = PostPolicy.decode(encode(unclosed))

        assertEquals(1, policy.conditions.size)
        PostPolicy.check(policy, fields(), 0, now)
    }

    @Test
    fun `an unterminated condition is refused rather than read off the end`() {
        // The inner bracket, not the outer one: the scan that looks for its partner walks to the
        // end of the document, and one step past the end is not a refusal, it is an exception where
        // a `400` should be.
        val unclosed =
            """{"expiration": "2026-08-18T13:00:00Z", "conditions": [["starts-with", "${'$'}key", "foo"}"""

        assertFailsWith<PostPolicy.Refused> { PostPolicy.decode(encode(unclosed)) }
    }

    @Test
    fun `a length range includes both of its ends`() {
        // `content-length-range` is a range and not an exclusive one: the suite signs `0, 1024` and
        // expects a file of exactly 1024 bytes to be accepted. A bound that is off by one here
        // refuses an upload the signer allowed, and the uploader has no way to tell why.
        val ranged =
            """
            {"expiration": "2026-08-18T13:00:00Z",
             "conditions": [{"bucket": "photos"}, ["content-length-range", 4, 8]]}
            """.trimIndent()
        val policy = PostPolicy.decode(encode(ranged))

        PostPolicy.check(policy, fields(), 4, now)
        PostPolicy.check(policy, fields(), 8, now)
        assertFailsWith<PostPolicy.Refused> { PostPolicy.check(policy, fields(), 3, now) }
        assertFailsWith<PostPolicy.Refused> { PostPolicy.check(policy, fields(), 9, now) }
    }

    @Test
    fun `an escape at the very end of a value is refused rather than read past it`() {
        // `\u` needs four digits after it and there are two. The check that says so is the last
        // thing between the parser and the end of the string.
        val truncated =
            """{"expiration": "2026-08-18T13:00:00Z", "conditions": [{"acl": "priv\u00"}]}"""

        assertFailsWith<PostPolicy.Refused> { PostPolicy.decode(encode(truncated)) }
    }

    @Test
    fun `an escape that ends a value exactly is read, not refused`() {
        // The other side of the same bound: four digits and the closing quote, with nothing to
        // spare. A parser that demands one character more than it needs refuses a legal policy.
        val exact =
            """
            {"expiration": "2026-08-18T13:00:00Z",
             "conditions": [{"bucket": "photos"}, {"acl": "priv\u0041"}]}
            """.trimIndent()

        val policy = PostPolicy.decode(encode(exact))

        PostPolicy.check(policy, fields("acl" to "privA"), 0, now)
    }

    @Test
    fun `the instant of expiry is still inside the policy, and the one after it is not`() {
        // A moment is either after the expiry or not, and the instant itself belongs to the side
        // the rule puts it on. The suite never asks — its policies expire an hour out — and a client
        // whose clock agrees with the server's to the millisecond does.
        val policy =
            PostPolicy.decode(
                encode("""{"expiration": "2026-08-18T12:00:00Z", "conditions": [{"bucket": "photos"}]}"""),
            )

        PostPolicy.check(policy, fields(), 0, now)
        assertFailsWith<PostPolicy.Refused> { PostPolicy.check(policy, fields(), 0, now.plusMillis(1)) }
    }

    @Test
    fun `a value that ends in a backslash keeps it rather than reading past it`() {
        // A lone backslash at the very end of a string is not an escape — there is nothing for it
        // to escape. The check that says so is the only thing between the unescaper and the
        // character after the last one.
        val trailing =
            """
            {"expiration": "2026-08-18T13:00:00Z",
             "conditions": [{"bucket": "photos"}, {"acl": "priv\\"}]}
            """.trimIndent()

        val policy = PostPolicy.decode(encode(trailing))

        PostPolicy.check(policy, fields("acl" to "priv\\"), 0, now)
    }

    @Test
    fun `a unicode escape one digit short is refused rather than read past the end`() {
        // Three digits where four are needed, and nothing after them. One character of slack in the
        // check turns a refusal into an index past the end of the string.
        val short =
            """{"expiration": "2026-08-18T13:00:00Z", "conditions": [{"acl": "priv\u004"}]}"""

        assertFailsWith<PostPolicy.Refused> { PostPolicy.decode(encode(short)) }
    }

    @Test
    fun `the value read is the one belonging to the key that was found`() {
        // The search for the colon starts from the key, and a member immediately before it is close
        // enough that starting a few characters early lands on **its** colon instead. The expiry
        // would then be whatever that member's value is — a policy the signer never wrote, and one
        // that parses.
        val preceded = """{"a":"b","expiration":"2026-08-18T13:00:00Z","conditions":[{"bucket":"photos"}]}"""

        val policy = PostPolicy.decode(encode(preceded))

        assertEquals(Instant.parse("2026-08-18T13:00:00Z"), policy.expiration)
    }

    @Test
    fun `a policy with no expiration says so, rather than failing to parse one`() {
        // Two different problems and two different things for the client to fix: a document that
        // forgot the member, and a member whose value is not a date. Answering the second for the
        // first sends whoever signed it looking at the format of something that is not there.
        val missing = """{"conditions": [{"bucket": "photos"}]}"""

        val refused = assertFailsWith<PostPolicy.Refused> { PostPolicy.decode(encode(missing)) }

        assertEquals("the policy has no expiration", refused.message)
    }
}
