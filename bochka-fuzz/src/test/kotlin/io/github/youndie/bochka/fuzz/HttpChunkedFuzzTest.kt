package io.github.youndie.bochka.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.youndie.bochka.http.HttpChunkedDecoder

/**
 * The framing of a chunked body, fed bytes nobody wrote on purpose (M-256).
 *
 * The head decides whether a request is refused; this decides where the request *ends*. Get that
 * wrong and the next request begins in the middle of this one's body — which is what the head
 * parser's smuggling refusals exist to prevent, and they are worth nothing if the decoder running
 * after them disagrees about the boundary.
 *
 * **The limits are small on purpose, and not to be quick.** libFuzzer does not generate inputs over
 * 4096 bytes, so against the shipped 8 KiB line limit and 64 MiB chunk limit the branches enforcing
 * them cannot be reached at all: a target using the defaults would cover everything except the
 * checks it exists for. The arithmetic is the subject, not the constant, so the constants move to
 * where an input can stand on them.
 */
class HttpChunkedFuzzTest {
    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun body(data: ByteArray) {
        var delivered = 0L
        val decoder =
            HttpChunkedDecoder(maxChunkBytes = 64, maxLineBytes = 24) { _, _, length ->
                check(length >= 0) { "a sink cannot be handed a negative length" }
                delivered += length
            }

        try {
            // Three slices, because the decoder is resumable and a boundary falling between calls
            // is exactly where an index carried across them can be wrong. One call never takes that
            // path, and there are five states here to carry across it.
            val cut = if (data.isEmpty()) 0 else data.size / 3
            val first = decoder.feed(data, 0, cut)
            check(first in 0..cut) { "consumed $first of a $cut-byte slice" }

            val rest = data.size - cut
            val second = decoder.feed(data, cut, rest)
            check(second in 0..rest) { "consumed $second of a $rest-byte slice" }

            // A body cannot yield more bytes than were fed to it. The decoder copies out of the
            // buffer it was handed, so anything else means a length taken from the input rather
            // than from what arrived — which is how a declared size turns into an allocation.
            check(delivered <= data.size) { "$delivered bytes delivered from ${data.size} bytes of input" }

            if (decoder.isComplete) {
                check(decoder.feed(data, 0, data.size) == 0) {
                    "a finished body went on consuming, so the next request would start inside it"
                }
            }
        } catch (refused: HttpChunkedDecoder.Malformed) {
            // The one refusal this path may produce. Anything else reaching this frame escapes the
            // request loop, and the client gets a closed socket instead of an answer — a shape this
            // repository has already shipped once, recorded by the foreign suite as a network error.
            check(refused.message != null) { "a refusal has to say what it refused" }
        }
    }
}
