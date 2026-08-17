package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.http.HttpServer
import io.github.youndie.bochka.s3.S3Router
import io.github.youndie.bochka.s3.sigv4.CanonicalRequest
import io.github.youndie.bochka.s3.sigv4.Credentials
import io.github.youndie.bochka.s3.sigv4.SignatureVerifier
import io.github.youndie.bochka.s3.sigv4.Sigv4
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A whole bochka on a real socket, spoken to by a real HTTP client over a signed request.
 *
 * The three layers are only ever wrong together — a route that decodes the key differently from the
 * signature, a response whose `Content-Length` disagrees with what the socket carries — and none of
 * that is visible to a test that calls the handler directly. Which is how `HEAD` came to answer
 * `Content-Length: 0` for weeks: every unit test passed, and the object was intact, and rclone
 * deleted it anyway.
 *
 * The client is the JDK's, deliberately: it frames requests and reads responses by its own rules,
 * so a response this server renders slightly wrong is a failure here rather than a thing the test
 * helpfully parses around.
 */
class S3Fixture(
    virtualHostSuffixes: List<String> = emptyList(),
) : AutoCloseable {
    val root: Path = Files.createTempDirectory("bochka-e2e")
    val store = ObjectStore(root, ObjectStore.Durability.NONE)

    private val server =
        HttpServer(
            S3Handler(
                store = store,
                verifier = SignatureVerifier(Credentials(mapOf(ACCESS_KEY to SECRET))),
                router = S3Router(virtualHostSuffixes),
            ),
            port = 0,
        )

    private val client: HttpClient = HttpClient.newBuilder().build()

    val port: Int get() = server.boundPort

    /**
     * What goes in `Host`, both on the wire and into the signature.
     *
     * Settable so a test can address a bucket virtual-hosted: the socket is still the loopback one,
     * and what decides the route is the header — which is also what is signed, so getting the two
     * out of step is a `SignatureDoesNotMatch` rather than a routing bug.
     */
    var host: String = "127.0.0.1:$port"

    class Answer(
        val status: Int,
        private val headers: java.net.http.HttpHeaders,
        val body: ByteArray,
    ) {
        val text: String get() = String(body)

        fun header(name: String): String? = headers.firstValue(name).orElse(null)

        fun headers(name: String): List<String> = headers.allValues(name)
    }

    /**
     * Signs and sends. [payloadHash] defaults to the hash of the body, which is what a client that
     * knows its body does; `UNSIGNED-PAYLOAD` is the other real case.
     */
    fun send(
        method: String,
        path: String,
        query: String = "",
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
        payloadHash: String? = null,
    ): Answer {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC))
        val hash = payloadHash ?: Sigv4.sha256Hex(body)

        val signed =
            headers +
                listOf(
                    "host" to host,
                    "x-amz-content-sha256" to hash,
                    "x-amz-date" to timestamp,
                )
        val signedNames = signed.map { it.first.lowercase() }.distinct().sorted()
        val canonical =
            CanonicalRequest.build(
                CanonicalRequest.Request(method, path, query, signed),
                signedNames,
                hash,
                CanonicalRequest.PathMode.VERBATIM,
            )
        val scope = "${timestamp.take(8)}/$REGION/s3/aws4_request"
        val signature =
            Sigv4.signature(
                Sigv4.signingKey(SECRET, timestamp.take(8), REGION, "s3"),
                Sigv4.stringToSign(timestamp, scope, canonical),
            )

        val uri = URI.create("http://127.0.0.1:$port$path" + if (query.isEmpty()) "" else "?$query")
        val builder =
            HttpRequest
                .newBuilder(uri)
                .header("Host", host)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .header("x-amz-content-sha256", hash)
                .header("x-amz-date", timestamp)
                .header(
                    "Authorization",
                    "${Sigv4.ALGORITHM} Credential=$ACCESS_KEY/$scope, " +
                        "SignedHeaders=${signedNames.joinToString(";")}, Signature=$signature",
                )
        for ((name, value) in headers) builder.header(name, value)

        val response = client.send(builder.build(), BodyHandlers.ofByteArray())
        return Answer(response.statusCode(), response.headers(), response.body())
    }

    fun createBucket(bucket: String): Answer = send("PUT", "/$bucket")

    fun put(
        bucket: String,
        key: String,
        content: ByteArray,
        headers: List<Pair<String, String>> = emptyList(),
    ): Answer = send("PUT", "/$bucket/$key", headers = headers, body = content)

    fun put(
        bucket: String,
        key: String,
        content: String,
        headers: List<Pair<String, String>> = emptyList(),
    ): Answer = put(bucket, key, content.toByteArray(), headers)

    fun get(
        bucket: String,
        key: String,
        headers: List<Pair<String, String>> = emptyList(),
    ): Answer = send("GET", "/$bucket/$key", headers = headers)

    override fun close() {
        server.close()
        store.close()
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    companion object {
        const val ACCESS_KEY = "bochkaadmin"
        const val SECRET = "bochkasecret"
        const val REGION = "us-east-1"
    }
}
