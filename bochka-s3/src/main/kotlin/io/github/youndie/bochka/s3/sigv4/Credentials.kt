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
    scopes: Map<String, KeyScope> = emptyMap(),
) {
    private val keys: Map<String, String> = keys.toMap()

    /**
     * What each key may do, for the keys that are narrowed at all.
     *
     * A key absent from here keeps everything: scopes only ever narrow, so configuration written
     * wrong cannot lock an operator out of their own store.
     */
    private val scopes: Map<String, KeyScope> = scopes.toMap()

    init {
        require(keys.isNotEmpty()) { "at least one access key is required" }
        require(keys.keys.none { it.isBlank() }) { "an access key id cannot be blank" }
        require(keys.values.none { it.isBlank() }) { "a secret cannot be blank" }
    }

    val ids: Set<String> get() = keys.keys

    /** `null` when the key is unknown — the caller answers [S3Error.INVALID_ACCESS_KEY_ID]. */
    fun secretFor(accessKeyId: String): String? = keys[accessKeyId]

    /** Unrestricted for a key nobody narrowed, which is every key by default. */
    fun scopeFor(accessKeyId: String): KeyScope = scopes[accessKeyId] ?: UNRESTRICTED

    companion object {
        private val UNRESTRICTED = KeyScope()

        fun of(vararg pairs: Pair<String, String>): Credentials = Credentials(pairs.toMap())
    }
}
