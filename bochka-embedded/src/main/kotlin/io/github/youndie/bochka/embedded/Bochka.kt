package io.github.youndie.bochka.embedded

import io.github.youndie.bochka.app.LoggingHandler
import io.github.youndie.bochka.app.S3Handler
import io.github.youndie.bochka.core.Metadata
import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.http.HttpServer
import io.github.youndie.bochka.s3.Lifecycle
import io.github.youndie.bochka.s3.LifecycleSweep
import io.github.youndie.bochka.s3.Lifecycles
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.sigv4.Credentials
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

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
    private val failures: InjectedFailures,
    private val root: Path,
    private val ownsRoot: Boolean,
    private val lifecycle: LifecycleSweep,
    private val lifecycleThread: java.util.concurrent.ScheduledExecutorService,
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

    /**
     * Which buckets exist — for an assertion in a test rather than for work.
     *
     * The same thing through `ListBuckets` costs a signed request and an XML parse, so the
     * assertion starts depending on two things instead of one. A test that failed on the signature
     * says nothing about buckets.
     */
    val bucketNames: List<String> get() = store.bucketNames()

    /**
     * Forgets everything without restarting the server: the port, the endpoint and the keys stay
     * the same.
     *
     * The thing called between tests. A restart costs a new store and a new socket, and therefore a
     * new endpoint that has to be passed somewhere; a reset costs none of that. Primed refusals are
     * cleared too — otherwise a refusal set up in one test fires in the next, and it gets hunted
     * for where it was never set up.
     */
    fun reset() {
        store.reset()
        failures.clear()
    }

    /**
     * Runs the lifecycle rules **now** and says what it deleted.
     *
     * The background sweep runs on its own too, but a test that waits for it is a test with a
     * `sleep` in it, and a `sleep` in a test either makes it slow or makes it flaky, usually both.
     * Here the sweep is called and finishes before returning: a rule with a term of one "day" and
     * `lifecycleDay = Duration.ofMillis(1)` is checked without a single pause.
     */
    fun sweepLifecycle(): LifecycleSweep.Report = lifecycle.sweep()

    /**
     * Puts an object directly, bypassing HTTP: a fixture for a test that starts **from a state**.
     *
     * Not a "convenient wrapper over the SDK": there is no signature, no socket and no parsing
     * here. A test that just needs an object to exist otherwise pays for it with a dozen client
     * calls and the first minute of its reader's attention.
     */
    @JvmOverloads
    fun put(
        bucket: String,
        key: String,
        content: ByteArray,
        contentType: String? = null,
    ) {
        store.createBucket(bucket)
        val staged = kotlinx.coroutines.runBlocking { store.stage { sink -> sink.write(content, 0, content.size) } }
        store.commit(bucket, ObjectKey.of(key), Metadata(contentType = contentType), staged)
    }

    /**
     * Answer [status] to the next [times] requests, whatever the client asks for.
     *
     * The one thing a real store cannot do and a test double is seriously needed for: making client
     * code live through a refusal. It clears itself once the counter runs out, and entirely on
     * [reset].
     *
     * **[times] has to cover the client's retries rather than the number of calls in the test**
     * (M-231). A retrying client eats the priming whole and silently: `io.minio:minio` 9.x retries
     * `408`, `429`, `499`, `500`, `502`, `503`, `504` and `520`, five times by default — so
     * `failNext(503, times = 1)` never reaches the calling code **at all**, the retry lands on a
     * healthy server, and a test checking refusal handling goes green having checked nothing. That
     * is exactly the class of thing the priming exists for. Take the number with room to spare
     * (`times = 10` outlives five retries), and assert that "the refusal arrived" through an
     * exception rather than through an absence of complaints.
     *
     * The statuses that can be ordered are the ones an error code follows from: `400`, `403`,
     * `405`, `408`, `412`, `429`, `500`, `501`, `502`, `503`, `504`, `507`. Anything else is an
     * `IllegalArgumentException`: the refusal is injected before the request is parsed, so the
     * double cannot name a `404` (`NoSuchBucket` or `NoSuchKey` — it does not know yet), and a code
     * picked at random would cost more than a missing one, because a client branches on exactly
     * that code.
     */
    @JvmOverloads
    fun failNext(
        status: Int = 503,
        times: Int = 1,
    ) = failures.failNext(status, times)

    override fun close() {
        lifecycleThread.shutdownNow()
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
            lifecycleDay: Duration = Lifecycle.DAY,
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
                    lifecycleDay = lifecycleDay,
                )
            val failures = InjectedFailures(handler)
            val server = HttpServer(LoggingHandler(failures, enabled = log), bindAddress = "127.0.0.1", port = port)
            val sweep = LifecycleSweep(store, Lifecycles(store), lifecycleDay)
            // The background sweep runs here too, because a rule accepted and not carried out lies
            // the same way in the server and in a test double. The thread is a daemon and lives
            // until `close`: an embedded bochka is started once per test, and one leaked thread per
            // test means strange logs first and exhausted memory after.
            val ticker =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "bochka-lifecycle").apply { isDaemon = true }
                }
            val period = lifecycleDay.dividedBy(10).coerceIn(Duration.ofMillis(50), Duration.ofHours(1))
            ticker.scheduleWithFixedDelay(
                {
                    // Catching here is **mandatory**, for a reason that has nothing to do with what
                    // went wrong: an exception out of a `scheduleWithFixedDelay` task cancels it
                    // forever, and silently. The sweep would stop running altogether with nowhere
                    // to learn that from.
                    //
                    // But the cause is **named** (M-207): what stood here was a bare
                    // `runCatching { … }`, and a broken sweep in the embedded mode was
                    // indistinguishable from a healthy one. Printed the same way as in
                    // `Main.startLifecycle`.
                    runCatching { sweep.sweep() }.onFailure { println("lifecycle sweep failed: $it") }
                },
                period.toMillis(),
                period.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            return Bochka(
                store,
                server,
                failures,
                root,
                ownsRoot = directory == null,
                sweep,
                ticker,
                accessKeyId,
                secretKey,
                region,
            )
        }
    }
}
