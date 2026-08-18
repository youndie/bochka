package io.github.youndie.bochka.s3.xml

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.s3.CorsRules
import io.github.youndie.bochka.s3.UriCodec
import java.nio.charset.StandardCharsets

/**
 * The two request bodies S3 takes as XML.
 *
 * Both are bounded by the protocol — a batch delete carries at most 1000 keys, a completion at most
 * 10 000 parts — so they are read whole. The bound is enforced here rather than assumed: this is an
 * unauthenticated-shaped input path, and "the client would not send more" is not a property of the
 * client (Риск 3).
 */
object S3Requests {
    /** `shapes.Delete.members`: `Objects` is flattened as `Object`, plus an optional `Quiet`. */
    data class DeleteRequest(
        val targets: List<Target>,
        val quiet: Boolean,
    )

    /**
     * One entry of a batch delete: a key, and what the client believes about the object under it.
     *
     * `shapes.ObjectIdentifier.members` carries `ETag`, `LastModifiedTime` (`rfc822`) and `Size`
     * beside the key, and they are the same three conditions the single `DELETE` takes as headers.
     * Kept as text here: whether a condition is well formed is a question for the layer that knows
     * what a timestamp is, and answering it in the parser would make an unreadable date a
     * malformed **document**, which fails the whole batch instead of the one key it is about.
     */
    data class Target(
        val key: ObjectKey,
        val eTag: String? = null,
        val lastModifiedTime: String? = null,
        val size: String? = null,
    )

    /** `shapes.CompletedMultipartUpload.members`: `Parts` flattened as `Part`. */
    data class CompletedPart(
        val partNumber: Int,
        val eTag: String,
    )

    /** A batch delete takes at most 1000 objects per request. */
    const val MAX_DELETE_KEYS: Int = 1000

    /** Part numbers run 1..10 000 (`docs/spec/s3-service-2.json:1604`), so a list cannot be longer. */
    const val MAX_PARTS: Int = 10_000

