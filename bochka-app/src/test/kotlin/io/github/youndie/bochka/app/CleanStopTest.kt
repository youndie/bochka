package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.http.HttpServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * What a `SIGTERM` leaves behind, and what it used to leave (M-292).
 *
 * The shutdown hook closed the server and **not the store**. Nothing was lost by that — records
 * reach the channel as they are written — but the directory stayed claimed until the process
 * exited, so "the server has stopped" and "the directory is free" were different moments with
 * nothing saying which one had happened.
 *
 * The observable is the claim: a store holds a lock on its directory, and a second one opening the
 * same directory is refused by name. After a clean stop that refusal must not happen — which is
 * how a test can tell a stop that closed everything from one that closed half.
 */
class CleanStopTest {
    private val home: Path = Files.createTempDirectory("bochka-clean-stop")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() = home.deleteRecursively()

    @Test
    fun `a clean stop releases the directory, not only the socket`() {
        val store = ObjectStore(home)
        val handler =
            S3Handler(
                store = store,
                verifier =
                    io.github.youndie.bochka.s3.sigv4
                        .SignatureVerifier(
                            io.github.youndie.bochka.s3.sigv4
                                .Credentials(mapOf(S3Fixture.ACCESS_KEY to S3Fixture.SECRET)),
                        ),
                router =
                    io.github.youndie.bochka.s3
                        .S3Router(),
            )
        val server = HttpServer(handler, port = 0)

        Main.stopCleanly(server, store)

        // If the store were still open this throws `DirectoryInUse`, which is exactly what the
        // hook used to leave behind: a stopped server and a directory nobody could take.
        ObjectStore(home).use { reopened ->
            reopened.createBucket("after-the-stop")
        }
    }
}
