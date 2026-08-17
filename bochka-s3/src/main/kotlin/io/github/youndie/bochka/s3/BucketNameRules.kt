package io.github.youndie.bochka.s3

/**
 * What a bucket may be called.
 *
 * The rules are prose, not model: `s3-service-2.json` types `BucketName` as a plain string, so
 * there is nothing in the model to check against and the source is the AWS documentation page
 * "Bucket naming rules" (`docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html`)
 * plus the reference server's `isValidBucketName` (`minio/minio`, `cmd/bucket-handlers.go` →
 * `s3utils.CheckValidBucketName`).
 *
 * Checked at all, rather than accepting whatever arrives, because the name reaches a `Location`
 * header and a virtual-hosted `Host`: a bucket called `a/b` or `..` would produce a URL that means
 * something else. That is also why the rules are the *DNS* ones and not the relaxed US-East-1
 * legacy set — the relaxed set exists for buckets made before 2018 and permits names that cannot
 * be addressed virtual-hosted at all.
 */
object BucketNameRules {
    enum class Rejection(
        val message: String,
    ) {
        TOO_SHORT("bucket names must be at least 3 characters"),
        TOO_LONG("bucket names must be no more than 63 characters"),
        BAD_CHARACTER("bucket names may hold only lowercase letters, digits, hyphens and periods"),
        BAD_EDGE("bucket names must begin and end with a letter or a digit"),
        ADJACENT_PERIODS("bucket names may not hold two adjacent periods"),
        LOOKS_LIKE_AN_ADDRESS("bucket names may not be formatted as an IP address"),
        RESERVED_PREFIX("bucket names may not begin with xn-- or sthree-"),
        RESERVED_SUFFIX("bucket names may not end with -s3alias or --ol-s3"),
    }

    fun check(name: String): Rejection? =
        when {
            name.length < 3 -> Rejection.TOO_SHORT
            name.length > 63 -> Rejection.TOO_LONG
            name.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '.' } -> Rejection.BAD_CHARACTER
            !name.first().isLetterOrDigit() || !name.last().isLetterOrDigit() -> Rejection.BAD_EDGE
            ".." in name -> Rejection.ADJACENT_PERIODS
            looksLikeAnAddress(name) -> Rejection.LOOKS_LIKE_AN_ADDRESS
            RESERVED_PREFIXES.any { name.startsWith(it) } -> Rejection.RESERVED_PREFIX
            RESERVED_SUFFIXES.any { name.endsWith(it) } -> Rejection.RESERVED_SUFFIX
            else -> null
        }

    /**
     * `192.168.5.4` is refused, and `192.168.5.4.5` is not.
     *
     * The rule is about the whole name being an address, because such a name in a virtual-hosted
     * URL is indistinguishable from the host it would be a bucket of.
     */
    private fun looksLikeAnAddress(name: String): Boolean {
        val parts = name.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && part.toInt() <= 255
        }
    }

    private val RESERVED_PREFIXES = listOf("xn--", "sthree-")
    private val RESERVED_SUFFIXES = listOf("-s3alias", "--ol-s3")
}
