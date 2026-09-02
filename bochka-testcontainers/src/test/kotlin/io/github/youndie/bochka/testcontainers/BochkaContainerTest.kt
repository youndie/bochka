package io.github.youndie.bochka.testcontainers

import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The container, started for real and spoken to by a client that is not ours.
 *
 * Run by `./gradlew :bochka-testcontainers:containerTest -Pbochka.image=…`, and by the CI job that
 * builds the image, so that a pull request is checked against the image it would produce rather
 * than against the one published last month. It is **excluded** from the ordinary test task rather
 * than skipped inside it: a test that returns on its first line looks exactly like one that passed.
 *
 * What is under test here is the wiring — the port, the credentials, the readiness the container
 * waits for — and not the server, which the rest of the repository checks at length. So the round
 * trip is the smallest one that touches all three: make a bucket, put an object, read it back.
 */
class BochkaContainerTest {
    @Test
    fun `a container comes up and answers a client that is not ours`() {
        val image = System.getenv("BOCHKA_IMAGE")
        require(!image.isNullOrBlank()) { "BOCHKA_IMAGE names the image under test; the task sets it" }

        BochkaContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ghcr.io/youndie/bochka"))
            .withCredentials("containerkey", "containersecret")
            .use { container ->
                container.start()

                val client =
                    MinioClient
                        .builder()
                        .endpoint(container.endpoint)
                        .credentials(container.accessKeyId, container.secretKey)
                        .build()

                client.makeBucket(MakeBucketArgs.builder().bucket("reports").build())
                assertTrue(
                    client.bucketExists(BucketExistsArgs.builder().bucket("reports").build()),
                    "the bucket was made and the container does not admit to it",
                )

                val body = "one report".toByteArray()
                client.putObject(
                    PutObjectArgs
                        .builder()
                        .bucket("reports")
                        .`object`("q3.txt")
                        .stream(ByteArrayInputStream(body), body.size.toLong(), -1)
                        .build(),
                )

                val back =
                    client
                        .getObject(
                            GetObjectArgs
                                .builder()
                                .bucket("reports")
                                .`object`("q3.txt")
                                .build(),
                        ).use { it.readBytes() }
                assertEquals(String(body), String(back), "what came back is not what went in")

                // The credentials this container was told to use are the ones it enforces. Without
                // this the test would pass against an image that accepts anybody, and "the wiring
                // works" is exactly the claim being made.
                val stranger =
                    MinioClient
                        .builder()
                        .endpoint(container.endpoint)
                        .credentials("someoneelse", "wrongsecret")
                        .build()
                val refused =
                    runCatching {
                        stranger.bucketExists(BucketExistsArgs.builder().bucket("reports").build())
                    }.exceptionOrNull()
                assertTrue(
                    refused != null,
                    "a client with the wrong key was allowed in: the container did not carry its credentials",
                )
            }
    }
}
