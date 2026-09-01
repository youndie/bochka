package io.github.youndie.bochka.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * That the stand this milestone is built on actually produces the condition it claims (M-264).
 *
 * Everything M40 asks afterwards — a short write on an object file, on the index journal, in the
 * middle of a compaction — is worth nothing if the volume underneath does not really end. So this
 * asks the one question first and asks it of the JVM rather than of `df`: a write into the prepared
 * directory has to come back as `ENOSPC`, spelled the way the JDK spells it, and not as a permission
 * error, a quota error or a silent short count.
 *
 * **Skipped when the directory is not there, and that is not a silent gate.** `ci/enospc.sh` is what
 * prepares it, that harness refuses a run in which the tests never executed, and CI calls the
 * harness — so the skip is covered by something that goes red. A test that needs `mount` cannot run
 * inside an ordinary `./gradlew check`, and pretending otherwise would mean either running the
 * whole gate as root or checking a filesystem nobody constrained.
 */
class EnospcStandTest {
    private val prepared: Path? = System.getenv("BOCHKA_ENOSPC_DIR")?.let(Path::of)

    @Test
    fun `the prepared volume ends, and the JVM is told so by name`() {
        val directory = prepared ?: return

        assertTrue(Files.isWritable(directory), "the stand handed over $directory and it is not writable")

        val target = directory.resolve("fill")
        val block = ByteArray(1 shl 20)
        val failure =
            try {
                Files.newOutputStream(target).use { out ->
                    // Bounded rather than endless: a volume that does not end is the finding, and a
                    // test that discovers it by running until something else kills it reports the
                    // something else. The stand is single-digit mebibytes, so this is generous.
                    repeat(256) {
                        out.write(block)
                        out.flush()
                    }
                }
                fail("wrote 256 MiB into $directory without running out of space: this volume does not end")
            } catch (e: IOException) {
                e
            }

        // Matched on the message because that is the only place the errno survives: the JDK maps
        // ENOSPC onto a plain IOException, so the type says nothing and a check on the type alone
        // would pass for a permission error just as happily.
        assertTrue(
            failure.message?.contains("No space left on device") == true,
            "the volume ended with something other than ENOSPC: ${failure.message}",
        )

        Files.deleteIfExists(target)

        // The marker the harness looks for, and it exists because a green run could not otherwise
        // be told from a skipped one. Filling three mebibytes takes milliseconds, so "the test ran
        // and hit the wall" and "the test returned on the first line because the variable never
        // reached this JVM" produce the same duration, the same `tests=1` and the same exit code.
        // The stand refuses a run without this file.
        Files.writeString(directory.resolve("exercised"), "the volume ended by ENOSPC\n")
    }
}
