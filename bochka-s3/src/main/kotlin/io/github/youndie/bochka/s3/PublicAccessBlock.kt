package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.S3Error
import io.github.youndie.bochka.s3.xml.XmlReader
import io.github.youndie.bochka.s3.xml.XmlWriter
import java.nio.charset.StandardCharsets

/**
 * `PublicAccessBlockConfiguration`: four switches that say "do not let this bucket become public"
 * (M-227).
 *
 * Shapes are `docs/spec/s3-service-2.json`: `PutPublicAccessBlock` (`PUT /{Bucket}?publicAccessBlock`,
 * no `responseCode`, so **200**), `GetPublicAccessBlock` and `DeletePublicAccessBlock` (whose
 * `responseCode` **is** 204). The four members and what each one does come from the same file's
 * documentation of `PublicAccessBlockConfiguration`, and they split two and two:
 *
 * * [blockPublicAcls] and [blockPublicPolicy] change what the bucket **accepts**. A public canned
 *   ACL and a policy granting to `*` are refused as they arrive, and neither touches what is
 *   already stored — "enabling this setting doesn't affect existing policies or ACLs";
 * * [ignorePublicAcls] and [restrictPublicBuckets] change what an already-public bucket
 *   **answers**. The document stays exactly as it was — `GetBucketAcl` and `GetBucketPolicy` still
 *   report it — and the access decision stops reading it as a grant.
 *
 * **All four are enforced, which is why all four are stored.** A flag written down and not applied
 * is the failure this repository refuses everywhere else (§3.6, §3.8): its author reads the bucket
 * as locked and finds out otherwise through a leak rather than through an error. There is no
 * account-level configuration here to combine with — S3 takes the most restrictive of bucket and
 * account, and this server has one level.
 */
object PublicAccessBlock {
    class Refused(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    /**
     * The four settings, each defaulting to `false` because an absent member is an absent
     * restriction: botocore sends only what its caller passed, and `Setting` has no default of its
     * own in the model.
     */
    data class Configuration(
        val blockPublicAcls: Boolean = false,
        val ignorePublicAcls: Boolean = false,
        val blockPublicPolicy: Boolean = false,
        val restrictPublicBuckets: Boolean = false,
    )

    /** The name this configuration is stored under, and the query parameter it arrives on. */
    const val NAME = "publicAccessBlock"

    private val MEMBERS =
        setOf("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets")

    /**
     * The document, or a [Refused] naming what could not be read.
     *
     * **This reader refuses an element it does not know, and it is the one in this codebase that
     * does.** [XmlReader] documents the opposite rule — skip the unknown, because S3 request bodies
     * grow members and a client sending a newer one should not be broken — and that rule is right
     * for a listing or a lifecycle rule. It is wrong here, because every member of this shape is a
     * restriction: skipping one silently means the caller asked for a bucket to be locked in a way
     * it is not. `TargetGrants` in [BucketLogging] is refused for the same reason and only when it
     * actually carries something.
     */
    fun decode(body: ByteArray): Configuration {
        val settings = HashMap<String, Boolean>()
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("PublicAccessBlockConfiguration") { field ->
            if (field !in MEMBERS) {
                throw Refused(
                    S3Error.MALFORMED_XML,
                    "'$field' is not a member of PublicAccessBlockConfiguration, and this server " +
                        "does not store a restriction it cannot apply",
                )
            }
            val text = reader.textOf(field).trim()
            settings[field] =
                when (text) {
                    "true" -> true
                    "false" -> false
                    else -> throw Refused(S3Error.MALFORMED_XML, "'$text' is not a value of $field")
                }
        }
        return Configuration(
            blockPublicAcls = settings["BlockPublicAcls"] ?: false,
            ignorePublicAcls = settings["IgnorePublicAcls"] ?: false,
            blockPublicPolicy = settings["BlockPublicPolicy"] ?: false,
            restrictPublicBuckets = settings["RestrictPublicBuckets"] ?: false,
        )
    }

    /**
     * The answer to `GetPublicAccessBlock`, and **all four members are written** even where the
     * caller set none of them.
     *
     * botocore turns an absent member into an absent key rather than into `False`, so a document
     * that leaves out what is off answers `KeyError` to every one of the four assertions in
     * `test_put_public_block:14277`. The same shape as `TargetObjectKeyFormat` in bucket logging:
     * a default here is a value, not an absence.
     */
    fun encode(configuration: Configuration): ByteArray =
        XmlWriter(256).document("PublicAccessBlockConfiguration") {
            text("BlockPublicAcls", configuration.blockPublicAcls)
            text("IgnorePublicAcls", configuration.ignorePublicAcls)
            text("BlockPublicPolicy", configuration.blockPublicPolicy)
            text("RestrictPublicBuckets", configuration.restrictPublicBuckets)
        }
}
