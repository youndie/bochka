package io.github.youndie.bochka.embedded

import io.minio.BucketExistsArgs
import io.minio.CopyObjectArgs
import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.Http
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.SourceObject
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The embedded server against somebody else's S3 client, in the same JVM (M-230).
 *
 * Project rule 3 says the oracle is a foreign client, and `ci/live-clients.sh` obeys it with four
 * containers — `aws-cli`, `boto3`, `mc`, `rclone`. None of them can be the client of **this**
 * module: what `bochka-embedded` sells is "a real S3 endpoint inside your test", and on the JVM the
 * thing on the other side of that endpoint is a library in the same process. So the module named in
 * the README as the niche was, until this file, the one module the rule was never applied to.
 *
 * `io.minio:minio` rather than the AWS SDK for two reasons and one non-reason. It signs and frames
 * its own way — a body it sends in a shape this server parses differently is a defect of the
 * server, not of the test, which is the entire value of a foreign client. It is also the client
 * a JVM test reaches for when it wants an S3 store, which is the population this module lives in.
 * The non-reason: this is **not** MinIO-the-server being treated as the standard. That server
 * rejects keys S3 accepts and the project says so in as many words; its client is a different
 * artefact with a different job.
 *
 * Deliberately not covered here: anything `bochka-app` already drives end to end over a socket.
 * A second copy of those assertions would grow the suite without asking a new question.
 */
class MinioClientTest {
    private fun client(bochka: Bochka): MinioClient =
        MinioClient
            .builder()
            .endpoint(bochka.endpoint)
            .credentials(bochka.accessKeyId, bochka.secretKey)
            .region(bochka.region)
            .build()

    private fun MinioClient.putBytes(
        bucket: String,
        key: String,
        content: ByteArray,
        contentType: String? = null,
    ) = putObject(
        PutObjectArgs
            .builder()
            .bucket(bucket)
            .`object`(key)
            .stream(ByteArrayInputStream(content), content.size.toLong(), null)
            .apply { if (contentType != null) contentType(contentType) }
            .build(),
    )

    private fun MinioClient.readBytes(
        bucket: String,
        key: String,
    ): ByteArray =
        getObject(
            GetObjectArgs
                .builder()
                .bucket(bucket)
                .`object`(key)
                .build(),
        ).use { it.readBytes() }

    @Test
    fun `a bucket the client makes is a bucket the store has`() {
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())

