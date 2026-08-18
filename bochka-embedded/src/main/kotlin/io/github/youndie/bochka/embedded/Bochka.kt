package io.github.youndie.bochka.embedded

import io.github.youndie.bochka.app.LoggingHandler
import io.github.youndie.bochka.app.S3Handler
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.http.HttpServer
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.sigv4.Credentials
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * bochka inside somebody else's process.
 *
 * The niche the README names: on the JVM the slot for "an S3 store you can start in a test" holds
 * a mock, and a mock answers what it was told to answer. This is the same server the image runs —
 * same signature verification, same four body framings, same storage — with a shorter way to start
 * it and a directory it will clean up.
 *
 * ```kotlin
 * Bochka.start().use { bochka ->
 *     val s3 = S3Client.builder()
 *         .endpointOverride(URI.create(bochka.endpoint))
 *         .credentialsProvider { AwsBasicCredentials.create(bochka.accessKeyId, bochka.secretKey) }
 *         .forcePathStyle(true)
 *         .build()
 * }
 * ```
 *
 * ## What is deliberately different from the server
 *
 * [Durability.NONE] by default. A test that `fsync`s every write measures the disk, and the thing
 * a test is checking is never durability — except when it is, and then [durable] says so. Naming
 * it here rather than choosing silently, because a store that quietly does not flush is the kind
 * of default somebody finds out about in production.
 */
class Bochka private constructor(
    private val store: ObjectStore,
    private val server: HttpServer,
    private val root: Path,
    private val ownsRoot: Boolean,
    val accessKeyId: String,
    val secretKey: String,
    val region: String,
) : Closeable {
    val port: Int get() = server.boundPort

    /** `http://127.0.0.1:<port>`, which is what an SDK wants as its endpoint override. */
    val endpoint: String get() = "http://127.0.0.1:$port"

    /** Where the objects are, for a test that wants to look at the disk rather than through S3. */
    val dataDirectory: Path get() = root

    /** How many objects exist right now, without listing anything. */
    val objectCount: Int get() = store.objectCount

    override fun close() {
        server.close()
        store.close()
        if (ownsRoot) {
            runCatching {
                Files.walk(root).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    companion object {
        const val DEFAULT_ACCESS_KEY_ID = "bochkaadmin"
        const val DEFAULT_SECRET_KEY = "bochkasecret"

        /**
         * Starts one. The port is chosen by the operating system unless [port] says otherwise.
         *
         * Zero rather than a default: a fixed port turns two tests running at once into one test
         * failing with a message about an address, and the caller reads [endpoint] anyway.
         */
        @JvmStatic
        @JvmOverloads
        fun start(
            port: Int = 0,
            directory: Path? = null,
            accessKeyId: String = DEFAULT_ACCESS_KEY_ID,
            secretKey: String = DEFAULT_SECRET_KEY,
            region: String = "us-east-1",
            durable: Boolean = false,
            log: Boolean = false,
        ): Bochka {
            val root = directory ?: Files.createTempDirectory("bochka-embedded")
            val store =
                ObjectStore(
                    root = root,
                    durability = if (durable) ObjectStore.Durability.FSYNC else ObjectStore.Durability.NONE,
                )
            val handler =
                S3Handler(
                    store = store,
                    verifier = SignatureVerifier(Credentials(mapOf(accessKeyId to secretKey)), region = region),
                    router = S3Router(),
                )
            val server = HttpServer(LoggingHandler(handler, enabled = log), bindAddress = "127.0.0.1", port = port)
            return Bochka(store, server, root, ownsRoot = directory == null, accessKeyId, secretKey, region)
        }
    }
}
