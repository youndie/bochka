package io.github.youndie.bochka.junit

import io.github.youndie.bochka.embedded.Bochka
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * A bochka for the lifetime of a test class, with a reset between tests.
 *
 * ```kotlin
 * class MyTest {
 *     companion object {
 *         @JvmField
 *         @RegisterExtension
 *         val bochka = BochkaExtension()
 *     }
 *
 *     @Test fun `stores the report`() {
 *         val s3 = S3Client.builder()
 *             .endpointOverride(URI.create(bochka.endpoint))
 *             .forcePathStyle(true)
 *             .build()
 *         // …
 *     }
 * }
 * ```
 *
 * ## Why one store per class rather than one per test
 *
 * Because what is expensive is not the state but the **start**: a new store means a new journal, a
 * new socket and a new endpoint that has to be passed somewhere. The state is cleared by
 * [Bochka.reset], and that is a couple of structures emptied.
 *
 * The isolation is no worse for it: after every test there are no objects, no buckets and no primed
 * refusals left. What is left is the port and the keys — exactly what a test finds convenient to
 * treat as constant.
 *
 * **For tests running in parallel inside one class this is not enough**, and that is said out loud
 * here rather than left to be guessed: they share one store, and a reset between them wipes
 * somebody else's work. For those, take one extension per test (`@RegisterExtension` on a
 * non-static field) — then the cost of starting is paid for isolation knowingly.
 */
class BochkaExtension
    @JvmOverloads
    constructor(
        private val durable: Boolean = false,
        private val log: Boolean = false,
    ) : BeforeAllCallback,
        AfterEachCallback,
        AfterAllCallback {
        private var running: Bochka? = null

        /** The running server. Asking for it before the start is a usage error rather than a
         *  `null`. */
        val bochka: Bochka
            get() = running ?: error("bochka is not up yet: the extension is registered but no test has started")

        val endpoint: String get() = bochka.endpoint
        val accessKeyId: String get() = bochka.accessKeyId
        val secretKey: String get() = bochka.secretKey
        val region: String get() = bochka.region

        override fun beforeAll(context: ExtensionContext) {
            running = Bochka.start(durable = durable, log = log)
        }

        override fun afterEach(context: ExtensionContext) {
            running?.reset()
        }

        override fun afterAll(context: ExtensionContext) {
            running?.close()
            running = null
        }
    }