            assertTrue(minio.bucketExists(BucketExistsArgs.builder().bucket("photos").build()))
            assertEquals(listOf("photos"), bochka.bucketNames)
            assertTrue(
                !minio.bucketExists(BucketExistsArgs.builder().bucket("absent").build()),
                "a bucket nobody made must not exist",
            )
        }
    }

    @Test
    fun `an object arrives byte for byte with the length declared`() {
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            // Non-ASCII on purpose: the bytes travel as a body, but the key travels through the
            // signature, and a key that only holds ASCII proves neither.
            val content = "первый объект, и он приехал целиком".toByteArray()

            minio.putBytes("photos", "письма/а.txt", content, contentType = "text/plain")

            assertContentEquals(content, minio.readBytes("photos", "письма/а.txt"))
            assertEquals(1, bochka.objectCount)
        }
    }

    @Test
    fun `an object arrives byte for byte with the length unknown`() {
        // The interesting half of PutObject, and the one a test with a byte array never reaches:
        // told `null` for the size, the client cannot set `Content-Length` from what it was
        // handed, so it frames the body its own way. Which framing that is, is the client's
        // business — that it is understood is this server's.
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            val content = ByteArray(300_000) { (it % 251).toByte() }

            minio.putObject(
                PutObjectArgs
                    .builder()
                    .bucket("photos")
                    .`object`("stream.bin")
                    // The part size has to be named when the length is not: the client has to
                    // know how much it may buffer before it can promise anything about a body it
                    // has not seen the end of.
                    .stream(ByteArrayInputStream(content), null, 5L * 1024 * 1024)
                    .build(),
            )

            assertContentEquals(content, minio.readBytes("photos", "stream.bin"))
        }
    }

    @Test
    fun `stat names the size and the content type`() {
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            val content = "a small thing".toByteArray()
            minio.putBytes("photos", "a.txt", content, contentType = "text/plain")

            val stat =
                minio.statObject(
                    StatObjectArgs
                        .builder()
                        .bucket("photos")
                        .`object`("a.txt")
                        .build(),
                )

            assertEquals(content.size.toLong(), stat.size())
            assertEquals("text/plain", stat.contentType())
            assertTrue(stat.etag().isNotEmpty(), "an ETag is what a client caches on")
        }
    }

    @Test
    fun `a listing filtered by prefix carries what the prefix names`() {
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            minio.putBytes("photos", "letters/a.txt", "a".toByteArray())
            minio.putBytes("photos", "letters/b.txt", "bb".toByteArray())
            minio.putBytes("photos", "numbers/1.txt", "1".toByteArray())

            val listed =
                minio
                    .listObjects(
                        ListObjectsArgs
                            .builder()
                            .bucket("photos")
                            .prefix("letters/")
                            .recursive(true)
                            .build(),
                    ).map { it.get() }

            assertEquals(listOf("letters/a.txt", "letters/b.txt"), listed.map { it.objectName() })
            assertEquals(listOf(1L, 2L), listed.map { it.size() }, "the sizes come from the listing, not from stat")
        }
    }

    @Test
    fun `a presigned PUT with the content type in the query is accepted, and a presigned GET reads it back`() {
        // The presigned pair is where a client is most on its own: the URL it builds is signed
        // without the server ever seeing the request that produced it, and one query parameter
        // signed differently is a `403` nobody can debug from either side. `ci/s3kn.sh` asks this
        // question of a Kotlin/Native client; nothing asked it of a JVM one.
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            val content = "signed elsewhere, uploaded here".toByteArray()

            val upload =
                minio.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs
                        .builder()
                        .method(Http.Method.PUT)
                        .bucket("photos")
                        .`object`("presigned.txt")
                        .expiry(60)
                        .extraQueryParams(mapOf("Content-Type" to "text/plain"))
                        .build(),
                )
            val put =
                HttpClient.newHttpClient().send(
                    HttpRequest
                        .newBuilder(URI.create(upload))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                        .header("Content-Type", "text/plain")
                        .build(),
                    BodyHandlers.ofString(),
                )
            assertEquals(200, put.statusCode(), put.body())

            val download =
                minio.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs
                        .builder()
                        .method(Http.Method.GET)
                        .bucket("photos")
                        .`object`("presigned.txt")
                        .expiry(60)
                        .build(),
                )
            val got =
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(download)).build(),
                    BodyHandlers.ofByteArray(),
                )
            assertEquals(200, got.statusCode())
            assertContentEquals(content, got.body())
        }
    }

    @Test
    fun `a copy leaves two objects holding the same bytes`() {
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            val content = "copied".toByteArray()
            minio.putBytes("photos", "source.txt", content, contentType = "text/plain")

            minio.copyObject(
                CopyObjectArgs
                    .builder()
                    .bucket("photos")
                    .`object`("target.txt")
                    .source(
                        SourceObject
                            .builder()
                            .bucket("photos")
                            .`object`("source.txt")
                            .build(),
                    ).build(),
            )

            assertContentEquals(content, minio.readBytes("photos", "target.txt"))
            assertEquals(2, bochka.objectCount, "a copy is a second object, not a second name")
            assertEquals(
                minio
                    .statObject(
                        StatObjectArgs
                            .builder()
                            .bucket("photos")
                            .`object`("source.txt")
                            .build(),
                    ).etag(),
                minio
                    .statObject(
                        StatObjectArgs
                            .builder()
                            .bucket("photos")
                            .`object`("target.txt")
                            .build(),
                    ).etag(),
                "same bytes, same ETag",
            )
        }
    }

    @Test
    fun `a removed object is gone, and asking for it says which half is missing`() {
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            minio.putBytes("photos", "a.txt", "here for a moment".toByteArray())

            minio.removeObject(
                RemoveObjectArgs
                    .builder()
                    .bucket("photos")
                    .`object`("a.txt")
                    .build(),
            )

            assertEquals(0, bochka.objectCount)
            // `NoSuchKey` rather than `NoSuchBucket`: the bucket is still there, and a client that
            // is told otherwise re-creates it (project rule — a refusal confirms the name).
            val refusal =
                assertFailsWith<ErrorResponseException> {
                    minio.statObject(
                        StatObjectArgs
                            .builder()
                            .bucket("photos")
                            .`object`("a.txt")
                            .build(),
                    )
                }
            assertEquals("NoSuchKey", refusal.errorResponse().code())
        }
    }

    @Test
    fun `an object staged without HTTP is an object the client can read`() {
        // `Bochka.put` exists so a test can start from state instead of from ten client calls
        // (M-98). What makes it worth having is that the two paths meet: bytes put in through the
        // back door must come out of the front one.
        Bochka.start().use { bochka ->
            bochka.put("fixtures", "hello.txt", "привет".toByteArray(), contentType = "text/plain")
            val minio = client(bochka)

            val stat =
                minio.statObject(
                    StatObjectArgs
                        .builder()
                        .bucket("fixtures")
                        .`object`("hello.txt")
                        .build(),
                )

            assertEquals("text/plain", stat.contentType())
            assertContentEquals("привет".toByteArray(), minio.readBytes("fixtures", "hello.txt"))
        }
    }

    @Test
    fun `reset empties the store under a client that keeps talking to the same endpoint`() {
        // The reason `reset` exists at all is that the client is built once and the tests are
        // many. A reset that forced a new endpoint would make that promise false.
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            minio.putBytes("photos", "a.txt", "before".toByteArray())

            bochka.reset()

            assertTrue(
                !minio.bucketExists(BucketExistsArgs.builder().bucket("photos").build()),
                "the bucket went with the reset",
            )
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            minio.putBytes("photos", "a.txt", "after".toByteArray())
            assertContentEquals("after".toByteArray(), minio.readBytes("photos", "a.txt"))
        }
    }

    @Test
    fun `a single injected failure never reaches the caller, because the client retries it away`() {
        // M-231, and it is the client half of what `failNext` is for. `times` counts **requests**,
        // and a client with retries makes more of them than the test made calls: minio 9.x retries
        // 408, 429, 499, 500, 502, 503, 504 and 520 five times over. So the one shape everybody
        // writes first — order one 503, call once, expect to handle it — is a test that handles
        // nothing and says so by passing.
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            minio.putBytes("photos", "a.txt", "still here".toByteArray())

            bochka.failNext(503, times = 1)

            val stat =
                minio.statObject(
                    StatObjectArgs
                        .builder()
                        .bucket("photos")
                        .`object`("a.txt")
                        .build(),
                )
            assertEquals(10L, stat.size(), "the retry landed on the healthy server, and nothing was raised")
        }
    }

    @Test
    fun `a failure that outlasts the retries surfaces, and it surfaces by its code`() {
        // The same order with a number that covers the retries. Slow on purpose — the client backs
        // off between attempts — and worth the seconds: this is the only shape in which a test can
        // claim it saw the failure.
        //
        // Asked through GET rather than through `statObject`, and that is the difference between
        // reading the server's answer and reading the client's guess: a HEAD carries no body, so
        // this client raises a `ServerException` holding the status alone, and for a 4xx it
        // makes the code up from the status. Only a request with a body proves whose code it is.
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())
            minio.putBytes("photos", "a.txt", "unreachable for a while".toByteArray())

            bochka.failNext(503, times = 10)

            val refusal = assertFailsWith<ErrorResponseException> { minio.readBytes("photos", "a.txt") }
            assertEquals("ServiceUnavailable", refusal.errorResponse().code())
        }
    }

    @Test
    fun `an injected refusal keeps its own code instead of arriving as a server fault`() {
        // The other half of M-231. `403` is not in the client's retry set, so one is enough — and
        // until the double had a table of codes, it answered `InternalError` to everything that
        // was not a 503. A test telling "denied" from "broken" could not be written on that: both
        // arrive as the same code, and the one that retries is the wrong one.
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("photos").build())

            bochka.failNext(403, times = 1)

            val refusal = assertFailsWith<ErrorResponseException> { minio.readBytes("photos", "a.txt") }
            assertEquals("AccessDenied", refusal.errorResponse().code())
        }
    }

    @Test
    fun `a multipart upload the client cut into three parts comes back whole`() {
        // The half of the client's work that a small body never exercises: over the part size it
        // stops sending a PUT and runs the four-call dance itself — create, three uploads,
        // complete — with its own part boundaries and its own idea of what completes an upload.
        //
        // Checked by digest rather than by holding the bytes: the test JVM runs under `-Xmx64M`
        // (root build file), and an assertion that needs 11 MiB twice over would be measuring the
        // heap profile instead of the server.
        Bochka.start().use { bochka ->
            val minio = client(bochka)
            minio.makeBucket(MakeBucketArgs.builder().bucket("big").build())
            val partSize = 5L * 1024 * 1024
            val total = 11L * 1024 * 1024

            val written =
                minio.putObject(
                    PutObjectArgs
                        .builder()
                        .bucket("big")
                        .`object`("large.bin")
                        .stream(pattern(total), total, partSize)
                        .build(),
                )

            // `-3`, and the number is the point: the suffix says how many parts the ETag was
            // computed over (docs/api/protocol-s3.md:614), so a server that quietly turned three
            // parts into one object with a plain MD5 would be caught here rather than by a client
            // that later asks for part 2.
            // Unquoted before the comparison, and that is the server being right rather than the
            // test being lenient: `CompleteMultipartUploadResult` carries the ETag **quoted**
            // inside the XML element, exactly as S3 does, and this client hands the element
            // through untouched where it strips the quotes off the header.
            val etag = written.etag().trim('"')
            assertTrue(etag.endsWith("-3"), "an eleven-mebibyte body in five-mebibyte parts: $etag")
            val stat =
                minio.statObject(
                    StatObjectArgs
                        .builder()
                        .bucket("big")
                        .`object`("large.bin")
                        .build(),
                )
            assertEquals(total, stat.size())
            assertEquals(
                digestOf(pattern(total)),
                getObject(minio, "big", "large.bin"),
                "the parts were reassembled in order and without a byte between them",
            )
        }
    }

    /** Eleven mebibytes nobody has to hold: the same bytes every time, produced as they are read. */
    private fun pattern(size: Long): InputStream =
        object : InputStream() {
            private var position = 0L

            override fun read(): Int {
                if (position >= size) return -1
                return ((position++ % 251L).toInt()) and 0xff
            }

            override fun read(
                destination: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (position >= size) return -1
                val count = minOf(length.toLong(), size - position).toInt()
                for (index in 0 until count) destination[offset + index] = ((position + index) % 251L).toByte()
                position += count
                return count
            }
        }

    private fun digestOf(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getObject(
        minio: MinioClient,
        bucket: String,
        key: String,
    ): String =
        minio
            .getObject(
                GetObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(key)
                    .build(),
            ).use { digestOf(it) }
}
