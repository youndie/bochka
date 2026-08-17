package io.github.youndie.bochka.app

import io.github.youndie.bochka.http.HttpServer
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.sigv4.Credentials
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * Runs bochka.
 *
 * Configuration is environment only, and deliberately thin: this exists so that a real client can
 * be pointed at a real socket (M3). Properties, validation-at-startup and the runtime profile baked
 * into a start script are M11 — a configuration system invented now would be shaped by what the
 * live-client harness happens to need this week.
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = env("BOCHKA_PORT")?.toIntOrNull() ?: 9000
        val address = env("BOCHKA_BIND_ADDRESS") ?: "127.0.0.1"
        val dataDir = env("BOCHKA_DATA_DIR")?.let(Path::of) ?: createTempDirectory("bochka")
        val region = env("BOCHKA_REGION") ?: "us-east-1"

        // Two by default, because the compatibility suite needs two and a server built around one
        // key cannot run half of it (research, §1.11.1).
        val credentials =
            Credentials(
                mapOf(
                    (env("BOCHKA_ACCESS_KEY") ?: "bochkaadmin") to (env("BOCHKA_SECRET_KEY") ?: "bochkasecret"),
                    (
                        env(
                            "BOCHKA_ALT_ACCESS_KEY",
                        ) ?: "bochkaalt"
                    ) to (env("BOCHKA_ALT_SECRET_KEY") ?: "bochkaaltsecret"),
                ),
            )

        val handler =
            S3Handler(
                store = DraftStore(dataDir),
                verifier = SignatureVerifier(credentials, region = region),
                router = S3Router(virtualHostSuffixes = env("BOCHKA_VIRTUAL_HOST_SUFFIXES")?.split(",") ?: emptyList()),
            )

        val logged = LoggingHandler(handler, enabled = env("BOCHKA_LOG") == "1")
        val server = HttpServer(logged, bindAddress = address, port = port)
        println("bochka listening on $address:${server.boundPort}, data in $dataDir")
        println("access keys: ${credentials.ids.sorted().joinToString(", ")}")
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        Thread.currentThread().join()
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}
