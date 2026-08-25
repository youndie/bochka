package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bounds on a tag set: `s3-service-2.json:1463` ("maximum number of tags to 10 tags per
 * object") and the tag restrictions page — 128 characters for a key, 256 for a value.
 */
class TagRulesTest {
    @Test
    fun `ten tags are allowed and eleven are not`() {
        assertNull(TagRules.check((1..10).associate { "k$it" to "v" }))
        assertEquals(TagRules.Rejection.TOO_MANY, TagRules.check((1..11).associate { "k$it" to "v" }))
    }

    @Test
    fun `the bound on a key and on a value is a bound rather than a ban`() {
        // `test_put_max_kvsize_tags:12087` puts exactly 128 and 256 and expects success; the two
        // cases next to it add one character each and expect a refusal. Both sides of the boundary
        // have to be checked: a "greater than or equal" comparison would pass half the suite and
        // fail the other half.
        assertNull(TagRules.check(mapOf("a".repeat(128) to "b".repeat(256))))
        assertEquals(TagRules.Rejection.KEY_TOO_LONG, TagRules.check(mapOf("a".repeat(129) to "b")))
        assertEquals(TagRules.Rejection.VALUE_TOO_LONG, TagRules.check(mapOf("a" to "b".repeat(257))))
    }

    @Test
    fun `characters are counted rather than bytes`() {
        // "128 Unicode characters", and the difference shows only outside ASCII — that is, only
        // where nobody checks. A hundred and twenty-eight emoji outside the BMP are 512 bytes of
        // UTF-8 and 256 UTF-16 units, and either number would refuse where S3 succeeds.
        val emoji = "😀".repeat(128)
        assertEquals(256, emoji.length, "surrogate pairs: the UTF-16 length is twice as long")
        assertNull(TagRules.check(mapOf(emoji to "v")))
        assertEquals(TagRules.Rejection.KEY_TOO_LONG, TagRules.check(mapOf(emoji + "😀" to "v")))
    }

    @Test
    fun `an empty value is allowed and an empty key is not`() {
        // An empty value is a legal tag: `x-amz-tagging: foo=bar&bar` sends exactly one.
        assertNull(TagRules.check(mapOf("bar" to "")))
        assertEquals(TagRules.Rejection.EMPTY_KEY, TagRules.check(mapOf("" to "v")))
    }
}
