package io.github.youndie.bochka.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import io.github.youndie.bochka.s3.PostForm

/**
 * The multipart body of a browser upload, fed bytes nobody wrote on purpose (M-257).
 *
 * The likeliest place in this milestone to find something, and the reason is in M37's own summary:
 * the edges here were closed by inputs **I** invented, one per surviving mutant. A form arrives
 * from a browser the server does not control, over a path that anonymous access can reach, and it
 * is parsed by hand — boundary search, header slicing, and index arithmetic across parts.
 *
 * The two indices are the subject. `fileOffset` and `fileLength` are handed back as a window into
 * the caller's own byte array, so a wrong pair is not a parse error — it is a read of whatever else
 * is in that buffer, which is the strongest reason to point a fuzzer here rather than at something
 * that merely returns a wrong string.
 */
class PostFormFuzzTest {
    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun form(data: ByteArray) {
        // The boundary comes off a header the client also writes, so it is fuzzed too rather than
        // fixed: a body is only interesting relative to the delimiter it is read against, and a
        // delimiter longer than the body is exactly the case that gets written by hand as an
        // afterthought.
        val split = if (data.isEmpty()) 0 else data.size / 8
        val boundary = String(data.copyOfRange(0, split), Charsets.ISO_8859_1)
        if (boundary.isEmpty()) return

        val parsed =
            try {
                PostForm.parse(data, boundary)
            } catch (refused: PostForm.Malformed) {
                // The one refusal this path may produce. A form reaches this parser before the
                // policy is checked, so anything else escaping here escapes an unauthenticated path.
                check(refused.message.isNotEmpty()) { "a refusal has to say what it refused" }
                return
            }

        // A window into the caller's array. Both ends have to be inside it, and the length cannot be
        // negative — a negative length reversed into a copy is a read backwards out of the buffer.
        if (parsed.fileOffset >= 0) {
            check(parsed.fileLength >= 0) { "a file of ${parsed.fileLength} bytes" }
            check(parsed.fileOffset <= data.size) { "file starts at ${parsed.fileOffset} of ${data.size}" }
            check(parsed.fileOffset + parsed.fileLength <= data.size) {
                "file window ${parsed.fileOffset}..${parsed.fileOffset + parsed.fileLength} " +
                    "reaches past ${data.size} bytes"
            }
            // Taken, because a window that is only checked is a window nobody proved readable.
            data.copyOfRange(parsed.fileOffset, parsed.fileOffset + parsed.fileLength)
        } else {
            check(parsed.fileLength == 0) { "no file, and yet ${parsed.fileLength} bytes of one" }
        }
    }

    @FuzzTest(maxDuration = FUZZ_DURATION)
    fun boundary(data: ByteArray) {
        // `boundaryOf` reads a header value and decides whether there is a form at all. It answers
        // `null` rather than refusing, so the property is that it never throws: a header is not a
        // thing a server may crash on.
        PostForm.boundaryOf(String(data, Charsets.ISO_8859_1))
    }
}
