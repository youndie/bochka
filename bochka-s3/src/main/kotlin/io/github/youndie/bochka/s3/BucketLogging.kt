package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.S3Error
import io.github.youndie.bochka.s3.xml.XmlReader
import io.github.youndie.bochka.s3.xml.XmlWriter
import java.nio.charset.StandardCharsets

/**
 * `BucketLoggingStatus`: where a bucket's access log goes, and whether it goes anywhere (M-202).
 *
 * Shapes are `docs/spec/s3-service-2.json`: `BucketLoggingStatus` (`:2183`) holds an optional
 * `LoggingEnabled` (`:9023`), which requires `TargetBucket` and `TargetPrefix` and may carry a
 * `TargetObjectKeyFormat` (`:13342`) — `SimplePrefix` or `PartitionedPrefix` with a
 * `PartitionDateSource`. An empty status is how logging is switched **off**: there is no
 * `DeleteBucketLogging`.
 *
 * **What this milestone is, and what it is not.** The configuration is here; the delivery of
 * records is not. That split came from the suite's own markers rather than from convenience:
 * 33 of the 39 failing cases in this family are `@pytest.mark.fails_on_aws`, and 27 of those are
 * `fails_without_logging_rollover` — they pin RGW's journal and its roll timing, which is a ceph
 * extension rather than S3. The six that are not describe exactly this: setting the configuration,
 * reading it back, removing it, and who is allowed to.
 *
 * `TargetGrants` is refused by name for the reason grants are refused everywhere here: it names
 * users, and this server has access keys (§3.6).
 */
object BucketLogging {
    class Refused(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    /** How the name of a delivered log object is built. Only the format is stored; nothing delivers yet. */
    sealed interface KeyFormat {
        data object Simple : KeyFormat

        data class Partitioned(
            val dateSource: String,
        ) : KeyFormat
    }

    data class Enabled(
        val targetBucket: String,
        val targetPrefix: String,
        val keyFormat: KeyFormat = KeyFormat.Simple,
    )

    /** The two values `PartitionDateSource` may take; anything else is `InvalidArgument`. */
    val DATE_SOURCES = setOf("DeliveryTime", "EventTime")

    /** The name this configuration is stored under, and the query parameter it arrives on. */
    const val NAME = "logging"

    /**
     * The document, or `null` when it carries no `LoggingEnabled` — which is the request to switch
     * logging off rather than a malformed one.
     */
    fun decode(body: ByteArray): Enabled? {
        var targetBucket: String? = null
        var targetPrefix: String? = null
        var keyFormat: KeyFormat = KeyFormat.Simple
        var enabledSeen = false
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("BucketLoggingStatus") { name ->
            if (name != "LoggingEnabled") return@root
            enabledSeen = true
            reader.children { field ->
                when (field) {
                    "TargetBucket" -> {
                        targetBucket = reader.textOf(field).trim()
                    }

                    "TargetPrefix" -> {
                        targetPrefix = reader.textOf(field)
                    }

                    "TargetGrants" -> {
                        // Refused only when it **carries** a grant. botocore writes an empty
                        // `<TargetGrants></TargetGrants>` into every request, so refusing the
                        // element itself refuses the standard client's ordinary shape — the same
                        // mistake the lifecycle reader made with `<Filter/>` (M23).
                        var granted = false
                        reader.children { granted = true }
                        if (granted) {
                            throw Refused(
                                S3Error.NOT_IMPLEMENTED,
                                "TargetGrants names users, and this server has access keys",
                            )
                        }
                    }

                    "TargetObjectKeyFormat" -> {
                        keyFormat = keyFormatOf(reader)
                    }

                    else -> {
                        reader.textOf(field)
                    }
                }
            }
        }
        if (!enabledSeen) return null
        val bucket = targetBucket
        val prefix = targetPrefix
        if (bucket.isNullOrEmpty()) throw Refused(S3Error.MALFORMED_XML, "LoggingEnabled carries no TargetBucket")
        if (prefix == null) throw Refused(S3Error.MALFORMED_XML, "LoggingEnabled carries no TargetPrefix")
        return Enabled(bucket, prefix, keyFormat)
    }

    private fun keyFormatOf(reader: XmlReader): KeyFormat {
        var format: KeyFormat = KeyFormat.Simple
        reader.children { shape ->
            when (shape) {
                "SimplePrefix" -> {
                    reader.children { }
                    format = KeyFormat.Simple
                }

                "PartitionedPrefix" -> {
                    var source = "EventTime"
                    reader.children { field ->
                        if (field == "PartitionDateSource") source = reader.textOf(field).trim()
                    }
                    if (source !in DATE_SOURCES) {
                        // `MalformedXML`, not `InvalidArgument`: the suite asks for the former
                        // (`test_put_bucket_logging_errors:16526`), and it is the truer of the two —
                        // the element holds a value its own grammar does not allow.
                        throw Refused(S3Error.MALFORMED_XML, "'$source' is not a PartitionDateSource")
                    }
                    format = KeyFormat.Partitioned(source)
                }

                else -> {
                    reader.children { }
                }
            }
        }
        return format
    }

    /**
     * The answer to `GetBucketLogging`.
     *
     * `TargetObjectKeyFormat` is written even when nobody asked for one, and that is the suite's
     * requirement rather than a flourish: `test_put_bucket_logging:15528` sets the minimal
     * configuration and then compares the read-back with `{'SimplePrefix': {}}` added — the
     * default is a value, not an absence.
     */
    fun encode(enabled: Enabled?): ByteArray =
        XmlWriter(256).document("BucketLoggingStatus") {
            if (enabled == null) return@document
            element("LoggingEnabled") {
                text("TargetBucket", enabled.targetBucket)
                text("TargetPrefix", enabled.targetPrefix)
                element("TargetObjectKeyFormat") {
                    when (val format = enabled.keyFormat) {
                        is KeyFormat.Simple -> {
                            element("SimplePrefix") {}
                        }

                        is KeyFormat.Partitioned -> {
                            element("PartitionedPrefix") {
                                text("PartitionDateSource", format.dateSource)
                            }
                        }
                    }
                }
            }
        }
}
