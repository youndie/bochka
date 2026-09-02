package io.github.youndie.bochka.http

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRequestParserTest {
    private fun parse(raw: String): HttpRequestParser.Head {
        val parser = HttpRequestParser()
        parser.feed(raw.toByteArray(Charsets.ISO_8859_1))
        return assertNotNull(parser.head, "the head did not complete")
    }

    private fun get(
        target: String = "/photos/a.txt",
        extra: String = "",
    ) = "GET $target HTTP/1.1\r\nHost: localhost:9000\r\n$extra\r\n"

    /** One limit at a time: everything not named here is out of the way of the one under test. */
    private fun limits(
        requestLineBytes: Int = 1 shl 20,
        headerLineBytes: Int = 1 shl 20,
        headerCount: Int = 10_000,
        headBytes: Int = 1 shl 20,
    ) = HttpRequestParser.Limits(requestLineBytes, headerLineBytes, headerCount, headBytes)

    private fun feed(
        bound: HttpRequestParser.Limits,
        raw: String,
    ) = HttpRequestParser(bound).also { it.feed(raw.toByteArray(Charsets.ISO_8859_1)) }

    private fun refusal(
        bound: HttpRequestParser.Limits,
        raw: String,
    ) = assertFailsWith<HttpRequestParser.Malformed> { feed(bound, raw) }

    /** A request line of exactly [length] characters, padding the target to get there. */
    private fun requestLine(length: Int): String {
        val around = "GET /".length + " HTTP/1.1".length
        return "GET /${"a".repeat(length - around)} HTTP/1.1"
    }

    @Test
    fun `a request line and its headers come apart`() {
        val head = parse(get(extra = "X-Amz-Date: 20260817T100000Z\r\nContent-Length: 0\r\n"))

        assertEquals("GET", head.method)
        assertEquals("/photos/a.txt", head.target)
        assertEquals("HTTP/1.1", head.version)
        assertEquals("localhost:9000", head.header("host"))
        assertEquals("20260817T100000Z", head.header("x-amz-date"), "header lookup is case-insensitive")
        assertEquals(0, head.contentLength)
    }

    @Test
    fun `the target is kept exactly as it arrived`() {
        // Everything downstream depends on this: the signature covers the path as it travelled, so
        // a parser that decodes `%2F` here changes which object the request is about.
        val head = parse(get(target = "/photos/a%2Fb.txt?list-type=2&prefix=a%2Fb"))

        assertEquals("/photos/a%2Fb.txt", head.path)
        assertEquals("list-type=2&prefix=a%2Fb", head.query)
    }

    @Test
    fun `a raw non-ascii byte in the target survives as a byte`() {
        // Clients should encode it, some do not, and the signature is computed over what arrived.
        val raw = "GET /á´ HTTP/1.1\r\nHost: h\r\n\r\n"
        val head = parse(raw)

        assertEquals(listOf(0xE1, 0x88, 0xB4), head.path.drop(1).map { it.code })
    }

    @Test
    fun `the head can arrive one byte at a time`() {
        val raw = get(extra = "X-Amz-Content-Sha256: UNSIGNED-PAYLOAD\r\n").toByteArray(Charsets.ISO_8859_1)
        val parser = HttpRequestParser()

        for (i in raw.indices) parser.feed(raw, i, 1)

        assertTrue(parser.isComplete)
        assertEquals("UNSIGNED-PAYLOAD", parser.head?.header("x-amz-content-sha256"))
    }

    @Test
    fun `the body is left for the caller`() {
        val raw = "PUT /b/k HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello".toByteArray(Charsets.ISO_8859_1)
        val parser = HttpRequestParser()

        val taken = parser.feed(raw)

        assertTrue(parser.isComplete)
        assertEquals(raw.size - 5, taken, "the parser must stop at the end of the head")
        assertEquals("hello", String(raw, taken, raw.size - taken))
    }

    @Test
    fun `keep-alive follows the version unless the client says otherwise`() {
        assertTrue(parse(get()).keepAlive, "HTTP/1.1 keeps the connection by default")
        assertFalse(parse(get(extra = "Connection: close\r\n")).keepAlive)

        val oneZero = parse("GET / HTTP/1.0\r\nHost: h\r\n\r\n")
        assertFalse(oneZero.keepAlive, "HTTP/1.0 closes by default")
        assertTrue(parse("GET / HTTP/1.0\r\nHost: h\r\nConnection: keep-alive\r\n\r\n").keepAlive)
    }

    @Test
    fun `expect 100-continue is recognised`() {
        assertTrue(parse(get(extra = "Expect: 100-continue\r\n")).expectsContinue)
        assertTrue(parse(get(extra = "expect: 100-Continue\r\n")).expectsContinue, "the token is case-insensitive")
        assertFalse(parse(get()).expectsContinue)
    }

    @Test
    fun `a chunked body has no length and a length body is not chunked`() {
        val chunked = parse(get(extra = "Transfer-Encoding: chunked\r\n"))
        assertTrue(chunked.isChunked)
        assertNull(chunked.contentLength)

        val sized = parse(get(extra = "Content-Length: 11\r\n"))
        assertFalse(sized.isChunked)
        assertEquals(11, sized.contentLength)
    }

    @Test
    fun `content-length together with transfer-encoding is refused`() {
        // Two answers to "where does this body end". A proxy in front believing one and this
        // believing the other is a request boundary the two see differently.
        val e =
            assertFailsWith<HttpRequestParser.Malformed> {
                parse(get(extra = "Content-Length: 5\r\nTransfer-Encoding: chunked\r\n"))
            }
        assertEquals(400, e.status)
    }

    @Test
    fun `conflicting content-length headers are refused`() {
        val e =
            assertFailsWith<HttpRequestParser.Malformed> {
                parse(get(extra = "Content-Length: 5\r\nContent-Length: 6\r\n"))
            }
        assertEquals(400, e.status)

        // The same value twice is not a conflict, only noise.
        assertEquals(5, parse(get(extra = "Content-Length: 5\r\nContent-Length: 5\r\n")).contentLength)
    }

    @Test
    fun `obsolete line folding is refused`() {
        val e = assertFailsWith<HttpRequestParser.Malformed> { parse(get(extra = "X-Long: one\r\n  two\r\n")) }
        assertEquals(400, e.status)
    }

    @Test
    fun `whitespace before the colon is refused`() {
        val e = assertFailsWith<HttpRequestParser.Malformed> { parse(get(extra = "Content-Length : 5\r\n")) }
        assertEquals(400, e.status)
    }

    @Test
    fun `a control character in a header value is refused`() {
        // Found by putting nginx from `deploy/` in front and sending the same bytes to both
        // (M-281, `ci/smuggling.sh`): nginx answered 400 and this server answered 200. A NUL is
        // the end of a string to everything written in C, so a value carrying one means two
        // readers with two different values — which is the same shape as the three framings this
        // parser already refuses, one field further in.
        val nul =
            assertFailsWith<HttpRequestParser.Malformed> { parse(get(extra = "X-Amz-Meta-A: b\u0000c\r\n")) }
        assertEquals(400, nul.status)

        // The whole class, not the one byte that was found: DEL is outside `field-vchar` too.
        val del =
            assertFailsWith<HttpRequestParser.Malformed> { parse(get(extra = "X-Amz-Meta-A: b\u007Fc\r\n")) }
        assertEquals(400, del.status)
    }

    @Test
    fun `a tab and a byte above ASCII stay allowed in a header value`() {
        // The other half, and it is not decoration: `obs-text` is legal in a field value, and a
        // metadata header carrying UTF-8 is exactly what an object store receives. A refusal
        // written as "anything unusual" would have broken it.
        val head = parse(get(extra = "X-Amz-Meta-A: b\tc\u00e9\r\n"))
        assertEquals("b\tc\u00e9", head.header("x-amz-meta-a"))
    }

    @Test
    fun `a request without host is refused on http 1_1`() {
        val e =
            assertFailsWith<HttpRequestParser.Malformed> {
                parse("GET /b/k HTTP/1.1\r\nX-Amz-Date: 20260817T100000Z\r\n\r\n")
            }
        assertEquals(400, e.status)
    }

    @Test
    fun `a bare newline is not a line ending`() {
        val e = assertFailsWith<HttpRequestParser.Malformed> { parse("GET / HTTP/1.1\nHost: h\n\n") }
        assertEquals(400, e.status)
    }

    @Test
    fun `a space inside the target is refused`() {
        // The signature vectors contain exactly this and it is legal there because that file is a
        // fixture; a client cannot send it, and accepting it would mean guessing where the target
        // ends.
        val e =
            assertFailsWith<HttpRequestParser.Malformed> {
                parse("GET /example space/ HTTP/1.1\r\nHost: h\r\n\r\n")
            }
        assertEquals(400, e.status)
    }

    @Test
    fun `an unknown http version is refused with 505`() {
        val e = assertFailsWith<HttpRequestParser.Malformed> { parse("GET / HTTP/2.0\r\nHost: h\r\n\r\n") }
        assertEquals(505, e.status)
    }

    @Test
    fun `the shipped limits are the ones a request actually meets`() {
        // Kept for the defaults: every boundary test below installs its own numbers, so without
        // this one nothing would ever run into `Limits.DEFAULT`. What it does **not** show is
        // where the bound is — that is the four tests after it, and the difference is the whole
        // reason they exist.
        val longLine =
            assertFailsWith<HttpRequestParser.Malformed> {
                parse("GET /${"a".repeat(9000)} HTTP/1.1\r\nHost: h\r\n\r\n")
            }
        assertEquals(431, longLine.status)

        val manyHeaders =
            assertFailsWith<HttpRequestParser.Malformed> {
                parse(get(extra = (1..200).joinToString("") { "X-H$it: v\r\n" }))
            }
        assertEquals(431, manyHeaders.status)
    }

    @Test
    fun `any input at all answers with a status rather than an exception nobody mapped`() {
        // Fuzz, and the property is not "it parses" — it is that a parser on an unauthenticated
        // path answers with a status rather than with an exception nobody mapped. Anything other
        // than Malformed here becomes a 500 in production, or worse, a thread that dies quietly.
        val random = Random(20260817)
        val alphabet = "GET POST /?&=\r\n:abc  ÿ%".toCharArray()

        repeat(20_000) {
            val length = random.nextInt(0, 64)
            val raw = CharArray(length) { alphabet[random.nextInt(alphabet.size)] }.concatToString()
            val parser = HttpRequestParser()
            try {
                parser.feed(raw.toByteArray(Charsets.ISO_8859_1))
            } catch (_: HttpRequestParser.Malformed) {
                // The only failure a caller has to handle.
            }
        }
    }

    @Test
    fun `a head split at every possible point parses the same`() {
        val raw =
            get(extra = "Content-Length: 3\r\nX-Amz-Date: 20260817T100000Z\r\nExpect: 100-continue\r\n")
                .toByteArray(Charsets.ISO_8859_1)
        val whole = HttpRequestParser().also { it.feed(raw) }.head

        for (split in 1 until raw.size) {
            val parser = HttpRequestParser()
            parser.feed(raw, 0, split)
            parser.feed(raw, split, raw.size - split)
            assertEquals(whole, parser.head, "split at $split")
        }
    }

    @Test
    fun `the request line limit is exact, and the carriage return counts against it`() {
        // What stood here for four releases sent 9000 bytes into an 8192-byte limit. That proves a
        // limit exists and says nothing about where it is — and where it is, is the whole of it: an
        // off-by-one on this path either refuses a legal presigned URL or accepts a byte past the
        // bound everything downstream was sized for. Found by turning `>` into `>=` and watching
        // the whole suite stay green.
        val limit = 64
        val bound = limits(requestLineBytes = limit)

        // The parser appends every byte that is not LF, so the CR is already in the buffer when the
        // check runs: the longest line that fits is one **shorter** than the configured number.
        feed(bound, "${requestLine(limit - 1)}\r\nHost: h\r\n\r\n")
        assertEquals(431, refusal(bound, "${requestLine(limit)}\r\nHost: h\r\n\r\n").status)
    }

    @Test
    fun `the request line and a header line are bounded separately`() {
        // Which of the two applies is chosen by `requestLine == null`, and swapping the arms left
        // every test green: only a request that is long in one place and short in the other can
        // tell them apart.
        val bound = limits(requestLineBytes = 200, headerLineBytes = 40)

        feed(bound, "${requestLine(120)}\r\nHost: h\r\n\r\n")
        assertEquals(431, refusal(bound, "GET / HTTP/1.1\r\nHost: h\r\nX-H: ${"v".repeat(60)}\r\n\r\n").status)
    }

    @Test
    fun `the header count limit is exact, and Host counts against it`() {
        val bound = limits(headerCount = 4)
        val extra = { n: Int -> (1..n).joinToString("") { "X-H$it: v\r\n" } }

        feed(bound, "GET / HTTP/1.1\r\nHost: h\r\n${extra(3)}\r\n")
        assertEquals(431, refusal(bound, "GET / HTTP/1.1\r\nHost: h\r\n${extra(4)}\r\n").status)
    }

    @Test
    fun `the head as a whole is bounded, and nothing was measuring it`() {
        // The only one of the four limits no test touched at all. "A thousand short headers cannot
        // do what one long one cannot" was a sentence in the KDoc rather than a thing anybody ran.
        val head = "GET / HTTP/1.1\r\nHost: h\r\n\r\n"

        feed(limits(headBytes = head.length), head)
        assertEquals(431, refusal(limits(headBytes = head.length - 1), head).status)
    }

    @Test
    fun `an unfinished head says so and gives back everything it took`() {
        // Both halves of this were unobserved. `isComplete` was only ever asserted true, so a
        // parser that said "complete" early would hand the body's first bytes to the router; and
        // the count returned on the way out of the loop was never compared to anything, so one
        // that under-reported would make the caller feed the same bytes twice.
        val raw = "GET /b/k HTTP/1.1\r\nHost: h\r\n".toByteArray(Charsets.ISO_8859_1)
        val parser = HttpRequestParser()

        assertEquals(raw.size, parser.feed(raw), "an incomplete head still consumed all of it")
        assertFalse(parser.isComplete, "there is no blank line yet")
        assertNull(parser.head)

        assertEquals(2, parser.feed("\r\n".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(parser.isComplete)
        assertEquals(0, parser.feed("hello".toByteArray(Charsets.ISO_8859_1)), "a finished parser takes nothing")
    }

    @Test
    fun `the method is upper-case letters and a header name is visible ascii`() {
        // Neither range had ever been sent anything: no test used a lower-case method, and none put
        // a control character in a header name. On an unauthenticated path the second is how a
        // header gets smuggled past whatever is in front of this.
        for (
        raw in
        listOf(
            "get / HTTP/1.1\r\nHost: h\r\n\r\n",
            " / HTTP/1.1\r\nHost: h\r\n\r\n",
            get(extra = "X\u001fH: v\r\n"),
            get(extra = "X\u007fH: v\r\n"),
            get(extra = ": v\r\n"),
        )
        ) {
            assertEquals(400, assertFailsWith<HttpRequestParser.Malformed>(raw) { parse(raw) }.status, raw)
        }

        // And the other side of each edge, because a refusal is only exact if what stands next to
        // it passes: these are all `tchar`, and all of them sit between 0x20 and 0x7F.
        assertEquals("v", parse(get(extra = "X-A!#*.^_|~B: v\r\n")).header("x-a!#*.^_|~b"))
    }
}
