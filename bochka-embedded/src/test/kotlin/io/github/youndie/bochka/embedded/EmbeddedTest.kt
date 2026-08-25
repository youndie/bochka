package io.github.youndie.bochka.embedded

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What this module is taken into somebody else's project for in the first place (M15).
 *
 * The milestone does not move the `ceph/s3-tests` count by a single case — there are no tests for
 * convenience there — so its acceptance lives here: a scenario that works rather than a number.
 *
 * The requests here are unsigned, and what is checked over HTTP is exactly what gets as far as the
 * signature: a primed refusal. Everything else is asserted through the double's own API — otherwise
 * a test that failed on the signature would say nothing about the reset or about the fixture. How
 * the server answers a signed request is checked in `bochka-app`, by a real client on a real
 * socket.
 */
class EmbeddedTest {
    private fun head(
        bochka: Bochka,
        path: String,
    ): Int {
        val client = HttpClient.newHttpClient()
        val request =
            HttpRequest
                .newBuilder(URI.create(bochka.endpoint + path))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        return client.send(request, BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `a reset forgets everything without restarting the server`() {
        // M-95, and the first thing anybody asks for once there are more than ten tests. A restart
        // per test costs a new store and a new socket; a reset costs clearing some structures.
        Bochka.start().use { bochka ->
            bochka.put("photos", "a.txt", "первый".toByteArray())
            bochka.put("photos", "b.txt", "второй".toByteArray())
            assertEquals(2, bochka.objectCount)
            val portBefore = bochka.port

            bochka.reset()

            assertEquals(0, bochka.objectCount)
            assertEquals(portBefore, bochka.port, "the port does not change: a reset is not a restart")
            assertEquals(emptyList(), bochka.bucketNames, "buckets go along with the objects")
        }
    }

    @Test
    fun `after a reset things can be put again, and the disk does not grow forever`() {
        Bochka.start().use { bochka ->
            repeat(3) { round ->
                bochka.put("photos", "a.txt", "раунд $round".toByteArray())
                assertEquals(1, bochka.objectCount)
                bochka.reset()
            }
            bochka.put("photos", "a.txt", "последний".toByteArray())

            assertEquals(1, bochka.objectCount)
            // A reset must leave no files behind: otherwise a test running a thousand rounds fills
            // the disk with something nobody ever looks at.
            //
            // `.lock` is excluded along with the journal and for the same reason: it is not
            // storage but a claim on the directory (M-224), and it has to outlive a reset — the
            // store is open at that moment, and removing it would let a second process into the
            // middle of somebody else's life.
            val files =
                bochka.dataDirectory
                    .toFile()
                    .walkTopDown()
                    .filter { it.isFile && it.name != "index.log" && it.name != ".lock" }
                    .count()
            assertTrue(files <= 1, "files were left after the reset: $files")
        }
    }

    @Test
    fun `a fixture is put before the first request`() {
        // M-98: a test starts from a state rather than from ten SDK calls made to reach it.
        Bochka.start().use { bochka ->
            bochka.put("fixtures", "hello.txt", "привет".toByteArray())

            assertEquals(1, bochka.objectCount)
            assertEquals(listOf("fixtures"), bochka.bucketNames, "the bucket is created for the object by itself")
        }
    }

    @Test
    fun `an ordered refusal answers the next request and clears itself`() {
        // M-99, and the one thing a mock is structurally better at than a real server: there is
        // nothing else that makes a client live through a `503`. Client code nobody can knock over
        // is untested on retries.
        Bochka.start().use { bochka ->
            bochka.failNext(503)

            assertEquals(503, head(bochka, "/anything"))
            assertTrue(head(bochka, "/anything") != 503, "there was one refusal and it cleared itself")
        }
    }

    @Test
    fun `a refusal can be ordered for several requests in a row`() {
        Bochka.start().use { bochka ->
            bochka.failNext(500, times = 2)

            assertEquals(500, head(bochka, "/anything"))
            assertEquals(500, head(bochka, "/anything"))
            assertTrue(head(bochka, "/anything") != 500)
        }
    }

    @Test
    fun `a status the double cannot name a code for is refused`() {
        // M-231, and the same rule by which the server refuses a policy it cannot enforce: an answer
        // accepted and named wrongly is discovered not through an error but through a test that
        // passed for nothing. The double cannot name a `404` — the refusal is injected before the
        // request is parsed, and at that moment it cannot tell `NoSuchBucket` from `NoSuchKey`.
        Bochka.start().use { bochka ->
            val refusal = assertFailsWith<IllegalArgumentException> { bochka.failNext(404) }

            assertTrue(refusal.message!!.contains("404"), refusal.message!!)
            assertTrue(refusal.message!!.contains("503"), "the message names what can be ordered")
        }
    }

    @Test
    fun `a reset clears primed refusals too`() {
        // Otherwise a refusal set up in one test fires in the next, and it gets hunted for where it
        // was never set up.
        Bochka.start().use { bochka ->
            bochka.failNext(503, times = 5)
            bochka.reset()

            assertTrue(head(bochka, "/anything") != 503)
        }
    }
}
