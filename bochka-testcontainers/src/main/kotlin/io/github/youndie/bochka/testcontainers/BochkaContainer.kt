package io.github.youndie.bochka.testcontainers

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * The published image, started by Testcontainers, for tests that want a server in a container.
 *
 * ```kotlin
 * class MyTest {
 *     companion object {
 *         @JvmStatic
 *         val bochka = BochkaContainer().apply { start() }
 *     }
 *
 *     @Test fun `stores the report`() {
 *         val s3 = MinioClient.builder()
 *             .endpoint(bochka.endpoint)
 *             .credentials(bochka.accessKeyId, bochka.secretKey)
 *             .build()
 *         // …
 *     }
 * }
 * ```
 *
 * ## Why this exists when [io.github.youndie.bochka.embedded.Bochka] is faster
 *
 * It is not faster, and this class is not here to compete with it: an embedded server answers its
 * first signed request in single-digit milliseconds, while a container is seconds. The reason is
 * that half the projects which would use this **already have a Testcontainers harness** — a base
 * class, a shared network, a reuse policy, a way of dumping logs when a test fails — and asking
 * them to rewrite it for one dependency is asking them not to bother. A container that plugs into
 * the harness they have costs them one line.
 *
 * There is one thing it gives that the embedded mode cannot, and it is worth naming: this runs
 * **the artefact that ships**. Same image, same non-root user, same runtime profile — so a test
 * against it is a test against what the operator will run, rather than against the same classes in
 * the test's own JVM.
 *
 * ## What it does not do
 *
 * It does not mount a volume: a container that is thrown away after the test has no data to keep,
 * and the image writes to a path it owns. It does not reuse: reuse across tests is the harness's
 * decision to make and Testcontainers already expresses it. And it does not wait for a port to
 * open — that says the socket is bound, not that the server will answer, which is a distinction
 * this project has already paid for twice.
 */
class BochkaContainer(
    image: DockerImageName = DEFAULT_IMAGE,
) : GenericContainer<BochkaContainer>(image) {
    var accessKeyId: String = DEFAULT_ACCESS_KEY_ID
        private set

    var secretKey: String = DEFAULT_SECRET_KEY
        private set

    init {
        withExposedPorts(PORT)
        applyKeys()
        // The health route rather than the port, and rather than a log line. A bound port means
        // the socket exists; a log line means the process said something. `GET /-/healthy`
        // answering 200 means the store opened, the index was recovered and the handler is on.
        waitingFor(Wait.forHttp(HEALTH_PATH).forPort(PORT).forStatusCode(200))
    }

    /** Names the one key this container will accept, instead of the two defaults it ships with. */
    fun withCredentials(
        accessKeyId: String,
        secretKey: String,
    ): BochkaContainer {
        this.accessKeyId = accessKeyId
        this.secretKey = secretKey
        applyKeys()
        return this
    }

    /** Where a client points, once [start] has returned. */
    val endpoint: String get() = "http://$host:${getMappedPort(PORT)}"

    private fun applyKeys() {
        addEnv("BOCHKA_KEYS", "$accessKeyId:$secretKey")
    }

    companion object {
        /** What the image exposes; the mapped one is what a client uses. */
        const val PORT = 9000

        /** `GET` here answers 200 when the server is able to serve, and 503 while it is not. */
        const val HEALTH_PATH = "/-/healthy"

        const val DEFAULT_ACCESS_KEY_ID = "bochkaadmin"
        const val DEFAULT_SECRET_KEY = "bochkasecret"

        /**
         * A tag rather than `latest`, and that is the whole of the argument: a test suite pinned to
         * `latest` changes what it tests when somebody else publishes, and the failure arrives on a
         * morning when nothing in the repository changed.
         */
        val DEFAULT_IMAGE: DockerImageName = DockerImageName.parse("ghcr.io/youndie/bochka:v0.5.0")
    }
}
