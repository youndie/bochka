package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CORS: the configuration and the preflight (M-93, M-94).
 *
 * The shape is in `docs/spec/s3-service-2.json`: `CORSConfiguration` (`:2241`) holds `CORSRule`
 * (`:2253`), which requires `AllowedMethods` and `AllowedOrigins`.
 *
 * Storage here is as dull as it is for tags; all of the reasoning lives in `OPTIONS`. It is the one
 * thing this server answers **unsigned**, and that is not a leniency: a browser sends a preflight
 * before any authorisation, by the RFC's definition, and there is nothing to sign it with.
 */
class CorsTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private val rules =
        (
            "<CORSConfiguration><CORSRule>" +
                "<AllowedMethod>GET</AllowedMethod><AllowedMethod>PUT</AllowedMethod>" +
                "<AllowedOrigin>https://example.com</AllowedOrigin>" +
                "<AllowedHeader>x-amz-*</AllowedHeader>" +
                "<MaxAgeSeconds>3000</MaxAgeSeconds>" +
                "</CORSRule></CORSConfiguration>"
        ).toByteArray()

    @Test
    fun `a bucket with no CORS answers NoSuchCORSConfiguration`() {
        s3.createBucket("photos")

        val answer = s3.send("GET", "/photos", query = "cors")

        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchCORSConfiguration")
    }

    @Test
    fun `the configuration is put, read and removed`() {
        s3.createBucket("photos")

        assertEquals(200, s3.send("PUT", "/photos", query = "cors", body = rules).status)

        val read = s3.send("GET", "/photos", query = "cors")
        assertEquals(200, read.status, read.text)
        assertContains(read.text, "<AllowedMethod>GET</AllowedMethod>")
        assertContains(read.text, "<AllowedOrigin>https://example.com</AllowedOrigin>")

        assertEquals(204, s3.send("DELETE", "/photos", query = "cors").status)
        assertEquals(404, s3.send("GET", "/photos", query = "cors").status)
    }

    @Test
    fun `a preflight of an allowed origin and method answers with the access headers`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = rules)

        val answer =
            s3.options(
                "/photos/a.txt",
                listOf("Origin" to "https://example.com", "Access-Control-Request-Method" to "GET"),
            )

        assertEquals(200, answer.status, answer.text)
        assertEquals("https://example.com", answer.header("Access-Control-Allow-Origin"))
        assertContains(answer.header("Access-Control-Allow-Methods")!!, "GET")
    }

    @Test
    fun `a preflight from another origin is refused rather than quietly allowed`() {
        // A browser reads a `200` with no access headers as a refusal — but the right answer here
        // is `403`, and the difference shows to whoever is debugging: "no rule matched" against "a
        // rule matched and allowed nothing".
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = rules)

        val answer =
            s3.options(
                "/photos/a.txt",
                listOf("Origin" to "https://evil.example", "Access-Control-Request-Method" to "GET"),
            )

        assertEquals(403, answer.status, answer.text)
        assertNull(answer.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun `a preflight of a forbidden method is refused even when the origin is allowed`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = rules)

        val answer =
            s3.options(
                "/photos/a.txt",
                listOf("Origin" to "https://example.com", "Access-Control-Request-Method" to "DELETE"),
            )

        assertEquals(403, answer.status, answer.text)
    }

    @Test
    fun `a preflight to a bucket with no configuration is refused`() {
        s3.createBucket("photos")

        val answer =
            s3.options(
                "/photos/a.txt",
                listOf("Origin" to "https://example.com", "Access-Control-Request-Method" to "GET"),
            )

        assertEquals(403, answer.status, answer.text)
    }

    @Test
    fun `a wildcard in an origin matches by the S3 rule rather than as a regular expression`() {
        // S3 allows **one** asterisk anywhere in the origin and matches everything else literally.
        // A regular expression in this place is a way to allow more than was asked: in it, the dot
        // of `example.com` means "any character".
        s3.createBucket("photos")
        val wildcard =
            (
                "<CORSConfiguration><CORSRule>" +
                    "<AllowedMethod>GET</AllowedMethod>" +
                    "<AllowedOrigin>https://*.example.com</AllowedOrigin>" +
                    "</CORSRule></CORSConfiguration>"
            ).toByteArray()
        s3.send("PUT", "/photos", query = "cors", body = wildcard)

        fun preflight(origin: String) =
            s3.options("/photos/a.txt", listOf("Origin" to origin, "Access-Control-Request-Method" to "GET")).status

        assertEquals(200, preflight("https://app.example.com"))
        assertEquals(403, preflight("https://appXexample.com"), "a dot is a dot rather than any character")
        assertEquals(403, preflight("http://app.example.com"), "the scheme is matched literally")
    }

    @Test
    fun `a preflight checks the requested headers too, not only the method and the origin`() {
        // M-177, `test_cors_header_option:7016`. The rule allows the method and the origin and
        // **names no header at all**, while the browser asks about `x-amz-meta-header2`.
        // `ExposeHeaders` has nothing to do with it: that is about what the browser will be allowed
        // to read in the answer, while a preflight asks about `AllowedHeaders`. Not looking at them,
        // the server allowed a request S3 refuses — that is, opened a little wider than it was
        // asked to.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send(
                "PUT",
                "/photos",
                query = "cors",
                body =
                    (
                        "<CORSConfiguration><CORSRule><AllowedMethod>GET</AllowedMethod>" +
                            "<AllowedOrigin>*</AllowedOrigin>" +
                            "<ExposeHeader>x-amz-meta-header1</ExposeHeader></CORSRule></CORSConfiguration>"
                    ).toByteArray(),
            )

            val refused =
                s3.options(
                    "/photos/bar",
                    headers =
                        listOf(
                            "Origin" to "example.origin",
                            "Access-Control-Request-Method" to "GET",
                            "Access-Control-Request-Headers" to "x-amz-meta-header2",
                        ),
                )
            assertEquals(403, refused.status, refused.text)

            // With no headers asked about, the same rule still matches: the rule did not get
            // stricter, the question did.
            val allowed =
                s3.options(
                    "/photos/bar",
                    headers = listOf("Origin" to "example.origin", "Access-Control-Request-Method" to "GET"),
                )
            assertEquals(200, allowed.status, allowed.text)
        }
    }

    @Test
    fun `a named header and an asterisk both allow a preflight`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            fun rules(allowed: String) =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "cors",
                    body =
                        (
                            "<CORSConfiguration><CORSRule><AllowedMethod>GET</AllowedMethod>" +
                                "<AllowedOrigin>*</AllowedOrigin>$allowed</CORSRule></CORSConfiguration>"
                        ).toByteArray(),
                )

            fun ask(headers: String) =
                s3.options(
                    "/photos/bar",
                    headers =
                        listOf(
                            "Origin" to "example.origin",
                            "Access-Control-Request-Method" to "GET",
                            "Access-Control-Request-Headers" to headers,
                        ),
                )

            rules("<AllowedHeader>x-amz-meta-header2</AllowedHeader>")
            // Header names are compared case-insensitively, the way HTTP compares them.
            assertEquals(200, ask("X-Amz-Meta-Header2").status)
            // Two asked about, one allowed, and that is not enough.
            assertEquals(403, ask("x-amz-meta-header2, x-amz-meta-header3").status)

            rules("<AllowedHeader>*</AllowedHeader>")
            assertEquals(200, ask("x-amz-meta-header2, anything-at-all").status)

            rules("<AllowedHeader>x-amz-*</AllowedHeader>")
            assertEquals(200, ask("x-amz-meta-header2").status)
            assertEquals(403, ask("x-other").status)
        }
    }

    // --- the half that was missing: an ordinary response to a cross-origin request (M-226) ------

    /**
     * The rules a browser actually needs, and the ones this server had none of.
     *
     * Preflight was answered from the first day of CORS here (M14) and the **real** request was
     * not: no `Access-Control-*` on the response at all. From the outside that looks like CORS
     * working — the `OPTIONS` says yes — and then the browser refuses to hand the body to the page,
     * because the answer it finally gets never says who may read it. No test in this repository
     * could see it, for the reason that makes it worth a milestone of its own: the tests are not
     * browsers.
     *
     * The oracle is `test_cors_origin_response:6916` and `test_cors_origin_wildcard`, both of
     * which are unmarked — this is S3's behaviour rather than RGW's.
     */
    private val forOrigins =
        (
            "<CORSConfiguration>" +
                "<CORSRule><AllowedMethod>GET</AllowedMethod><AllowedOrigin>*suffix</AllowedOrigin></CORSRule>" +
                "<CORSRule><AllowedMethod>PUT</AllowedMethod><AllowedOrigin>*.put</AllowedOrigin></CORSRule>" +
                "</CORSConfiguration>"
        ).toByteArray()

    @Test
    fun `a request without an Origin gets no cors headers`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val answer = s3.send("GET", "/photos", query = "list-type=2")

        assertEquals(200, answer.status)
        assertNull(answer.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun `an origin a rule matches is told so on the answer itself`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val answer = s3.send("GET", "/photos", query = "list-type=2", headers = listOf("Origin" to "foo.suffix"))

        assertEquals(200, answer.status)
        assertEquals("foo.suffix", answer.header("Access-Control-Allow-Origin"))
        assertEquals("GET", answer.header("Access-Control-Allow-Methods"))
    }

    @Test
    fun `an origin no rule matches is answered without cors headers, not refused`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val answer = s3.send("GET", "/photos", query = "list-type=2", headers = listOf("Origin" to "foo.bar"))

        assertEquals(200, answer.status, "a request the rules do not cover is still an ordinary request")
        assertNull(answer.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun `the headers ride on a failure too`() {
        // `test_cors_origin_response` checks a 404 and a 403 for exactly this: a browser has to be
        // able to read the error, and a page that cannot see the 404 sees a network failure.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val answer = s3.send("GET", "/photos/missing", headers = listOf("Origin" to "foo.suffix"))

        assertEquals(404, answer.status)
        assertEquals("foo.suffix", answer.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun `a stated request method decides the match before the real one does`() {
        // A PUT carrying `Access-Control-Request-Method: GET` matches the GET rule — the same
        // matching function preflight uses, which is what the suite pins.
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val statedGet =
            s3.send(
                "PUT",
                "/photos/bar",
                headers = listOf("Origin" to "foo.suffix", "Access-Control-Request-Method" to "GET"),
            )
        assertEquals("foo.suffix", statedGet.header("Access-Control-Allow-Origin"))
        assertEquals("GET", statedGet.header("Access-Control-Allow-Methods"))

        val statedPut =
            s3.send(
                "PUT",
                "/photos/bar",
                headers = listOf("Origin" to "foo.suffix", "Access-Control-Request-Method" to "PUT"),
            )
        assertNull(statedPut.header("Access-Control-Allow-Origin"), "no rule allows PUT from *suffix")
    }

    @Test
    fun `and the real method when nothing states one`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val answer = s3.send("PUT", "/photos/bar", headers = listOf("Origin" to "foo.put"), body = "body".toByteArray())

        assertEquals("foo.put", answer.header("Access-Control-Allow-Origin"))
        assertEquals("PUT", answer.header("Access-Control-Allow-Methods"))
    }

    @Test
    fun `a rule that allows every origin answers with the star, not with the origin`() {
        // `test_cors_origin_wildcard`: the answer is `*`, which is what lets a browser cache it
        // for any origin. Echoing the caller back would be a different, narrower promise.
        s3.createBucket("photos")
        val anyOrigin =
            (
                "<CORSConfiguration><CORSRule><AllowedMethod>GET</AllowedMethod>" +
                    "<AllowedOrigin>*</AllowedOrigin></CORSRule></CORSConfiguration>"
            ).toByteArray()
        s3.send("PUT", "/photos", query = "cors", body = anyOrigin)

        val answer = s3.send("GET", "/photos", query = "list-type=2", headers = listOf("Origin" to "example.origin"))

        assertEquals("*", answer.header("Access-Control-Allow-Origin"))
    }

    // --- OPTIONS that is not a preflight (M-226) -----------------------------------------------

    /**
     * An `OPTIONS` with no `Access-Control-Request-Method` is not a preflight at all — it is a
     * malformed one, and `400` is what says so.
     *
     * `403` reads as "you may not ask", which sends the caller to look at credentials that were
     * never the problem. Seven cases of the suite spent a milestone classified under anonymous
     * access for exactly this reason: they send `OPTIONS` at a presigned link and wait for `400`
     * (`test_cors_origin_response:6916` pins the plain form).
     */
    @Test
    fun `an OPTIONS without a stated method is a bad request, not a refusal`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val withOrigin = s3.send("OPTIONS", "/photos", headers = listOf("Origin" to "foo.suffix"))

        assertEquals(400, withOrigin.status, withOrigin.text)
        assertNull(withOrigin.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun `and an OPTIONS with no Origin either`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        assertEquals(400, s3.send("OPTIONS", "/photos").status)
        // Even a bucket that was never told about CORS: the request is wrong before the
        // configuration is consulted.
        s3.createBucket("plain")
        assertEquals(400, s3.send("OPTIONS", "/plain").status)
    }

    @Test
    fun `an origin no rule covers is still refused, and with 403`() {
        // The other half of the pair: this **is** a preflight, and the answer to it is "no".
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val answer =
            s3.send(
                "OPTIONS",
                "/photos",
                headers = listOf("Origin" to "nobody.knows", "Access-Control-Request-Method" to "GET"),
            )

        assertEquals(403, answer.status, answer.text)
    }

    /**
     * The shape the suite actually sends, and the reason its three CORS cases do not pass as
     * shipped (found by removing their classification rule and watching what came back).
     *
     * `_cors_request_and_check` calls bare `requests.get` — **no signature at all** — against a
     * bucket made `public-read` for exactly that purpose. Every assertion in those cases therefore
     * runs through layer two, which is off in the shipped configuration (M28), and the first one
     * fails on `403` long before any `Access-Control-*` is looked at. The old rule blamed the
     * missing header, and the header was only the second reason.
     *
     * So this is the same request with the switch on, and it is the proof that the mechanism is
     * whole: the cases are `off-by-default`, not broken.
     */
    @Test
    fun `an unsigned browser request to a public bucket gets its cors headers`() {
        S3Fixture(anonymous = true).use { open ->
            open.createBucket("photos", headers = listOf("x-amz-acl" to "public-read"))
            open.send("PUT", "/photos", query = "cors", body = forOrigins)

            val plain = open.unsigned("GET", "/photos", query = "list-type=2")
            assertEquals(200, plain.status, "the premise: an unsigned reader is served at all")
            assertNull(plain.header("Access-Control-Allow-Origin"), "no Origin, no headers")

            val cross =
                open.unsigned(
                    "GET",
                    "/photos",
                    query = "list-type=2",
                    headers = listOf("Origin" to "foo.suffix"),
                )
            assertEquals(200, cross.status, cross.text)
            assertEquals("foo.suffix", cross.header("Access-Control-Allow-Origin"))
            assertEquals("GET", cross.header("Access-Control-Allow-Methods"))
        }
    }

    @Test
    fun `and the same request as shipped is refused before cors is reached`() {
        s3.createBucket("photos", headers = listOf("x-amz-acl" to "public-read"))
        s3.send("PUT", "/photos", query = "cors", body = forOrigins)

        val cross = s3.unsigned("GET", "/photos", query = "list-type=2", headers = listOf("Origin" to "foo.suffix"))

        assertEquals(403, cross.status, "layer two is off, and that is the first answer the case meets")
    }

    /**
     * A refused preflight carries no `Access-Control-*` — a decorator was adding them (found in
     * M-204).
     *
     * `test_cors_header_option:7016` asks `OPTIONS` with an `Access-Control-Request-Headers` the
     * rule does not allow, and expects a `403` with **no** `Access-Control-*` at all. The headers
     * of an ordinary answer (M-226) are added on all three exits, and they went out on this `403`
     * too: the server refused and in the same breath announced that the request was allowed. No
     * local test saw it, because every one of them checked the positive side.
     *
     * A preflight answers for its own headers, in both directions — when it allows and when it does
     * not.
     */
    @Test
    fun `a refused preflight carries no cors headers`() {
        s3.createBucket("photos")
        val exposeOnly =
            (
                "<CORSConfiguration><CORSRule><AllowedMethod>GET</AllowedMethod>" +
                    "<AllowedOrigin>*</AllowedOrigin>" +
                    "<ExposeHeader>x-amz-meta-header1</ExposeHeader></CORSRule></CORSConfiguration>"
            ).toByteArray()
        s3.send("PUT", "/photos", query = "cors", body = exposeOnly)

        val answer =
            s3.unsigned(
                "OPTIONS",
                "/photos/bar",
                headers =
                    listOf(
                        "Origin" to "example.origin",
                        "Access-Control-Request-Headers" to "x-amz-meta-header2",
                        "Access-Control-Request-Method" to "GET",
                    ),
            )

        assertEquals(403, answer.status, answer.text)
        assertNull(answer.header("Access-Control-Allow-Origin"), "a refusal that announces permission")
    }
}
