package io.github.youndie.bochka.s3.sigv4

/**
 * The chain of signatures that runs down an `aws-chunked` body.
 *
 * Each chunk is signed over the signature of the one before it, and the first is signed over the
 * signature of the request itself — so the body cannot be reordered, truncated or spliced from
 * another upload without the chain coming apart. The seed is
 * [SignatureVerifier.Result.Ok]'s signature, which is why the body path cannot start before the
 * headers are verified.
 *
 * Both string-to-sign forms are here because they are **not the same shape**, and that is the trap:
 *
 * ```
 * AWS4-HMAC-SHA256-PAYLOAD     AWS4-HMAC-SHA256-TRAILER
 * <timestamp>                  <timestamp>
 * <scope>                      <scope>
 * <previous signature>         <previous signature>
 * <sha256 of empty string>     <sha256 of the trailer bytes>
 * <sha256 of the chunk>
 * ```
 *
 * Five lines against four: the trailer form has no empty-payload line. Read from
 * `minio/minio`, `cmd/streaming-signature-v4.go:54-94` — `getChunkSignature` and
 * `getTrailerChunkSignature`. Writing the trailer with five lines produces a mismatch on the very
 * last frame of an upload that otherwise went perfectly, which is the most expensive place to be
 * wrong.
 */
class ChunkSigning(
    private val secret: String,
    private val timestamp: String,
    private val date: String,
    private val region: String,
    seedSignature: String,
) {
    /** The signature of the previous frame; the seed for the first one. */
    var previousSignature: String = seedSignature
        private set

    private val signingKey: ByteArray = Sigv4.signingKey(secret, date, region, SERVICE)
    private val scope: String get() = "$date/$region/$SERVICE/aws4_request"

    fun chunkSignature(chunkSha256Hex: String): String =
        Sigv4.signature(
            signingKey,
            buildString {
                append(CHUNK_ALGORITHM).append('\n')
                append(timestamp).append('\n')
                append(scope).append('\n')
                append(previousSignature).append('\n')
                append(Sigv4.EMPTY_PAYLOAD_SHA256).append('\n')
                append(chunkSha256Hex)
            },
        )

    fun trailerSignature(trailerSha256Hex: String): String =
        Sigv4.signature(
            signingKey,
            buildString {
                append(TRAILER_ALGORITHM).append('\n')
                append(timestamp).append('\n')
                append(scope).append('\n')
                append(previousSignature).append('\n')
                append(trailerSha256Hex)
            },
        )

    fun accept(signature: String) {
        previousSignature = signature
    }

    companion object {
        const val CHUNK_ALGORITHM: String = "AWS4-HMAC-SHA256-PAYLOAD"
        const val TRAILER_ALGORITHM: String = "AWS4-HMAC-SHA256-TRAILER"
        private const val SERVICE = "s3"
    }
}