    /**
     * `<Tagging><TagSet><Tag><Key/><Value/>` — `s3-service-2.json:13301`, `:13294`, `:13272`.
     *
     * Возвращается картой, а не списком: ключи тега уникальны, и карта не даёт положить два
     * значения под одним ключом, о чём иначе пришлось бы помнить каждому, кто это читает.
     */
    fun parseTagging(body: ByteArray): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("Tagging") { name ->
            if (name != "TagSet") return@root
            reader.children { entry ->
                if (entry != "Tag") return@children
                var key: String? = null
                var value: String? = null
                reader.children { field ->
                    when (field) {
                        "Key" -> key = reader.textOf(field)
                        "Value" -> value = reader.textOf(field)
                    }
                }
                val k = key ?: throw XmlReader.MalformedXmlException("<Tag> без <Key>")
                tags[k] = value ?: throw XmlReader.MalformedXmlException("<Tag> без <Value>")
            }
        }
        if (tags.size > MAX_TAGS) throw XmlReader.MalformedXmlException("больше $MAX_TAGS тегов")
        return tags
    }

    /**
     * `x-amz-tagging: a=1&b=2` — те же теги, но формой запроса, а не документа
     * (`s3-service-2.json:3158`). Значения процентно закодированы, как в query.
     */
    fun parseTaggingHeader(value: String): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        for (pair in value.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) throw XmlReader.MalformedXmlException("тег без значения: '$pair'")
            tags[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
        }
        if (tags.size > MAX_TAGS) throw XmlReader.MalformedXmlException("больше $MAX_TAGS тегов")
        return tags
    }

    private fun decode(value: String): String = String(UriCodec.decode(value, plusIsSpace = true))

    /** `<CORSConfiguration><CORSRule>…` — `s3-service-2.json:2241`, `:2253`. */
    fun parseCors(body: ByteArray): CorsRules {
        val rules = ArrayList<CorsRules.Rule>()
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("CORSConfiguration") { name ->
            if (name != "CORSRule") return@root
            var id: String? = null
            val methods = ArrayList<String>()
            val origins = ArrayList<String>()
            val allowedHeaders = ArrayList<String>()
            val exposeHeaders = ArrayList<String>()
            var maxAge: Int? = null
            reader.children { field ->
                when (field) {
                    "ID" -> id = reader.textOf(field)
                    "AllowedMethod" -> methods += reader.textOf(field).trim()
                    "AllowedOrigin" -> origins += reader.textOf(field).trim()
                    "AllowedHeader" -> allowedHeaders += reader.textOf(field).trim()
                    "ExposeHeader" -> exposeHeaders += reader.textOf(field).trim()
                    "MaxAgeSeconds" -> maxAge = reader.textOf(field).trim().toIntOrNull()
                }
            }
            if (methods.isEmpty() || origins.isEmpty()) {
                throw XmlReader.MalformedXmlException("<CORSRule> без <AllowedMethod> или <AllowedOrigin>")
            }
            rules += CorsRules.Rule(id, methods, origins, allowedHeaders, exposeHeaders, maxAge)
        }
        return CorsRules(rules)
    }

    /** Десять тегов на объект и на бакет — предел из документации AWS; в модели величины нет. */
    const val MAX_TAGS: Int = 10

    fun parseDelete(body: ByteArray): DeleteRequest {
        val targets = ArrayList<Target>()
        var quiet = false
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))

        reader.root("Delete") { name ->
            when (name) {
                "Object" -> {
                    var key: ObjectKey? = null
                    var eTag: String? = null
                    var lastModifiedTime: String? = null
                    var size: String? = null
                    reader.children { field ->
                        // VersionId is read and dropped: versioning is out of scope, and refusing a
                        // member the client is allowed to send would break it for no gain.
                        when (field) {
                            "Key" -> key = ObjectKey(reader.textOf(field).toByteArray())
                            "ETag" -> eTag = reader.textOf(field).trim()
                            "LastModifiedTime" -> lastModifiedTime = reader.textOf(field).trim()
                            "Size" -> size = reader.textOf(field).trim()
                        }
                    }
                    val parsed = key ?: throw XmlReader.MalformedXmlException("<Object> without <Key>")
                    if (targets.size >= MAX_DELETE_KEYS) {
                        throw XmlReader.MalformedXmlException("more than $MAX_DELETE_KEYS objects in one delete")
                    }
                    targets.add(Target(parsed, eTag, lastModifiedTime, size))
                }

                "Quiet" -> {
                    quiet = reader.textOf(name).trim().equals("true", ignoreCase = true)
                }
            }
        }
        return DeleteRequest(targets, quiet)
    }

    /**
     * The part list of `CompleteMultipartUpload`.
     *
     * Order is **not** checked here. Parts must arrive ascending by number, and out of order is
     * `InvalidPartOrder` — but that is a 400 with a code, not a malformed document, and the
     * difference is what the client sees. Checked where the upload is completed (M-55).
     */
    fun parseCompleteMultipartUpload(body: ByteArray): List<CompletedPart> {
        val parts = ArrayList<CompletedPart>()
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))

        reader.root("CompleteMultipartUpload") { name ->
            if (name != "Part") return@root
            var number: Int? = null
            var eTag: String? = null
            reader.children { field ->
                when (field) {
                    "PartNumber" -> {
                        val raw = reader.textOf(field).trim()
                        number =
                            raw.toIntOrNull()
                                ?: throw XmlReader.MalformedXmlException("<PartNumber> is not a number: '$raw'")
                    }

                    // Quotes are kept exactly as they arrived: an ETag is compared verbatim, and
                    // stripping them here would make it not match what was handed out.
                    "ETag" -> {
                        eTag = reader.textOf(field).trim()
                    }
                }
            }
            val n = number ?: throw XmlReader.MalformedXmlException("<Part> without <PartNumber>")
            val tag = eTag ?: throw XmlReader.MalformedXmlException("<Part> without <ETag>")
            if (parts.size >= MAX_PARTS) {
                throw XmlReader.MalformedXmlException("more than $MAX_PARTS parts in one completion")
            }
            parts.add(CompletedPart(n, tag))
        }
        return parts
    }
}
