package io.github.youndie.bochka.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.youndie.bochka.http.HttpRequestParser

/**
 * The head of an HTTP request, fed bytes nobody wrote on purpose.
 *
 * This is the first code a stranger reaches. §1.2.2 requires a refusal to be possible from the
 * headers alone, so the head is parsed **before** the signature is checked — which means every
 * byte here arrives unauthenticated, and the question is not whether the parser agrees with the
 * specification but whether any input at all can make it do something other than answer.
 *
 * **The assertion is a property, not a specification line**, and that is a deliberate departure
 * from the rule that a test cites `docs/spec/`. There is nothing to cite: the claim is not "the
 * server answers this way" but "on no input does the server do anything except parse or refuse".
 * A refusal is `Malformed`, which carries the status the caller will send. Anything else reaching
 * this frame is the finding — and it is the shape of a defect this repository has already had,
 * where an exception escaped the request loop and the client got a closed socket with no bytes at
 * all, which the foreign suite recorded as a network error.
 */
class HttpHeadFuzzTest {
    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun head(data: ByteArray) {
        val parser = HttpRequestParser()
        try {
            // Fed in two slices rather than one, because that is how bytes arrive on a selector:
            // the parser is resumable, and a split is exactly where an index carried across calls
            // can be wrong. One call would never take that path.
            val cut = if (data.isEmpty()) 0 else data.size / 2
            parser.feed(data, 0, cut)
            parser.feed(data, cut, data.size - cut)
        } catch (refused: HttpRequestParser.Malformed) {
            // A refusal carries the status the caller will answer with, so the only thing to hold
            // it to is that it **is** a refusal. The range was 400..499 for about a minute, and the
            // first thing the fuzzer found was that: `505` for a version this server does not speak
            // is deliberate and correct (RFC 9110 §15.6.6). The property was mine, the code was
            // right, and it took 24 runs — which is the argument for this whole milestone in one
            // line, and also the reason a fuzz target's assertion has to be the weakest true one.
            check(refused.status in 400..599) {
                "a refusal has to name an error status, and this one says ${refused.status}"
            }
        }
    }
}
