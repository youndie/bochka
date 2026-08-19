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
     * Какие бакеты есть — для утверждения в тесте, а не для работы.
     *
     * Через `ListBuckets` то же самое стоит подписанного запроса и разбора XML, то есть проверка
     * начинает зависеть от двух вещей вместо одной. Тест, упавший на подписи, ничего не говорит
     * про бакеты.
     */
    val bucketNames: List<String> get() = store.bucketNames()

    /**
     * Забывает всё, не перезапуская сервер: порт, эндпоинт и ключи остаются теми же.
     *
     * То, что зовут между тестами. Перезапуск стоит нового стора и нового сокета, а значит
     * и нового эндпоинта, который придётся куда-то передать; сброс не стоит ничего из этого.
     * Заготовленные отказы снимаются тоже — иначе отказ, заведённый в одном тесте, срабатывает
     * в следующем, и ищут его там, где не заводили.
     */
    fun reset() {
        store.reset()
        failures.clear()
    }

    /**
     * Прогоняет правила жизненного цикла **сейчас** и говорит, что удалил.
     *
     * Фоновый обход идёт и сам, но тест, который его ждёт, — это тест со `sleep`, а `sleep`
     * в тесте либо делает его медленным, либо делает его мигающим, обычно и то и другое. Здесь
     * обход зовут, и он заканчивается до возврата: правило со сроком в один «день» при
     * `lifecycleDay = Duration.ofMillis(1)` проверяется без единой паузы.
     */
    fun sweepLifecycle(): LifecycleSweep.Report = lifecycle.sweep()

    /**
     * Кладёт объект напрямую, минуя HTTP: заготовка для теста, который начинается **с состояния**.
     *
     * Не «удобная обёртка над SDK»: тут нет ни подписи, ни сокета, ни разбора. Тест, которому
     * нужно, чтобы объект просто был, иначе платит за это десятком вызовов клиента и своей
     * первой минутой чтения.
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
     * Ответить [status] на следующие [times] запросов — что бы клиент ни спросил.
     *
     * Единственное, чего настоящее хранилище не умеет и что от тестового двойника нужно всерьёз:
     * заставить клиентский код пережить отказ. Снимается само, когда счётчик кончится, и целиком
     * при [reset].
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
            // Фоновый обход есть и здесь, потому что правило, принятое и не исполняемое, врёт
            // одинаково в сервере и в тестовом двойнике. Поток — демон и живёт до `close`:
            // встроенная bochka заводится по одной на тест, и оставленный поток на тест — это
            // сначала странные логи, а потом кончившаяся память.
            val ticker =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "bochka-lifecycle").apply { isDaemon = true }
                }
            val period = lifecycleDay.dividedBy(10).coerceIn(Duration.ofMillis(50), Duration.ofHours(1))
            ticker.scheduleWithFixedDelay(
                { runCatching { sweep.sweep() } },
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
