package io.github.youndie.bochka.embedded

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The embeddable server, used the way a consumer would.
 *
 * Deliberately through the socket and not through the objects behind it: what this module offers
 * is "a real S3 endpoint in your test", and a test that reached past the endpoint would be
 * checking something nobody buys.
 */
class BochkaTest {
    @Test
    fun `it starts, serves and cleans up after itself`() {
        val directory: java.nio.file.Path
        Bochka.start().use { bochka ->
            directory = bochka.dataDirectory
            assertTrue(bochka.port > 0, "the port is chosen by the operating system")
            assertEquals("http://127.0.0.1:${bochka.port}", bochka.endpoint)
            assertTrue(Files.isDirectory(directory))

            // Unauthenticated, so no signing is needed to prove the socket answers S3 rather than
            // being merely open.
            val response =
                java.net.http.HttpClient
                    .newHttpClient()
                    .send(
                        java.net.http.HttpRequest
                            .newBuilder(java.net.URI.create("${bochka.endpoint}/"))
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers
                            .ofString(),
                    )
            assertEquals(403, response.statusCode())
            assertTrue(response.body().contains("AccessDenied"), response.body())
        }

        // A temporary directory it made is a temporary directory it removes: a test suite that
        // starts a hundred of these must not leave a hundred directories behind.
        assertFalse(Files.exists(directory), "the directory it created should go with it")
    }

    @Test
    fun `a directory the caller chose is left alone`() {
        val mine = Files.createTempDirectory("caller-owned")
        try {
            Bochka.start(directory = mine).use { assertEquals(mine, it.dataDirectory) }
            assertTrue(Files.exists(mine), "a directory the caller passed in is the caller's")
        } finally {
            Files.walk(mine).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun `two run at once`() {
        // The reason the default port is zero. A fixed one turns two tests running in parallel
        // into one test failing with a message about an address.
        Bochka.start().use { first ->
            Bochka.start().use { second ->
                assertTrue(first.port != second.port)
            }
        }
    }
}
