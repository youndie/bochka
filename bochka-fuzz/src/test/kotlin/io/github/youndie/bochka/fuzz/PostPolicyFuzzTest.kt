package io.github.youndie.bochka.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.youndie.bochka.s3.PostPolicy
import java.util.Base64

/**
 * The signed policy of a browser upload, fed documents nobody wrote on purpose (M-257).
 *
 * The document arrives base64-encoded in a form field and is read by a JSON parser written here by
 * hand — `valueOf`, `conditionsOf`, `unescape`, all index arithmetic over a string a stranger
 * chose. M37 closed 22 surviving mutants in it with inputs I invented one at a time, which is the
 * argument for pointing something less imaginative at it.
 *
 * It decides what an anonymous upload is allowed to do, so a document that parses into the wrong
 * conditions is worth more to an attacker than one that fails to parse.
 */
class PostPolicyFuzzTest {
    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun document(data: ByteArray) {
        // Encoded here rather than fuzzed as text, so that the fuzzer spends its inputs on the JSON
        // parser instead of on producing valid base64 by accident. Fuzzing `decode` with raw bytes
        // measures the base64 decoder, which is the JDK's and not ours — the `encoded` target below
        // covers that side deliberately and separately.
        decoded(Base64.getEncoder().encodeToString(data))
    }

    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun encoded(data: ByteArray) {
        // The other half: what arrives is a field value, and whether it is base64 at all is the
        // client's claim rather than a fact. This is the path a malformed field takes.
        decoded(String(data, Charsets.ISO_8859_1))
    }

    private fun decoded(encoded: String) {
        try {
            PostPolicy.decode(encoded)
        } catch (refused: PostPolicy.Refused) {
            // The one refusal this path may produce, and it carries the S3 error the client will be
            // answered with. Anything else escapes an unauthenticated path.
            check(refused.message.isNotEmpty()) { "a refusal has to say what it refused" }
        }

        // Nothing is asserted about the conditions that come back, and that is on purpose. The
        // first draft here required `content-length-range` bounds to be ordered — and `pairOf`
        // takes them as they are written, with no ordering check. That would have been a finding
        // about my expectation rather than about the code, which is the second time in this
        // milestone: a target's assertion has to be the weakest true one. A reversed range fails
        // closed anyway — nothing has a size inside it — so there is nothing here to refuse.
    }
}
