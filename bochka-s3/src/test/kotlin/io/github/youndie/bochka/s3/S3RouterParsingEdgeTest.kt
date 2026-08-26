package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The three small parsers the router runs before it decides anything (M-252).
 *
 * A copy source, a host, a query string. Every one of them is read off an unauthenticated request,
 * and every one of them was only ever given well-formed input: a bucket and a key with something on
 * both sides of the slash, a host comfortably longer than the suffix, a query whose every token has
 * a name and a value. Nineteen mutations of their index arithmetic survived on that diet.
 *
 * What a mistake costs here is not an error — it is a request that routes, and routes to the wrong
 * object. An empty bucket name in a copy source is a copy from somewhere else; an empty parameter
 * name in a query is a sub-resource nobody asked for.
 */
class S3RouterParsingEdgeTest {
    private val pathStyle = S3Router()
    private val virtual = S3Router(virtualHostSuffixes = listOf("s3.example.com"))

    private fun copy(source: String) = pathStyle.route("PUT", "h", "/photos/a.txt", "", copySource = source)

    @Test
    fun `a copy source needs a bucket and a key, and both have to be there`() {
        assertEquals(
            S3Router.Route.CopyObject("photos", ObjectKey.of("a.txt"), "src", ObjectKey.of("b.txt"), null),
            copy("/src/b.txt"),
        )

        // No bucket: the slash that should separate them is the first character there is.
        assertIs<S3Router.Route.NotImplemented>(copy("//b.txt"), "an empty bucket is not a source")
        // No key: the slash is the last character.
        assertIs<S3Router.Route.NotImplemented>(copy("/src/"), "an empty key is not a source")
        // Neither: one segment and nothing to split.
        assertIs<S3Router.Route.NotImplemented>(copy("/src"))
        assertIs<S3Router.Route.NotImplemented>(copy("/"))
        assertIs<S3Router.Route.NotImplemented>(copy(""))
    }

    @Test
    fun `a version in a copy source is a value, and an empty one is no version`() {
        assertEquals(
            S3Router.Route.CopyObject("photos", ObjectKey.of("a.txt"), "src", ObjectKey.of("b.txt"), "v-1"),
            copy("/src/b.txt?versionId=v-1"),
        )
        assertEquals(
            S3Router.Route.CopyObject("photos", ObjectKey.of("a.txt"), "src", ObjectKey.of("b.txt"), null),
            copy("/src/b.txt?versionId="),
            "an empty version id names nothing, and naming nothing is not naming the current one wrongly",
        )
        assertEquals(
            S3Router.Route.CopyObject("photos", ObjectKey.of("a.txt"), "src", ObjectKey.of("b.txt"), null),
            copy("/src/b.txt?other=1"),
        )
    }

    @Test
    fun `a host that is only the suffix addresses no bucket`() {
        // `.s3.example.com` ends with the dotted suffix and has nothing in front of it. Taking it
        // as virtual-hosted addressing gives a bucket whose name is the empty string, and every
        // path under it becomes a key in a bucket that cannot exist.
        assertIs<S3Router.Route.ListObjects>(virtual.route("GET", ".s3.example.com", "/photos", ""))
        assertEquals(
            S3Router.Route.ListObjects("photos"),
            virtual.route("GET", ".s3.example.com", "/photos", ""),
            "the first path segment is the bucket, as it is for any host that is not a suffix",
        )

        // One character in front of it is enough to be a bucket.
        assertEquals(S3Router.Route.ListObjects("p"), virtual.route("GET", "p.s3.example.com", "/", ""))
    }

    @Test
    fun `a query token without a name is not a parameter`() {
        // `=v` has a value and nothing to call it. Reading the whole token as the name instead
        // invents a parameter, and a parameter is what decides which operation this is.
        assertEquals(
            S3Router.Route.ListObjectsV2("photos"),
            pathStyle.route("GET", "h", "/photos", "=v&list-type=2"),
        )

        // Empty tokens are not parameters either: a query of `a=1&&b=2` has two.
        assertEquals(
            S3Router.Route.ListObjectsV2("photos"),
            pathStyle.route("GET", "h", "/photos", "&&list-type=2&&"),
        )
    }

    @Test
    fun `a parameter whose value is empty still has its name`() {
        // `?uploads=` and `?uploads` are the same request, and the second is how every client
        // actually writes it. A parser that needs an `=` to find a name loses the operation.
        assertEquals(S3Router.Route.ListMultipartUploads("photos"), pathStyle.route("GET", "h", "/photos", "uploads="))
        assertEquals(S3Router.Route.ListMultipartUploads("photos"), pathStyle.route("GET", "h", "/photos", "uploads"))
    }
}
