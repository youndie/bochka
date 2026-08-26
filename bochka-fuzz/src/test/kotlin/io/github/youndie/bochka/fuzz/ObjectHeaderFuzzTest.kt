package io.github.youndie.bochka.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.youndie.bochka.s3.ByteRanges
import io.github.youndie.bochka.s3.ObjectHeaders
import io.github.youndie.bochka.s3.UriCodec

/**
 * The headers a client writes by hand and the server reads with arithmetic (M-259).
 *
 * These are the last of the unauthenticated readers: `Range` decides which bytes of an object leave
 * the process, and `x-amz-meta-*`, `x-amz-tagging` and the checksum headers decide what is stored
 * beside it. A client composes them character by character — no SDK builds a `Range` for you — so
 * what arrives is whatever somebody typed.
 *
 * `Range` is the more interesting half, because its answer is an **offset and a length into a file**
 * rather than a value: `resolve` is total, and a wrong pair there is not an error but bytes of the
 * wrong object leaving the machine. So the target does not only call it — it checks the window it
 * hands back lies inside the size it was given.
 */
class ObjectHeaderFuzzTest {
    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun range(data: ByteArray) {
        if (data.size < 2) return

        // The size comes out of the input too. A range is only meaningful against an object length,
        // and the boundaries worth standing on — last byte, one past the end, an empty object — are
        // relations between the two rather than constants either one could carry alone.
        val size = (data[0].toLong() and 0xFF) * (data[1].toLong() and 0xFF)
        val header = String(data, 2, data.size - 2, Charsets.ISO_8859_1)

        when (val resolved = ByteRanges.resolve(header, size)) {
            is ByteRanges.Resolved.Satisfiable -> {
                check(resolved.start >= 0) { "a range starting at ${resolved.start}" }
                check(resolved.endInclusive >= resolved.start) {
                    "a range ending at ${resolved.endInclusive} that starts at ${resolved.start}"
                }
                check(resolved.endInclusive < size) {
                    "range ${resolved.start}..${resolved.endInclusive} of an object of $size bytes"
                }
                check(resolved.length > 0) { "a satisfiable range of ${resolved.length} bytes" }
            }

            // Both of the other answers are whole-object answers and carry no arithmetic to check.
            ByteRanges.Resolved.Unsatisfiable, ByteRanges.Resolved.Whole -> {
                Unit
            }
        }
    }

    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun headers(data: ByteArray) {
        // Split into headers the way the wire does, so that a name with no colon, a repeated name
        // and an empty value are all reachable rather than excluded by the target's own tidiness.
        val headers =
            String(data, Charsets.ISO_8859_1).split('\n').map { line ->
                val colon = line.indexOf(':')
                if (colon < 0) line to "" else line.substring(0, colon) to line.substring(colon + 1)
            }

        try {
            ObjectHeaders.read(headers)
        } catch (refused: ObjectHeaders.Malformed) {
            check(refused.message != null) { "a refusal has to say what it refused" }
        } catch (refused: UriCodec.Malformed) {
            // `x-amz-tagging` is percent-encoded, so the URI codec is reached from here as well —
            // which is the ninth call site that made M-258 answer this refusal in one place rather
            // than beside the parser that happened to find it.
            check(refused.message.isNotEmpty()) { "a refusal has to say what it refused" }
        }
    }
}
