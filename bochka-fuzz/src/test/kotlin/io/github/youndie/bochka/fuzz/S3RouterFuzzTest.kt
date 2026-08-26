package io.github.youndie.bochka.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.UriCodec

/**
 * The routing table, fed request lines nobody wrote on purpose (M-258).
 *
 * `route` refuses exactly one way — `UriCodec.Malformed`, for a URI that cannot be read — and
 * otherwise answers. That is the property, and it runs on every request before anything is
 * verified, so an untyped exception here is not a wrong answer but a wrong **status**: the generic
 * failure handler cannot tell a client's bad byte from this server's bug.
 *
 * The first version of this target asserted that `route` throws nothing at all, which the source
 * supported — there is no `throw` anywhere in it. It found in 54 inputs that `parseQuery` reaches
 * `UriCodec`, which threw a bare `IllegalArgumentException` and was answered `500 InternalError`,
 * a status both `aws-cli` and `boto3` retry for a request that can never succeed. The refusal is
 * typed now and answered `400 InvalidURI` (`MalformedUriTest`), so the property here is one step
 * weaker and true, rather than one step stronger and useful once.
 *
 * The router is also where M37 left twelve survivors it could not explain: they sit at lines past
 * the end of the file, inside inlined lambdas where SMAP invents the number, and both obvious
 * guesses were checked by hand and caught by the suite. A fuzzer will not name them either — but it
 * reaches the cascade with inputs nobody thought to write, which is the one thing not yet tried.
 */
class S3RouterFuzzTest {
    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun route(data: ByteArray) {
        // Five fields cut out of one input at a separator the fuzzer can move as well as fill. A
        // space, because it is the one byte that already separates a request line, so a corpus entry
        // reads as the thing it stands for rather than as an encoding of it.
        val parts = String(data, Charsets.ISO_8859_1).split(' ', limit = 5)
        if (parts.size < 5) return

        // Virtual-host routing is on, because `bucketFromHost` is one of the parsers under test and
        // with an empty suffix list it returns null on its first line every time.
        val router = S3Router(virtualHostSuffixes = listOf(".s3.example", ".example"))

        try {
            router.route(
                method = parts[0],
                host = parts[1],
                path = parts[2],
                query = parts[3],
                copySource = parts[4].takeIf { it.isNotEmpty() },
            )
        } catch (refused: UriCodec.Malformed) {
            // The one refusal the routing table may produce, and the layer above answers it `400`.
            // Anything else is the finding.
            check(refused.message.isNotEmpty()) { "a refusal has to say what it refused" }
        }
    }
}
