package io.github.youndie.bochka.s3.sigv4

/**
 * The access keys the server knows.
 *
 * A static list, decided at startup, the way the neighbouring broker fixes its partitions: no IAM,
 * no policies, no rotation at runtime (research, Открытый вопрос 4).
 *
 * **More than one from the first day, and that is a requirement rather than generosity.**
 * `ceph/s3-tests` needs "the location of the service and two different credentials", so a server
 * built around a single key cannot run half the suite it is going to be measured by — and the
 * suite is the measure (Р6).
 */
class Credentials(
    keys: Map<String, String>,
) {
    private val keys: Map<String, String> = keys.toMap()

    init {
        require(keys.isNotEmpty()) { "at least one access key is required" }
        require(keys.keys.none { it.isBlank() }) { "an access key id cannot be blank" }
        require(keys.values.none { it.isBlank() }) { "a secret cannot be blank" }
    }

    val ids: Set<String> get() = keys.keys

    /** `null` when the key is unknown — the caller answers [S3Error.INVALID_ACCESS_KEY_ID]. */
    fun secretFor(accessKeyId: String): String? = keys[accessKeyId]

    companion object {
        fun of(vararg pairs: Pair<String, String>): Credentials = Credentials(pairs.toMap())
    }
}
