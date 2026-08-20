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
    accelRedirect: String? = null,
    /** What the fixture's one key may do; unrestricted unless a test narrows it (M19). */
    scope: io.github.youndie.bochka.s3.sigv4.KeyScope? = null,
    /**
     * Сколько длится «день» правила жизненного цикла.
     *
     * Сутки, как в поставке. Тест про истечение срока ставит миллисекунду — и тогда обход,
     * который зовут руками, делает всё, что должен, без единой паузы: `sleep` в тесте либо
     * замедляет его, либо делает мигающим, обычно и то и другое.
     */
    private val lifecycleDay: java.time.Duration = io.github.youndie.bochka.s3.Lifecycle.DAY,
) : AutoCloseable {
    val root: Path = Files.createTempDirectory("bochka-e2e")
    val store = ObjectStore(root, ObjectStore.Durability.NONE)

    private val server =
        HttpServer(
            S3Handler(
                store = store,
                verifier =
                    SignatureVerifier(
                        Credentials(
                            mapOf(ACCESS_KEY to SECRET, OTHER_ACCESS_KEY to OTHER_SECRET),
                            scope?.let { mapOf(ACCESS_KEY to it) } ?: emptyMap(),
                        ),
                    ),
                router = S3Router(virtualHostSuffixes),
                accelRedirect = accelRedirect,
                lifecycleDay = lifecycleDay,
            ),
            port = 0,
        )

    /**
     * Прогоняет правила жизненного цикла — тот же обход, что крутится в сервере фоном.
     *
     * [now] существует затем, чтобы тест истечения не зависел от того, насколько быстра машина.
     * «День» в тесте длится миллисекунду, и при часах по умолчанию вопрос «истёк ли срок» решает
     * то, сколько прошло между записью объекта и обходом, — величина, которой у теста нет. Один
     * прогон в CI на этом и упал: то же дерево, зелёное здесь пять раз подряд.
     */
    fun sweepLifecycle(
        now: java.time.Instant = java.time.Instant.now(),
    ): io.github.youndie.bochka.s3.LifecycleSweep.Report =
        io.github.youndie.bochka.s3
            .LifecycleSweep(
                store,
                io.github.youndie.bochka.s3
                    .Lifecycles(store),
                lifecycleDay,
            ).sweep(now)

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
        /**
         * Send the body with `Transfer-Encoding: chunked` and no `Content-Length`.
         *
         * The JDK client picks the framing from the publisher: one with a known length gets a
         * `Content-Length`, one without gets chunked. There is no way to ask for it directly, and
         * setting the header by hand is refused as contradicting the length.
         */
        chunked: Boolean = false,
        /**
         * Sign as the second key rather than the first (M27).
         *
         * Two keys and not two fixtures, because what the access model is about is two keys
         * against **one** store: a second server would share nothing and could not tell a private
         * object from somebody else's.
         */
        asOther: Boolean = false,
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
        val key = if (asOther) OTHER_ACCESS_KEY else ACCESS_KEY
        val secret = if (asOther) OTHER_SECRET else SECRET
        val signature =
            Sigv4.signature(
                Sigv4.signingKey(secret, timestamp.take(8), REGION, "s3"),
                Sigv4.stringToSign(timestamp, scope, canonical),
            )

        val uri = URI.create("http://127.0.0.1:$port$path" + if (query.isEmpty()) "" else "?$query")
        val builder =
            HttpRequest
                .newBuilder(uri)
                .header("Host", host)
                .method(
                    method,
                    if (chunked) {
                        HttpRequest.BodyPublishers.ofInputStream { java.io.ByteArrayInputStream(body) }
                    } else {
                        HttpRequest.BodyPublishers.ofByteArray(body)
                    },
                ).header("x-amz-content-sha256", hash)
                .header("x-amz-date", timestamp)
                .header(
                    "Authorization",
                    "${Sigv4.ALGORITHM} Credential=$key/$scope, " +
                        "SignedHeaders=${signedNames.joinToString(";")}, Signature=$signature",
                )
        for ((name, value) in headers) builder.header(name, value)

        val response = client.send(builder.build(), BodyHandlers.ofByteArray())
        return Answer(response.statusCode(), response.headers(), response.body())
    }

    /**
     * Неподписанный запрос — то, чем является preflight.
     *
     * Браузер шлёт `OPTIONS` до всякой авторизации и подписать его нечем: у клиентского кода
     * в этот момент нет ни ключа, ни повода его показывать. Поэтому это отдельный метод, а не
     * флаг у [send]: подписанный preflight не бывает, и возможность его отправить только сбивала
     * бы с толку.
     */

    fun options(
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
    ): Answer {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Host", host)
        for ((name, value) in headers) builder.header(name, value)
        val response = client.send(builder.build(), BodyHandlers.ofByteArray())
        return Answer(response.statusCode(), response.headers(), response.body())
    }

    /**
     * A request with no signature at all — what an orchestrator's probe sends.
     *
     * Deliberately not [send] with an empty key: the point of the health handle is that nothing in
     * the head is checked, and a helper that signed it "harmlessly" would leave that untested.
     */
    fun unsigned(
        method: String,
        path: String,
    ): Answer {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Host", host)
                .build()
        val response = client.send(request, BodyHandlers.ofByteArray())
        return Answer(response.statusCode(), response.headers(), response.body())
    }

    /**
     * `POST /<bucket>` with a `multipart/form-data` body — the upload a browser makes.
     *
     * Unsigned at the HTTP level on purpose: this operation carries its signature in the `policy`
     * and `signature` fields, and there is nothing in the head to sign it with. The body is built
     * byte by byte rather than by a library because the parser under test reads bytes: a helper
     * that framed it "correctly" would hide exactly the framing this is meant to exercise.
     */
    fun postForm(
        bucket: String,
        fields: List<Pair<String, String>>,
        file: ByteArray,
        fileName: String? = "upload.bin",
        boundary: String = "----bochkaformboundary",
    ): Answer {
        val body = java.io.ByteArrayOutputStream()

        fun line(text: String) = body.write("$text\r\n".toByteArray(Charsets.UTF_8))
        for ((name, value) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line("")
            line(value)
        }
        line("--$boundary")
        val disposition = StringBuilder("Content-Disposition: form-data; name=\"file\"")
        if (fileName != null) disposition.append("; filename=\"$fileName\"")
        line(disposition.toString())
        line("Content-Type: application/octet-stream")
        line("")
        body.write(file)
        line("")
        line("--$boundary--")

        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/$bucket"))
                .header("Host", host)
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build()
        val response = client.send(request, BodyHandlers.ofByteArray())
        return Answer(response.statusCode(), response.headers(), response.body())
    }

    /**
     * The client half of a form upload: a policy and the signature over it.
     *
     * The second version, which is what `ceph/s3-tests` sends. Written here rather than reused from
     * the server so that the test signs the way a client signs — with `javax.crypto` and nothing
     * of ours in between.
     */
    fun signedPolicy(json: String): List<Pair<String, String>> {
        val policy =
            java.util.Base64
                .getEncoder()
                .encodeToString(json.toByteArray(Charsets.UTF_8))
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature =
            java.util.Base64
                .getEncoder()
                .encodeToString(mac.doFinal(policy.toByteArray(Charsets.UTF_8)))
        return listOf("AWSAccessKeyId" to ACCESS_KEY, "policy" to policy, "signature" to signature)
    }

    fun createBucket(
        bucket: String,
        headers: List<Pair<String, String>> = emptyList(),
    ): Answer = send("PUT", "/$bucket", headers = headers)

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

        /** A second key, for the half of the access model that needs somebody else (M27). */
        const val OTHER_ACCESS_KEY = "bochkaother"

        const val OTHER_SECRET = "bochkaothersecret"
        const val REGION = "us-east-1"
    }
}
