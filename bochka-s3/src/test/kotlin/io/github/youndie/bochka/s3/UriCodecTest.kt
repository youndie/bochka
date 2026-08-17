package io.github.youndie.bochka.s3

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Sources: `docs/spec/s3-signing-vectors/key-encoding` for the path, generated from Python's
 * `quote` through botocore; `minio/minio`, `cmd/api-utils.go:28-44` for the listing response.
 */
class UriCodecTest {
    private val specDir: Path =
        Path.of(
            System.getProperty("bochka.specDir")
                ?: error("bochka.specDir is not set; the build has to point tests at docs/spec"),
        )

    @Test
    fun `encoding a path agrees with the botocore key-encoding vectors`() {
        val lines = Files.readAllLines(specDir.resolve("s3-signing-vectors/key-encoding"))
        val cases = lines.filter { it.isNotBlank() }.map { it.split("\t") }
        check(cases.size >= 9) { "the vector file looks truncated: ${cases.size} cases" }

        for ((key, expected) in cases.map { it[0] to it[1] }) {
            assertEquals(expected, UriCodec.encodePath(key.toByteArray()), "key '$key'")
        }
    }

    @Test
    fun `a space is percent twenty in both encoders, and the two still differ`() {
        // This assertion said `my+dir/file.txt` for the listing until `ceph/s3-tests` disagreed
        // (`test_bucket_list_encoding_basic`): botocore decodes a listing with `unquote`, which
        // leaves a `+` as a `+`, so a key with a space came back as a key with a plus. The `+`
        // was a guess, and it was consistent with itself in both directions — which is exactly
        // the kind of wrong that only somebody else's client can find.
        val key = "my dir/file.txt".toByteArray()

        assertEquals("my%20dir/file.txt", UriCodec.encodePath(key))
        assertEquals("my%20dir/file.txt", UriCodec.encodeForListing(key))
    }

    @Test
    fun `a listing escapes tilde and leaves star, the path does the opposite`() {
        // `cmd/api-utils.go:40-43`: "Avoid encoding '/' and '*'", "Force encoding of '~'".
        assertEquals("a~b", UriCodec.encodePath("a~b".toByteArray()))
        assertEquals("a%7Eb", UriCodec.encodeForListing("a~b".toByteArray()))

        assertEquals("a%2Ab", UriCodec.encodePath("a*b".toByteArray()))
        assertEquals("a*b", UriCodec.encodeForListing("a*b".toByteArray()))
    }

    @Test
    fun `a literal plus in a key survives the listing round trip`() {
        // The one thing that would make `+` ambiguous: it does not happen, because a literal plus
        // is not unreserved and comes back as %2B.
        assertEquals("a%2Bb", UriCodec.encodeForListing("a+b".toByteArray()))
        assertEquals("a%2Bb", UriCodec.encodePath("a+b".toByteArray()))
    }

    @Test
    fun `decoding a path leaves plus alone`() {
        // A path is not a form. Decoding `+` as a space here would rename every key containing one.
        assertContentEquals("a+b".toByteArray(), UriCodec.decode("a+b"))
        assertContentEquals("a b".toByteArray(), UriCodec.decode("a%20b"))
    }

    @Test
    fun `decoding does not normalise dot segments`() {
        // `docs/spec/reference/botocore-auth.py:538` — S3 signs the path as it is. Collapsing these
        // would both break the signature and store the object under a different key.
        assertContentEquals("a/./b".toByteArray(), UriCodec.decode("a/./b"))
        assertContentEquals("a/../b".toByteArray(), UriCodec.decode("a/../b"))
        assertContentEquals("a//b".toByteArray(), UriCodec.decode("a//b"))
    }

    @Test
    fun `decoding keeps bytes that are not text`() {
        // %01 is a legal byte in a key and cannot be represented in XML 1.0 at all, which is what
        // `encoding-type=url` exists for.
        assertContentEquals(byteArrayOf(1), UriCodec.decode("%01"))
        assertContentEquals(byteArrayOf(0xC3.toByte(), 0x28), UriCodec.decode("%C3%28"))
        // Lower-case hex arrives from real clients too.
        assertContentEquals(byteArrayOf(0xC3.toByte(), 0x28), UriCodec.decode("%c3%28"))
    }

    @Test
    fun `decoding round-trips a non-ascii key`() {
        val key = "файл.txt".toByteArray()
        assertContentEquals(key, UriCodec.decode(UriCodec.encodePath(key)))
        assertEquals("%D1%84%D0%B0%D0%B9%D0%BB.txt", UriCodec.encodePath(key))
    }

    @Test
    fun `a malformed escape is refused rather than guessed at`() {
        assertFailsWith<IllegalArgumentException> { UriCodec.decode("a%2") }
        assertFailsWith<IllegalArgumentException> { UriCodec.decode("a%zz") }
        assertFailsWith<IllegalArgumentException> { UriCodec.decode("a%") }
    }

    @Test
    fun `a char above 0xff means the request line was decoded as text somewhere upstream`() {
        // Not a theoretical case: reading the request line as UTF-8 loses the original bytes, and
        // the failure has to name that rather than silently truncate.
        assertFailsWith<IllegalArgumentException> { UriCodec.decode("ф") }
    }
}
