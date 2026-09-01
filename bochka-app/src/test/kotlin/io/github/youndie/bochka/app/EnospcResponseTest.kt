package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.ObjectStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * A disk that fills up is answered with a document, not with a dropped connection (M-268).
 *
 * The lesson is M10's and it has been paid for twice: an exception that escapes the request loop
 * leaves the client holding a closed socket with no bytes in it, every SDK reads that as a network
 * failure, and a network failure is what SDKs retry. A full disk that presents itself that way
 * becomes a storm of retries against a server that cannot succeed — and the operator sees load,
 * not a cause. The same class as `500` for an unreadable URI (M-258): the status decides whether
 * the client tries again.
 *
 * **Asked through the wire and in the server's own terms**, by writing objects until the volume
 * ends rather than by filling it from the side. That is what a client actually does to a store,
 * and it makes the answer to "what does the caller see" the literal answer the caller saw. A
 * dropped connection surfaces here as an `IOException` from the JDK client, which is the failure
 * this test exists to tell apart from a `500`.
 *
 * Runs only under `ci/enospc.sh`; see `EnospcStandTest` for why the skip is not a silent gate.
 */
class EnospcResponseTest {
    private val prepared: Path? = System.getenv("BOCHKA_ENOSPC_DIR")?.let(Path::of)

    @Test
    fun `a write that runs out of disk is answered with an error document`() {
        val directory = prepared ?: return
        val home = Files.createDirectories(directory.resolve("app-store"))
        // FSYNC, because the question is what happens when the disk refuses. With the barrier off,
        // a write the page cache accepted is indistinguishable from a write that reached the disk.
        S3Fixture(root = home, durability = ObjectStore.Durability.FSYNC).use { s3 ->
            assertEquals(200, s3.createBucket("photos").status)

            val block = ByteArray(1 shl 20) { 'x'.code.toByte() }
            var stored = 0
            var refusal: S3Fixture.Answer? = null
            for (n in 1..512) {
                val answer =
                    try {
                        s3.send("PUT", "/photos/big-$n", body = block)
                    } catch (e: IOException) {
                        fail(
                            "after $stored MiB the client got no answer at all but ${e::class.simpleName}: " +
                                "a dropped connection is what every SDK retries, which is the whole of this test",
                        )
                    }
                if (answer.status == 200) {
                    stored++
                    continue
                }
                refusal = answer
                break
            }

            val answer =
                assertNotNull(
                    refusal,
                    "$stored MiB went in without a refusal: this stand is not constraining anything",
                )
            assertEquals(
                500,
                answer.status,
                "a full disk is this server's problem to report, and the body said: ${answer.text}",
            )
            assertContains(
                answer.text,
                "<Error>",
                message = "the status arrived without a document to explain it",
            )
            assertContains(answer.text, "InternalError")

            // And the store is still a store. A server that answered the failure and then stopped
            // serving would pass every assertion above — the answer is only worth having if the
            // process it came from is still there.
            assertEquals(200, s3.send("GET", "/photos/big-1").status, "the objects written before the wall are gone")
        }

        // After `close`, which takes the store's tree with it: the marker needs somewhere to go,
        // and until then there is nothing left on the volume.
        Files.createDirectories(directory.resolve("exercised"))
        Files.writeString(directory.resolve("exercised").resolve("response"), "ENOSPC answered over HTTP\n")
    }
}
