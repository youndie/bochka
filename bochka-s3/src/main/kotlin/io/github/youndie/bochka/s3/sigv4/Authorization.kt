package io.github.youndie.bochka.s3.sigv4

/**
 * The `Authorization` header a client sends, taken apart.
 *
 * ```
 * AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/s3/aws4_request,
 *   SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=<hex>
 * ```
 *
 * `docs/spec/reference/botocore-auth.py:445`.
 *
 * This is the first thing a request touches, before anything is authenticated, so it parses
 * defensively: every failure is [Malformed] with a reason, and nothing here can throw something the
 * caller has not been told about. The reason is for the log — what goes back on the wire is
 * `AuthorizationHeaderMalformed`, because telling an unauthenticated caller *which* part they got
 * wrong is free reconnaissance.
 */
data class Authorization(
    val accessKeyId: String,
    val date: String,
    val region: String,
    val service: String,
    val signedHeaders: List<String>,
    val signature: String,
) {
    /** `<date>/<region>/<service>/aws4_request`, the third line of the string to sign. */
    val scope: String get() = "$date/$region/$service/aws4_request"

    class Malformed(
        message: String,
    ) : IllegalArgumentException(message)

    companion object {
        private const val PREFIX = "${Sigv4.ALGORITHM} "

        fun parse(header: String): Authorization {
            if (!header.startsWith(PREFIX)) throw Malformed("not $PREFIX")

            val parts = HashMap<String, String>(3)
            for (chunk in header.removePrefix(PREFIX).split(',')) {
                val trimmed = chunk.trim()
                if (trimmed.isEmpty()) continue
                val eq = trimmed.indexOf('=')
                if (eq <= 0) throw Malformed("component without '=': '$trimmed'")
                parts[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
            }

            val credential = parts["Credential"] ?: throw Malformed("no Credential")
            val signedHeaders = parts["SignedHeaders"] ?: throw Malformed("no SignedHeaders")
            val signature = parts["Signature"] ?: throw Malformed("no Signature")

            // <access key>/<date>/<region>/<service>/aws4_request — and the access key is the one
            // field that may itself contain a slash on some deployments, so the scope is taken from
            // the end rather than the whole thing split into five.
            val slash = credential.indexOf('/')
            if (slash <= 0) throw Malformed("Credential is not <key>/<scope>")
            val accessKeyId = credential.substring(0, slash)
            val scope = credential.substring(slash + 1).split('/')
            if (scope.size != 4) throw Malformed("scope is not <date>/<region>/<service>/aws4_request")
            if (scope[3] != "aws4_request") throw Malformed("scope does not end in aws4_request")
            if (scope[0].length != 8 || scope[0].any { !it.isDigit() }) throw Malformed("scope date is not yyyyMMdd")

            val names = signedHeaders.split(';').filter { it.isNotEmpty() }
            if (names.isEmpty()) throw Malformed("SignedHeaders is empty")
            // Lower case and sorted is not a nicety — the canonical request is built in this order,
            // so a client sending them unsorted would sign a different string than we rebuild. It
            // is their error, and it has to be named as one rather than produce a mismatch.
            if (names != names.map { it.lowercase() }) throw Malformed("SignedHeaders must be lower case")
            if (names != names.sorted()) throw Malformed("SignedHeaders must be sorted")
            if (SIGNED_HEADER_HOST !in names) throw Malformed("SignedHeaders must include host")

            if (signature.length != 64 || signature.any { it !in "0123456789abcdefABCDEF" }) {
                throw Malformed("Signature is not 64 hex characters")
            }

            return Authorization(accessKeyId, scope[0], scope[1], scope[2], names, signature.lowercase())
        }

        private const val SIGNED_HEADER_HOST = "host"
    }
}
