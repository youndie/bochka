package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.s3.sigv4.S3Error
import io.github.youndie.bochka.s3.xml.S3Documents
import java.security.SecureRandom

/**
 * Turns a refusal into what goes on the wire.
 *
 * Deliberately not an `HttpResponse`: this module knows nothing about sockets, and the transport
 * type lives on the other side of that boundary (Р8). What comes out is a status, headers and a
 * body — the app puts the three together.
 *
 * Every error carries `x-amz-request-id`, in the header **and** in the body, whether or not there
 * is anything else to say. Client libraries lift that pair into their exceptions unconditionally
 * because without it a support request is not looked at; a server that omits it makes every one of
 * its failures unreportable.
 */
object S3ErrorResponse {
    class Rendered(
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    fun render(
        error: S3Error,
        resource: String,
        requestId: String = newRequestId(),
        key: ObjectKey? = null,
        bucket: String? = null,
        detail: String? = null,
    ): Rendered {
        val body =
            S3Documents.error(
                code = error.code,
                // The detail replaces the stock message only when there is one, and it never
                // explains *why* an unauthenticated caller was refused — which part of a signature
                // was wrong is reconnaissance, and it belongs in the log.
                message = detail ?: error.message,
                resource = resource,
                requestId = requestId,
                key = key,
                bucketName = bucket,
            )
        return Rendered(
            status = error.status,
            headers =
                listOf(
                    "Content-Type" to "application/xml",
                    "x-amz-request-id" to requestId,
                ),
            body = body,
        )
    }

    /**
     * Sixteen hex characters, like the ones S3 hands out. Random rather than sequential: a counter
     * tells anybody who asks twice how many requests the server has served.
     */
    fun newRequestId(): String {
        val bytes = ByteArray(8)
        RANDOM.nextBytes(bytes)
        return buildString(16) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(HEX[v shr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    private val RANDOM = SecureRandom()
    private val HEX = "0123456789ABCDEF".toCharArray()
}
