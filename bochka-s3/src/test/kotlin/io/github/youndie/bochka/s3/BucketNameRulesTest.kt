package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bucket naming, from `docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html`.
 *
 * There is nothing to cite in `s3-service-2.json` — `BucketName` is a bare string there — so the
 * source is the documentation page and the reference server's `s3utils.CheckValidBucketName`.
 */
class BucketNameRulesTest {
    @Test
    fun `ordinary names pass`() {
        for (name in listOf("photos", "my-bucket", "a.b.c", "bucket123", "1bucket", "a1b", "x".repeat(63))) {
            assertNull(BucketNameRules.check(name), name)
        }
    }

    @Test
    fun `length is bounded at both ends`() {
        assertEquals(BucketNameRules.Rejection.TOO_SHORT, BucketNameRules.check("ab"))
        assertEquals(BucketNameRules.Rejection.TOO_LONG, BucketNameRules.check("x".repeat(64)))
    }

    @Test
    fun `uppercase and underscores are not names`() {
        // The rule is not aesthetic: a bucket reaches a Host header, and DNS labels have no case.
        assertEquals(BucketNameRules.Rejection.BAD_CHARACTER, BucketNameRules.check("MyBucket"))
        assertEquals(BucketNameRules.Rejection.BAD_CHARACTER, BucketNameRules.check("my_bucket"))
        assertEquals(BucketNameRules.Rejection.BAD_CHARACTER, BucketNameRules.check("my bucket"))
        assertEquals(BucketNameRules.Rejection.BAD_CHARACTER, BucketNameRules.check("a/b"))
    }

    @Test
    fun `the edges must be a letter or a digit`() {
        assertEquals(BucketNameRules.Rejection.BAD_EDGE, BucketNameRules.check("-bucket"))
        assertEquals(BucketNameRules.Rejection.BAD_EDGE, BucketNameRules.check("bucket-"))
        assertEquals(BucketNameRules.Rejection.BAD_EDGE, BucketNameRules.check(".bucket"))
        assertEquals(BucketNameRules.Rejection.BAD_EDGE, BucketNameRules.check("bucket."))
    }

    @Test
    fun `two periods in a row are not a name`() {
        assertEquals(BucketNameRules.Rejection.ADJACENT_PERIODS, BucketNameRules.check("my..bucket"))
    }

    @Test
    fun `a name shaped like an address is refused, and a name merely containing one is not`() {
        // Four numeric labels is an address; five is a name. The rule exists because the first is
        // ambiguous with the host in a virtual-hosted URL and the second is not.
        assertEquals(BucketNameRules.Rejection.LOOKS_LIKE_AN_ADDRESS, BucketNameRules.check("192.168.5.4"))
        assertNull(BucketNameRules.check("192.168.5.4.5"))
        assertNull(BucketNameRules.check("192.168.5.400"))
    }

    @Test
    fun `reserved prefixes and suffixes are refused`() {
        assertEquals(BucketNameRules.Rejection.RESERVED_PREFIX, BucketNameRules.check("xn--bucket"))
        assertEquals(BucketNameRules.Rejection.RESERVED_PREFIX, BucketNameRules.check("sthree-bucket"))
        assertEquals(BucketNameRules.Rejection.RESERVED_SUFFIX, BucketNameRules.check("bucket-s3alias"))
        assertEquals(BucketNameRules.Rejection.RESERVED_SUFFIX, BucketNameRules.check("bucket--ol-s3"))
    }
}
