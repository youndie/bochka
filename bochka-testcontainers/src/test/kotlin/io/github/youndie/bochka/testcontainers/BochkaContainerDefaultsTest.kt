package io.github.youndie.bochka.testcontainers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the container promises before anything is started, asked without a Docker daemon.
 *
 * [BochkaContainerTest] is the real thing and needs an image; this is the half of the contract that
 * can be checked anywhere, and it exists because the gate runs where there may be no daemon at all.
 * Constructing a `GenericContainer` contacts nothing — only `start()` does.
 */
class BochkaContainerDefaultsTest {
    @Test
    fun `the image is a tag rather than latest`() {
        // Not decoration: a suite pinned to `latest` changes what it tests when somebody else
        // publishes, and the failure arrives on a morning when nothing in the repository changed.
        val image = BochkaContainer.DEFAULT_IMAGE.asCanonicalNameString()
        assertTrue(image.startsWith("ghcr.io/youndie/bochka:"), "the default image moved: $image")
        assertTrue(!image.endsWith(":latest"), "the default image is `latest`, which is not a version")
    }

    @Test
    fun `the credentials it was given are the ones the container is told`() {
        // The wiring this module exists for. Without it the container comes up with the image's own
        // defaults and every call made with the credentials the test was handed is refused — which
        // is what the container test sees at runtime, a whole Docker pull later.
        val container = BochkaContainer().withCredentials("someid", "somesecret")

        assertEquals("someid", container.accessKeyId)
        assertEquals("somesecret", container.secretKey)
        assertTrue(
            container.env.any { it == "BOCHKA_KEYS=someid:somesecret" },
            "the container carries ${container.env}, and none of it names the credentials it was given",
        )
    }

    @Test
    fun `it waits on the health route and exposes the port the image serves`() {
        assertEquals(9000, BochkaContainer.PORT)
        assertEquals("/-/healthy", BochkaContainer.HEALTH_PATH)
        assertTrue(
            BochkaContainer().exposedPorts.contains(BochkaContainer.PORT),
            "the container does not publish the port the image serves on",
        )
    }
}
