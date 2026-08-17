package io.github.youndie.bochka.s3.sigv4

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 34 official AWS vectors, run in the direction a server runs them.
 *
 * Source: `docs/spec/aws-sig-v4-test-suite/`, `NOTICE` dated 2019. Fixed inputs for every case:
 * key `AKIDEXAMPLE`, secret `wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY`, `20150830T123600Z`,
 * `us-east-1`, service `service`.
 *
 * A client checks these by producing an `Authorization` header. A server has to do the opposite:
 * take the header the client sent, believe **its** `SignedHeaders` list, rebuild the canonical
 * request from the wire, and see whether the signature comes out the same. So the list of signed
 * headers here comes out of `.authz` rather than out of the request — using the request would test
 * a client, and quietly, because on these cases both give the same answer.
 *
 * Run in generic mode: these describe SigV4 as such, not S3. The eight `normalize-path/` cases are
 * exactly where the two differ, and S3 mode is pinned separately by `S3ModeVectorsTest`.
 */
class AwsVectorSuiteTest {
    private val specDir: Path =
        Path.of(
            System.getProperty("bochka.specDir")
                ?: error("bochka.specDir is not set; the build has to point tests at docs/spec"),
        )

    private val suite: Path get() = specDir.resolve("aws-sig-v4-test-suite")

    private data class Case(
        val name: String,
        val dir: Path,
    ) {
        fun read(extension: String): String = Files.readString(dir.resolve("$name.$extension"))

        /**
         * The wire form, read byte for byte. ISO-8859-1 and not UTF-8 on purpose: a request line
         * may carry raw non-ASCII bytes — `get-vanilla-utf8-query` does — and reading them as text
         * would decide their meaning before the canonicaliser gets to.
         */
        fun wire(): String = String(Files.readAllBytes(dir.resolve("$name.sreq")), Charsets.ISO_8859_1)
    }

    private fun cases(): List<Case> =
        Files
            .walk(suite)
            .asSequence()
            .filter { it.isDirectory() }
            .mapNotNull { dir ->
                val name = dir.name
                if (Files.exists(dir.resolve("$name.req"))) Case(name, dir) else null
            }.sortedBy { it.name }
            .toList()

    @Test
    fun `the suite is all there`() {
        // 34 cases, and a count that drops silently is the failure mode this guards: a vector file
        // that stops being found reads exactly like a vector that passes.
        assertEquals(34, cases().size, "expected 34 cases, found ${cases().map { it.name }}")
    }

    @Test
    fun `every vector rebuilds its canonical request, string to sign and signature`() {
        val failures = ArrayList<String>()

        for (case in cases()) {
            val request = RawHttpRequest.parse(case.wire())
            val authorization = Authorization.parse(case.read("authz").trim())

            val canonical =
                CanonicalRequest.build(
                    request =
                        CanonicalRequest.Request(
                            method = request.method,
                            path = request.path,
                            query = request.query,
                            headers = request.headers,
                        ),
                    signedHeaders = authorization.signedHeaders,
                    payloadHash = Sigv4.sha256Hex(request.body),
                    mode = CanonicalRequest.PathMode.NORMALISED,
                )
            if (canonical != case.read("creq")) {
                failures +=
                    "${case.name}: canonical request\n" +
                    "--- expected\n${case.read("creq")}\n--- actual\n$canonical"
                continue
            }

            val stringToSign = Sigv4.stringToSign(TIMESTAMP, authorization.scope, canonical)
            if (stringToSign != case.read("sts")) {
                failures +=
                    "${case.name}: string to sign\n" +
                    "--- expected\n${case.read("sts")}\n--- actual\n$stringToSign"
                continue
            }

            val signature = Sigv4.signature(signingKey(), stringToSign)
            if (!Sigv4.signaturesMatch(authorization.signature, signature)) {
                failures += "${case.name}: signature ${authorization.signature} != $signature"
            }
        }

        assertTrue(failures.isEmpty(), "${failures.size} of 34 vectors failed:\n\n" + failures.joinToString("\n\n"))
    }

    @Test
    fun `a request altered after signing no longer verifies`() {
        // The half of the job a vector cannot describe. A suite of "does it produce the same
        // string" says nothing about whether anything is compared, and a verifier that computes a
        // signature and forgets to check it passes every vector above.
        val case = cases().first { it.name == "get-vanilla-query" }
        val request = RawHttpRequest.parse(case.wire())
        val authorization = Authorization.parse(case.read("authz").trim())

        fun signatureOf(tampered: CanonicalRequest.Request): String {
            val canonical =
                CanonicalRequest.build(
                    tampered,
                    authorization.signedHeaders,
                    Sigv4.sha256Hex(request.body),
                    CanonicalRequest.PathMode.NORMALISED,
                )
            return Sigv4.signature(signingKey(), Sigv4.stringToSign(TIMESTAMP, authorization.scope, canonical))
        }

        val original =
            CanonicalRequest.Request(request.method, request.path, request.query, request.headers)
        assertTrue(Sigv4.signaturesMatch(authorization.signature, signatureOf(original)))

        assertFalse(
            Sigv4.signaturesMatch(authorization.signature, signatureOf(original.copy(method = "POST"))),
            "changing the method must change the signature",
        )
        assertFalse(
            Sigv4.signaturesMatch(authorization.signature, signatureOf(original.copy(path = "/other"))),
            "changing the path must change the signature",
        )
        assertFalse(
            Sigv4.signaturesMatch(authorization.signature, signatureOf(original.copy(query = "Param1=value2"))),
            "changing a query value must change the signature",
        )
        assertFalse(
            Sigv4.signaturesMatch(
                authorization.signature,
                signatureOf(original.copy(headers = listOf("Host" to "elsewhere.amazonaws.com"))),
            ),
            "changing a signed header must change the signature",
        )
    }

    @Test
    fun `the normalize-path cases are the ones s3 mode has to answer differently`() {
        // Not a formality: it is the single difference between the two modes, and the reason the
        // core has a mode at all (`docs/spec/reference/botocore-auth.py:538`).
        val case = cases().first { it.name == "get-slash-dot-slash" }
        val request = RawHttpRequest.parse(case.wire())

        assertEquals("/", CanonicalRequest.canonicalUri(request.path, CanonicalRequest.PathMode.NORMALISED))
        assertEquals("/./", CanonicalRequest.canonicalUri(request.path, CanonicalRequest.PathMode.VERBATIM))
    }

    private fun signingKey() = Sigv4.signingKey(SECRET, "20150830", "us-east-1", "service")

    private companion object {
        const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
        const val TIMESTAMP = "20150830T123600Z"
    }
}
