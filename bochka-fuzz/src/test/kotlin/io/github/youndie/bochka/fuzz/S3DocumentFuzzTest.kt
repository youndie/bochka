package io.github.youndie.bochka.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.youndie.bochka.s3.xml.S3Requests
import io.github.youndie.bochka.s3.xml.XmlReader

/**
 * The XML documents a client sends in a request body, fed bytes nobody wrote on purpose (M-258).
 *
 * Ten parsers over one hand-written reader. A signature proves who sent a body, never that the body
 * is well formed, and the anonymous path (M28) reaches several of these with no signature at all —
 * so "the request was authenticated" says nothing about the bytes below.
 *
 * **All ten take one input, rather than one target each.** libFuzzer owns the process per method,
 * so every extra target costs a whole run of the clock; ten targets at a minute each is ten minutes
 * to ask ten questions of the same reader. Fanning one input across them spends the whole minute on
 * `XmlReader`, which is the code they share and the code the arithmetic lives in. The cost is that
 * a finding names a fan-out rather than a target — and the input that produced it is written out
 * either way, which is what actually reproduces it.
 */
class S3DocumentFuzzTest {
    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun document(data: ByteArray) {
        for (parse in PARSERS) {
            try {
                parse(data)
            } catch (refused: XmlReader.MalformedXmlException) {
                check(refused.message != null) { "a refusal has to say what it refused" }
            } catch (refused: S3Requests.InvalidArgument) {
                // The document parsed and says something that cannot be carried out — `400
                // InvalidArgument`. A refusal, and a different one from "this is not XML".
                check(refused.message.isNotEmpty()) { "an InvalidArgument with nothing to say" }
            } catch (refused: S3Requests.InvalidRetentionPeriod) {
                check(refused.message.isNotEmpty()) { "an InvalidRetentionPeriod with nothing to say" }
            }
        }
    }

    private companion object {
        /**
         * Every parser that takes a request body. Listed rather than reflected, because a list that
         * is written down can be compared against the source by a person, and a reflected one
         * silently covers whatever happens to be there — including nothing.
         */
        val PARSERS: List<(ByteArray) -> Any?> =
            listOf(
                { S3Requests.parseTagging(it) },
                { S3Requests.parseVersioning(it) },
                { S3Requests.parseObjectLock(it) },
                { S3Requests.parseRetention(it) },
                { S3Requests.parseLegalHold(it) },
                { S3Requests.parseLifecycle(it) },
                { S3Requests.parseCors(it) },
                { S3Requests.parseDelete(it) },
                { S3Requests.parseCompleteMultipartUpload(it) },
            )
    }
}
