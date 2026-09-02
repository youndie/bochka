package io.github.youndie.bochka.benchmark

import io.github.youndie.bochka.embedded.Bochka
import io.github.youndie.bochka.s3.sigv4.CanonicalRequest
import io.github.youndie.bochka.s3.sigv4.Sigv4
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * What it costs to keep one of these in a test: how long a start takes, and what it holds while
 * idle (M-277).
 *
 * The axis is not the one the rest of this document uses. Everything else here is processor time
 * per byte, because everything else moves bytes; this moves none. The audience is somebody running
 * a test suite a hundred times a day, for whom the interesting quantities are the seconds before
 * the first assertion and the memory the runner has to find for a server that is doing nothing.
 *
 * **Two moments, because they are not the same number.** `Bochka.start()` returning means the
 * socket is bound; a test cannot do anything with that until a signed request comes back, which is
 * the signature path and the store's first write. Reporting only the first would flatter the thing
 * being measured.
 *
 * ```
 * ./gradlew :bochka-benchmark:measure -Pbochka.measure=startup
 * ```
 */
object Startup {
    private const val ACCESS_KEY = "bochkaadmin"
    private const val SECRET = "bochkasecret"
    private const val REGION = "us-east-1"

    /** How long an idle server is left alone before its footprint is read. */
    private const val SETTLE_MILLIS = 2_000L

    fun measure(
        directory: Path,
        repeats: Int,
    ) {
        val client = HttpClient.newBuilder().build()

        // The footprint goes first, on a process that has never started a server, and the first
        // version of this had it last — where it reported **zero**. Eight starts had already
        // loaded every class and touched every page, so the ninth server cost nothing to add. The
        // number was a property of the order, not of the server.
        footprint(directory, client)

        // Discarded, and that is not superstition: the first start of the process pays for class
        // loading, the JIT seeing this code at all, and the first temporary directory on a cold
        // page cache. Publishing it would be publishing a warm-up.
        run(directory, client)

        val starts = ArrayList<Long>(repeats)
        val readies = ArrayList<Long>(repeats)
        repeat(repeats) {
            val (bound, ready) = run(directory, client)
            starts += bound
            readies += ready
        }

        report("Bochka.start() returns", starts)
        report("first signed request answered", readies)
    }

    /**
     * What one idle server adds to the process holding it.
     *
     * A difference rather than a total, because the total is mostly the JVM the test runner was
     * paying for anyway — and the absolute is printed beside it so the difference cannot be read
     * as the whole cost. Both are read from `/proc/self/status`, which counts the thread stacks,
     * the direct buffers and the mapped files that a heap figure does not.
     */
    private fun footprint(
        directory: Path,
        client: HttpClient,
    ) {
        val before = residentKilobytes()
        Bochka.start(directory = Files.createTempDirectory(directory, "idle")).use {
            createBucket(client, it, "idle")
            Thread.sleep(SETTLE_MILLIS)
            val after = residentKilobytes()
            if (before == null || after == null) {
                println("  idle footprint             unavailable: this platform does not publish VmRSS")
            } else {
                println(
                    "  idle footprint             %.1f MiB added, %.1f MiB resident with it up".format(
                        (after - before) / 1024.0,
                        after / 1024.0,
                    ),
                )
            }
        }
    }

    /** Returns the two moments in milliseconds: the call returning, and the first answer. */
    private fun run(
        directory: Path,
        client: HttpClient,
    ): Pair<Long, Long> {
        val root = Files.createTempDirectory(directory, "startup")
        val began = System.nanoTime()
        val bochka = Bochka.start(directory = root)
        val bound = System.nanoTime()
        try {
            createBucket(client, bochka, "first")
            val answered = System.nanoTime()
            return (bound - began) / 1_000_000 to (answered - began) / 1_000_000
        } finally {
            bochka.close()
        }
    }

    /** A signed `PUT` of a bucket: the whole path a test's first call takes. */
    private fun createBucket(
        client: HttpClient,
        bochka: Bochka,
        bucket: String,
    ) {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC))
        val host = "127.0.0.1:${bochka.port}"
        val hash = Sigv4.sha256Hex(ByteArray(0))
        val headers =
            listOf(
                "host" to host,
                "x-amz-content-sha256" to hash,
                "x-amz-date" to timestamp,
            )
        val canonical =
            CanonicalRequest.build(
                CanonicalRequest.Request("PUT", "/$bucket", "", headers),
                headers.map { it.first }.sorted(),
                hash,
                CanonicalRequest.PathMode.VERBATIM,
            )
        val scope = "${timestamp.take(8)}/$REGION/s3/aws4_request"
        val signature =
            Sigv4.signature(
                Sigv4.signingKey(SECRET, timestamp.take(8), REGION, "s3"),
                Sigv4.stringToSign(timestamp, scope, canonical),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("${bochka.endpoint}/$bucket"))
                // `Host` is not set here and must not be: the JDK client refuses it as a
                // restricted header, and sets it itself to exactly the authority being signed.
                .header("x-amz-content-sha256", hash)
                .header("x-amz-date", timestamp)
                .header(
                    "Authorization",
                    "AWS4-HMAC-SHA256 Credential=$ACCESS_KEY/$scope, " +
                        "SignedHeaders=${headers.map { it.first }.sorted().joinToString(";")}, " +
                        "Signature=$signature",
                ).PUT(HttpRequest.BodyPublishers.noBody())
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "the first request answered ${response.statusCode()}: ${response.body()}"
        }
    }

    /**
     * Resident memory of this process in kibibytes, or null where the kernel does not say.
     *
     * `/proc/self/status` rather than a JVM figure, because the question is what the machine
     * running the tests has to find. The heap says nothing about the thread stacks, the direct
     * buffers or the mapped files, and those are most of what a server holds.
     */
    private fun residentKilobytes(): Long? =
        runCatching {
            Path
                .of("/proc/self/status")
                .toFile()
                .readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLong()
        }.getOrNull()

    private fun report(
        what: String,
        millis: List<Long>,
    ) {
        val sorted = millis.sorted()
        println(
            "  %-26s %5d ms median, %d to %d over %d runs".format(
                what,
                sorted[sorted.size / 2],
                sorted.first(),
                sorted.last(),
                sorted.size,
            ),
        )
    }
}
