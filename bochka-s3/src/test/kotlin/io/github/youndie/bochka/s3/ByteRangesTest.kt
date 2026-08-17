package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Range`, resolved against a size.
 *
 * The arithmetic is RFC 9110 §14.1.2; the answers to headers that do not resolve are `ceph/s3-tests`
 * (`s3tests_boto3/functional/test_s3.py`, `test_ranged_request_*`), which is the only place the
 * three outcomes are written down together.
 */
class ByteRangesTest {
    private fun resolve(
        header: String?,
        size: Long = 11,
    ) = ByteRanges.resolve(header, size)

    private fun satisfiable(
        start: Long,
        end: Long,
    ) = ByteRanges.Resolved.Satisfiable(start, end)

    @Test
    fun `no header is the whole object`() {
        assertEquals(ByteRanges.Resolved.Whole, resolve(null))
    }

    @Test
    fun `a closed range is the bytes it names, last one included`() {
        // test_ranged_request_response_code: 'testcontent'[4:8] against `bytes=4-7`, so the last
        // position is part of the answer. Reading it as exclusive returns three bytes and a
        // Content-Range that contradicts them.
        assertEquals(satisfiable(4, 7), resolve("bytes=4-7"))
        assertEquals(4, (resolve("bytes=4-7") as ByteRanges.Resolved.Satisfiable).length)
    }

    @Test
    fun `an open range runs to the end`() {
        // test_ranged_request_skip_leading_bytes_response_code.
        assertEquals(satisfiable(4, 10), resolve("bytes=4-"))
    }

    @Test
    fun `a suffix range counts back from the end`() {
        // test_ranged_request_return_trailing_bytes_response_code.
        assertEquals(satisfiable(4, 10), resolve("bytes=-7"))
    }

    @Test
    fun `a suffix longer than the object is the whole object`() {
        assertEquals(satisfiable(0, 10), resolve("bytes=-100"))
    }

    @Test
    fun `an end past the object stops at the object`() {
        assertEquals(satisfiable(9, 10), resolve("bytes=9-100"))
    }

    @Test
    fun `a range that starts past the end is unsatisfiable`() {
        // test_ranged_request_invalid_range: `bytes=40-50` on eleven bytes is 416 InvalidRange.
        assertEquals(ByteRanges.Resolved.Unsatisfiable, resolve("bytes=40-50"))
    }

    @Test
    fun `any range on an empty object is unsatisfiable`() {
        // test_ranged_request_empty_object. There is no first byte, so nothing can be returned —
        // and note this is the one case where 416 is right for a request that would be fine on a
        // non-empty object.
        assertEquals(ByteRanges.Resolved.Unsatisfiable, resolve("bytes=40-50", size = 0))
        assertEquals(ByteRanges.Resolved.Unsatisfiable, resolve("bytes=0-", size = 0))
        assertEquals(ByteRanges.Resolved.Unsatisfiable, resolve("bytes=-1", size = 0))
    }

    @Test
    fun `a zero-length suffix asks for nothing and is refused`() {
        assertEquals(ByteRanges.Resolved.Unsatisfiable, resolve("bytes=-0"))
    }

    @Test
    fun `a header that does not parse is ignored rather than refused`() {
        // RFC 9110 §14.2: a recipient that cannot honour a Range serves the whole thing. Answering
        // 416 here would fail requests S3 succeeds at.
        assertEquals(ByteRanges.Resolved.Whole, resolve("bytes=abc-def"))
        assertEquals(ByteRanges.Resolved.Whole, resolve("bytes="))
        assertEquals(ByteRanges.Resolved.Whole, resolve("bytes=7-4"))
        assertEquals(ByteRanges.Resolved.Whole, resolve("items=0-1"))
        assertEquals(ByteRanges.Resolved.Whole, resolve("0-1"))
    }

    @Test
    fun `several ranges are served as the whole object`() {
        // "Amazon S3 doesn't support retrieving multiple ranges of data per GET request"
        // (GetObject documentation). No multipart/byteranges, so the honest answer is everything.
        assertEquals(ByteRanges.Resolved.Whole, resolve("bytes=0-1,3-4"))
    }

    @Test
    fun `the headers name positions the client can read back`() {
        assertEquals("bytes 4-7/11", ByteRanges.contentRange(satisfiable(4, 7), 11))
        assertEquals("bytes */11", ByteRanges.unsatisfiedRange(11))
    }
}
