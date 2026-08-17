package io.github.youndie.bochka.s3.sigv4

import io.github.youndie.bochka.s3.UriCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3 signs its own way, and AWS publishes no vectors for it. These come from botocore — the code
 * `aws-cli` signs with — through `docs/spec/s3-signing-vectors/generate.py`.
 *
 * Not official, and worth exactly as much as the reference implementation is: the value is that
 * botocore is an **independent** implementation rather than a restatement of what this code does.
 *
 * Each case is a set of parameters rather than a raw request, so the test builds the wire form the
 * way a client would — with [UriCodec.encodePath], which is itself checked against the botocore
 * key-encoding table. What is being tested is the canonicalisation, and the one thing that makes S3
 * different: the path is signed exactly as it travels
 * (`docs/spec/reference/botocore-auth.py:538`).
 */
class S3ModeVectorsTest {
    private val vectors: Path =
        Path
            .of(
                System.getProperty("bochka.specDir")
                    ?: error("bochka.specDir is not set; the build has to point tests at docs/spec"),
            ).resolve("s3-signing-vectors/header")

    private fun cases(): List<Path> =
        Files
            .list(vectors)
            .asSequence()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .toList()

    @Test
    fun `every s3-mode vector rebuilds its canonical request, string to sign and signature`() {
        val cases = cases()
        assertTrue(cases.size >= 13, "expected at least 13 cases, found ${cases.map { it.name }}")

        val failures = ArrayList<String>()
        for (case in cases) {
            val input = readInput(case)
            val expectedCreq = Files.readString(case.resolve("creq"))
            val expectedSts = Files.readString(case.resolve("sts"))
            val authorization = Authorization.parse(Files.readString(case.resolve("authz")).trim())
            val payloadHash = Files.readString(case.resolve("sha256")).trim()

            val host =
                when (input.getValue("style")) {
                    "virtual" -> "${input.getValue("bucket")}.s3.us-east-1.amazonaws.com"
                    else -> "s3.us-east-1.amazonaws.com"
                }
            val encodedKey = UriCodec.encodePath(input.getValue("key").toByteArray())
            val path =
                when (input.getValue("style")) {
                    "virtual" -> "/$encodedKey"
                    else -> "/${input.getValue("bucket")}/$encodedKey"
                }

            val headers = ArrayList<Pair<String, String>>()
            headers += "Host" to host
            headers += "X-Amz-Content-Sha256" to payloadHash
            headers += "X-Amz-Date" to TIMESTAMP
            val token = input.getValue("token")
            if (token.isNotEmpty()) headers += "X-Amz-Security-Token" to token

            val canonical =
                CanonicalRequest.build(
                    request =
                        CanonicalRequest.Request(
                            method = input.getValue("method"),
                            path = path,
                            query = input.getValue("query"),
                            headers = headers,
                        ),
                    signedHeaders = authorization.signedHeaders,
                    payloadHash = payloadHash,
                    mode = CanonicalRequest.PathMode.VERBATIM,
                )
            if (canonical != expectedCreq) {
                failures += "${case.name}: canonical request\n--- expected\n$expectedCreq\n--- actual\n$canonical"
                continue
            }

            val stringToSign = Sigv4.stringToSign(TIMESTAMP, authorization.scope, canonical)
            if (stringToSign != expectedSts) {
                failures += "${case.name}: string to sign\n--- expected\n$expectedSts\n--- actual\n$stringToSign"
                continue
            }

            val signature = Sigv4.signature(Sigv4.signingKey(SECRET, "20150830", "us-east-1", "s3"), stringToSign)
            if (!Sigv4.signaturesMatch(authorization.signature, signature)) {
                failures += "${case.name}: signature ${authorization.signature} != $signature"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} s3-mode vectors failed:\n\n" + failures.joinToString("\n\n"),
        )
    }

    @Test
    fun `the generic path rule cannot be applied to a wire path at all`() {
        // A green vector suite proves nothing by itself — it has to be shown to be capable of
        // failing, so the S3 cases get run through the *other* path mode.
        //
        // The result is stronger than expected, and it is worth writing down. Normalisation breaks
        // these keys in two distinct ways:
        //
        //  * structure is lost: `a/./b` and `a//b` collapse to `a/b`, which is a different object;
        //  * everything else is **double-encoded**: `hello%20world` becomes `hello%2520world`,
        //    because the generic rule percent-encodes a path that is already percent-encoded.
        //
        // The second one is the reason the official vectors carry raw, unencoded paths: the generic
        // mode is written for a client that has not encoded yet. On a server, where the path always
        // arrives encoded, that mode is not merely different — it is inapplicable. Which is exactly
        // why the mode exists as an explicit choice rather than as a default.
        val collapsed = ArrayList<String>()
        val doubleEncoded = ArrayList<String>()

        for (case in cases()) {
            val input = readInput(case)
            if (input.getValue("style") != "virtual") continue
            val key = input.getValue("key")

            val verbatim = "/" + UriCodec.encodePath(key.toByteArray())
            val normalised = CanonicalRequest.canonicalUri(verbatim, CanonicalRequest.PathMode.NORMALISED)
            if (normalised == verbatim) continue

            if (normalised.contains("%25")) doubleEncoded += case.name else collapsed += case.name
        }

        assertEquals(listOf("key-with-dot-segment", "key-with-repeated-slash"), collapsed.sorted())
        assertEquals(
            listOf("key-outside-basic-plane", "key-with-non-ascii", "key-with-space"),
            doubleEncoded.sorted(),
        )
    }

    private fun readInput(case: Path): Map<String, String> =
        Files
            .readAllLines(case.resolve("input"))
            .filter { it.isNotBlank() }
            .associate { line ->
                val eq = line.indexOf('=')
                line.substring(0, eq) to line.substring(eq + 1)
            }

    private companion object {
        const val SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
        const val TIMESTAMP = "20150830T123600Z"
    }
}
