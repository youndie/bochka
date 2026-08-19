package io.github.youndie.bochka.s3

/**
 * What a tag set may be.
 *
 * The limits are prose rather than model: `s3-service-2.json` types `Key` and `Value` as plain
 * strings, and the numbers live in the documentation of the operations that take them —
 * `PutObjectTagging` (`:1463`) says "Amazon S3 limits the maximum number of tags to 10 tags per
 * object", and the tag restrictions page gives 128 characters for a key and 256 for a value.
 *
 * **In one place because three operations take tags and all three have to agree**: the XML body of
 * `PutObjectTagging` and `PutBucketTagging`, and the `x-amz-tagging` header of an upload. Until
 * M-155 only the first two counted them, and only as a *count*, and the answer was `MalformedXML`
 * — which sends the caller to look at their XML writer for a value they chose themselves.
 *
 * Characters and not bytes, because that is what the restriction says. The difference shows up
 * only outside ASCII, which is to say only where nobody tests.
 */
object TagRules {
    /** Ten per object (`s3-service-2.json:1463`, prose). */
    const val MAX_TAGS: Int = 10

    const val MAX_KEY_CHARACTERS: Int = 128

    const val MAX_VALUE_CHARACTERS: Int = 256

    enum class Rejection(
        val message: String,
    ) {
        TOO_MANY("a tag set holds at most $MAX_TAGS tags"),
        KEY_TOO_LONG("a tag key holds at most $MAX_KEY_CHARACTERS characters"),
        VALUE_TOO_LONG("a tag value holds at most $MAX_VALUE_CHARACTERS characters"),
        EMPTY_KEY("a tag key may not be empty"),
    }

    fun check(tags: Map<String, String>): Rejection? {
        if (tags.size > MAX_TAGS) return Rejection.TOO_MANY
        for ((key, value) in tags) {
            if (key.isEmpty()) return Rejection.EMPTY_KEY
            if (characters(key) > MAX_KEY_CHARACTERS) return Rejection.KEY_TOO_LONG
            if (characters(value) > MAX_VALUE_CHARACTERS) return Rejection.VALUE_TOO_LONG
        }
        return null
    }

    private fun characters(text: String): Int = text.codePointCount(0, text.length)
}
