package io.github.youndie.bochka.s3.xml

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.core.ObjectStore
import io.github.youndie.bochka.s3.CorsRules
import io.github.youndie.bochka.s3.Lifecycle
import io.github.youndie.bochka.s3.UriCodec
import java.nio.charset.StandardCharsets
import java.time.Instant

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
        /**
         * Which version to remove, when the client names one.
         *
         * Not decoration: this is how a versioned bucket is emptied. `nuke_bucket` in
         * `ceph/s3-tests` pages `ListObjectVersions` and posts the ids back here in batches of a
         * hundred and twenty-eight — so a batch delete that drops this field answers `204`,
         * lays a fresh tombstone over every key, and leaves the bucket exactly as full as it was.
         */
        val versionId: String? = null,
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
        // Предел на число тегов здесь не проверяется, и это не упущение: одиннадцать тегов —
        // документ разборчивый и по схеме верный, а неверен набор, который он описывает. Ответ
        // на такое — `InvalidTag` от [TagRules], а не `MalformedXML`, посылающий клиента искать
        // ошибку в своём сериализаторе (M-155).
        return tags
    }

    /**
     * `x-amz-tagging: a=1&b=2` — те же теги, но формой запроса, а не документа
     * (`s3-service-2.json:3158`). Значения процентно закодированы, как в query.
     *
     * **Пара без `=` — это тег с пустым значением, а не поломка.** У тега значение необязательно,
     * и `foo=bar&bar` (`test_put_obj_with_tags:12281`) означает два тега, у второго значение
     * пустое. Отказ здесь стоил дороже, чем выглядел: [ObjectHeaders.read] зовётся из `screen`,
     * а брошенное оттуда исключение уходило мимо цикла запроса — клиент получал закрытый сокет
     * без единого байта ответа и диагностировал сеть.
     */
    fun parseTaggingHeader(value: String): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        for (pair in value.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                tags[decode(pair)] = ""
            } else {
                tags[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
            }
        }
        return tags
    }

    private fun decode(value: String): String = String(UriCodec.decode(value, plusIsSpace = true))

    /**
     * `<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>`.
     *
     * Only two values are accepted, and `Disabled` is not one of them: S3 has no way back to
     * "never configured" once versioning has been switched on, so a request asking for it is asking
     * for something no server does. Accepting it and storing nothing would leave the client
     * believing its versions had stopped being kept.
     */
    fun parseVersioning(body: ByteArray): ObjectStore.Versioning {
        var status: String? = null
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("VersioningConfiguration") { name ->
            if (name == "Status") status = reader.textOf(name).trim()
        }
        return when (status) {
            "Enabled" -> ObjectStore.Versioning.ENABLED
            "Suspended" -> ObjectStore.Versioning.SUSPENDED
            else -> throw XmlReader.MalformedXmlException("Status must be Enabled or Suspended, not '$status'")
        }
    }

    /**
     * `<ObjectLockConfiguration>` — `s3-service-2.json`, `PutObjectLockConfigurationRequest`.
     *
     * Three refusals, and they are three different codes because the client fixes three different
     * things. A status that is not `Enabled`, a mode that is not one of the two, or both `Days`
     * and `Years` — the document is wrong, `MalformedXML`. A period that is zero or negative — the
     * document is well-formed and the number is nonsense, `InvalidRetentionPeriod`
     * (`test_object_lock_put_obj_lock_invalid_days:13378`).
     */
    fun parseObjectLock(body: ByteArray): ObjectStore.ObjectLock {
        var status: String? = null
        var mode: String? = null
        var days: Int? = null
        var years: Int? = null
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("ObjectLockConfiguration") { name ->
            when (name) {
                "ObjectLockEnabled" -> {
                    status = reader.textOf(name).trim()
                }

                "Rule" -> {
                    reader.children { rule ->
                        if (rule != "DefaultRetention") return@children
                        reader.children { field ->
                            when (field) {
                                "Mode" -> mode = reader.textOf(field).trim()
                                "Days" -> days = reader.textOf(field).trim().toIntOrNull() ?: 0
                                "Years" -> years = reader.textOf(field).trim().toIntOrNull() ?: 0
                            }
                        }
                    }
                }
            }
        }
        if (status != "Enabled") throw XmlReader.MalformedXmlException("ObjectLockEnabled must be Enabled")
        if (mode != null && mode !in RETENTION_MODES) throw XmlReader.MalformedXmlException("bad Mode: '$mode'")
        if (days != null && years != null) throw XmlReader.MalformedXmlException("both Days and Years")
        days?.let { if (it <= 0) throw InvalidRetentionPeriod("Days must be positive, not $it") }
        years?.let { if (it <= 0) throw InvalidRetentionPeriod("Years must be positive, not $it") }
        return ObjectStore.ObjectLock(mode, days, years)
    }

    /** A period that parses and cannot be meant: separate from a malformed document on purpose. */
    class InvalidRetentionPeriod(
        override val message: String,
    ) : RuntimeException(message)

    val RETENTION_MODES = setOf("GOVERNANCE", "COMPLIANCE")

    /** `<Retention><Mode>…<RetainUntilDate>…` — an empty document means "take the retention off". */
    fun parseRetention(body: ByteArray): ObjectStore.Retention? {
        var mode: String? = null
        var until: String? = null
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("Retention") { name ->
            when (name) {
                "Mode" -> mode = reader.textOf(name).trim()
                "RetainUntilDate" -> until = reader.textOf(name).trim()
            }
        }
        if (mode == null && until == null) return null
        if (mode !in RETENTION_MODES) throw XmlReader.MalformedXmlException("bad Mode: '$mode'")
        val stated = until ?: throw XmlReader.MalformedXmlException("Retention without RetainUntilDate")
        // Both spellings arrive: botocore sends an offset date-time, and
        // `test_object_lock_get_obj_retention_iso8601:13567` pins that a plain `Z` instant is read
        // back the same way it was written.
        val instant =
            try {
                java.time.OffsetDateTime
                    .parse(stated)
                    .toInstant()
            } catch (_: java.time.format.DateTimeParseException) {
                try {
                    java.time.Instant.parse(stated)
                } catch (_: java.time.format.DateTimeParseException) {
                    throw XmlReader.MalformedXmlException("RetainUntilDate is not a date: '$stated'")
                }
            }
        return ObjectStore.Retention(mode!!, instant.toEpochMilli())
    }

    /** `<LegalHold><Status>ON|OFF</Status></LegalHold>`. */
    fun parseLegalHold(body: ByteArray): Boolean {
        var status: String? = null
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("LegalHold") { name ->
            if (name == "Status") status = reader.textOf(name).trim()
        }
        return when (status) {
            "ON" -> true
            "OFF" -> false
            else -> throw XmlReader.MalformedXmlException("LegalHold Status must be ON or OFF, not '$status'")
        }
    }

    /**
     * `<LifecycleConfiguration><Rule>…` — `s3-service-2.json:2127`, `:7896`.
     *
     * Разбор и **проверки** вместе, потому что проверки здесь разными кодами, а код отказа — это
     * то, что клиент чинит. Документ, который не является документом (`Status`, написанный
     * `enabled`), — `MalformedXML`; документ разборчивый и бессмысленный (`Days: 0`, два правила
     * с одним `ID`) — `InvalidArgument`. Сьют пинит обе стороны:
     * `test_lifecycle_invalid_status:9037` ждёт первое, `test_lifecycle_id_too_long:9012` и
     * `test_lifecycle_same_id:9024` — второе.
     */
    fun parseLifecycle(body: ByteArray): Lifecycle {
        val rules = ArrayList<Lifecycle.Rule>()
        val reader = XmlReader(body.toString(StandardCharsets.UTF_8))
        reader.root("LifecycleConfiguration") { name ->
            if (name != "Rule") return@root
            rules += parseLifecycleRule(reader)
        }
        if (rules.isEmpty()) throw XmlReader.MalformedXmlException("<LifecycleConfiguration> without a <Rule>")
        val ids = HashSet<String>()
        for (rule in rules) {
            if (!ids.add(rule.id)) throw InvalidArgument("two rules share the ID '${rule.id}'")
        }
        return Lifecycle(rules)
    }

    private fun parseLifecycleRule(reader: XmlReader): Lifecycle.Rule {
        var id: String? = null
        var status: String? = null
        var prefix: String? = null
        var filter: Lifecycle.Filter? = null
        var expiration: Lifecycle.Expiration? = null
        var noncurrent: Lifecycle.Noncurrent? = null
        var abortDays: Int? = null
        reader.children { field ->
            when (field) {
                "ID" -> {
                    id = reader.textOf(field)
                }

                "Status" -> {
                    status = reader.textOf(field).trim()
                }

                "Prefix" -> {
                    prefix = reader.textOf(field)
                }

                "Filter" -> {
                    filter = parseLifecycleFilter(reader)
                }

                "Expiration" -> {
                    expiration = parseLifecycleExpiration(reader)
                }

                "NoncurrentVersionExpiration" -> {
                    noncurrent = parseNoncurrentExpiration(reader)
                }

                "AbortIncompleteMultipartUpload" -> {
                    abortDays = parseAbortIncomplete(reader)
                }

                // Отвергается по имени, а не пропускается: класс хранения здесь один, потому что
                // диск один, и правилу «через тридцать дней в GLACIER» некуда исполняться.
                // Принятое и неисполняемое правило клиент обнаруживает счётом за хранение, а не
                // ошибкой — то же соображение, по которому отвергается `PutBucketPolicy`.
                "Transition", "NoncurrentVersionTransition" -> {
                    throw InvalidArgument("<$field>: this store has one storage class")
                }
            }
        }
        // Ровно два значения и с той же буквы: `ExpirationStatus` — перечисление
        // (`s3-service-2.json:4881`), а `enabled` в нём нет.
        if (status != "Enabled" && status != "Disabled") {
            throw XmlReader.MalformedXmlException("<Status> must be Enabled or Disabled, not '$status'")
        }
        val stated = id?.trim()
        if (stated != null && stated.length > Lifecycle.MAX_ID_LENGTH) {
            throw InvalidArgument("<ID> is ${stated.length} characters, over ${Lifecycle.MAX_ID_LENGTH}")
        }
        return Lifecycle.Rule(
            // Правило без `ID` его получает: `GetBucketLifecycleConfiguration` обязан ответить
            // правилом с идентификатором (`test_lifecycle_get_no_id:8494`), а придумать его больше
            // некому. Придуманный один раз — на записи, — и дальше живёт в сохранённом документе.
            id = if (stated.isNullOrEmpty()) mintRuleId() else stated,
            enabled = status == "Enabled",
            prefix = prefix,
            filter = filter,
            expiration = expiration,
            noncurrent = noncurrent,
            abortIncompleteUploadDays = abortDays,
        )
    }

    private fun parseLifecycleFilter(reader: XmlReader): Lifecycle.Filter {
        var prefix: String? = null
        val tags = ArrayList<Lifecycle.Tag>()
        var greater: Long? = null
        var less: Long? = null
        var and: Lifecycle.And? = null
        reader.children { field ->
            when (field) {
                "Prefix" -> {
                    prefix = reader.textOf(field)
                }

                "Tag" -> {
                    tags += parseLifecycleTag(reader)
                }

                "ObjectSizeGreaterThan" -> {
                    greater = longOf(reader, field)
                }

                "ObjectSizeLessThan" -> {
                    less = longOf(reader, field)
                }

                "And" -> {
                    var andPrefix: String? = null
                    val andTags = ArrayList<Lifecycle.Tag>()
                    var andGreater: Long? = null
                    var andLess: Long? = null
                    reader.children { inner ->
                        when (inner) {
                            "Prefix" -> andPrefix = reader.textOf(inner)
                            "Tag" -> andTags += parseLifecycleTag(reader)
                            "ObjectSizeGreaterThan" -> andGreater = longOf(reader, inner)
                            "ObjectSizeLessThan" -> andLess = longOf(reader, inner)
                        }
                    }
                    and = Lifecycle.And(andPrefix, andTags, andGreater, andLess)
                }
            }
        }
        return Lifecycle.Filter(prefix, tags, greater, less, and)
    }

    private fun parseLifecycleTag(reader: XmlReader): Lifecycle.Tag {
        var key: String? = null
        var value: String? = null
        reader.children { field ->
            when (field) {
                "Key" -> key = reader.textOf(field)
                "Value" -> value = reader.textOf(field)
            }
        }
        return Lifecycle.Tag(
            key ?: throw XmlReader.MalformedXmlException("<Tag> without <Key>"),
            value ?: throw XmlReader.MalformedXmlException("<Tag> without <Value>"),
        )
    }

    private fun parseLifecycleExpiration(reader: XmlReader): Lifecycle.Expiration {
        var days: Int? = null
        var date: Instant? = null
        var marker = false
        reader.children { field ->
            when (field) {
                "Days" -> days = intOf(reader, field)
                "Date" -> date = lifecycleDate(reader.textOf(field).trim())
                "ExpiredObjectDeleteMarker" -> marker = reader.textOf(field).trim().equals("true", ignoreCase = true)
            }
        }
        // Ноль дней законен у перехода и незаконен у истечения — сьют проверяет именно эту
        // разницу (`test_lifecycle_expiration_days0:9111`), и ответ у неё `InvalidArgument`,
        // а не «документ сломан».
        days?.let { if (it <= 0) throw InvalidArgument("<Days> must be positive, not $it") }
        if (days != null && date != null) throw XmlReader.MalformedXmlException("both <Days> and <Date>")
        return Lifecycle.Expiration(days, date, marker)
    }

    /**
     * Дата истечения — **всегда полночь UTC**, и это не придирка к формату.
     *
     * `test_lifecycle_set_invalid_date:9075` шлёт строку `'20200101'`, ожидая `400`. На провод
     * приезжает `1970-08-22T19:08:21Z` — botocore понял это как секунды эпохи и выдал совершенно
     * исправную дату. Отличить её от осмысленной нечем, кроме правила S3 «время всегда полночь
     * UTC», и правило это заодно единственное, что делает `Date` датой, а не моментом.
     */
    private fun lifecycleDate(stated: String): Instant {
        val instant =
            try {
                Instant.parse(stated)
            } catch (_: java.time.format.DateTimeParseException) {
                try {
                    java.time.OffsetDateTime
                        .parse(stated)
                        .toInstant()
                } catch (_: java.time.format.DateTimeParseException) {
                    throw XmlReader.MalformedXmlException("<Date> is not a date: '$stated'")
                }
            }
        if (instant != instant.truncatedTo(java.time.temporal.ChronoUnit.DAYS)) {
            throw InvalidArgument("<Date> must be midnight UTC, got '$stated'")
        }
        return instant
    }

    private fun parseNoncurrentExpiration(reader: XmlReader): Lifecycle.Noncurrent {
        var days: Int? = null
        var newer: Int? = null
        reader.children { field ->
            when (field) {
                "NoncurrentDays" -> days = intOf(reader, field)
                "NewerNoncurrentVersions" -> newer = intOf(reader, field)
            }
        }
        val stated = days ?: throw XmlReader.MalformedXmlException("<NoncurrentVersionExpiration> without days")
        if (stated <= 0) throw InvalidArgument("<NoncurrentDays> must be positive, not $stated")
        return Lifecycle.Noncurrent(stated, newer)
    }

    private fun parseAbortIncomplete(reader: XmlReader): Int {
        var days: Int? = null
        reader.children { field ->
            if (field == "DaysAfterInitiation") days = intOf(reader, field)
        }
        val stated = days ?: throw XmlReader.MalformedXmlException("<AbortIncompleteMultipartUpload> without days")
        if (stated <= 0) throw InvalidArgument("<DaysAfterInitiation> must be positive, not $stated")
        return stated
    }

    private fun intOf(
        reader: XmlReader,
        field: String,
    ): Int {
        val raw = reader.textOf(field).trim()
        return raw.toIntOrNull() ?: throw XmlReader.MalformedXmlException("<$field> is not a number: '$raw'")
    }

    private fun longOf(
        reader: XmlReader,
        field: String,
    ): Long {
        val raw = reader.textOf(field).trim()
        return raw.toLongOrNull() ?: throw XmlReader.MalformedXmlException("<$field> is not a number: '$raw'")
    }

    private fun mintRuleId(): String =
        java.util.UUID
            .randomUUID()
            .toString()
            .replace("-", "")

    /** Документ разобран, и то, что в нём написано, не может быть исполнено. `400 InvalidArgument`. */
    class InvalidArgument(
        override val message: String,
    ) : RuntimeException(message)

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
                    var versionId: String? = null
                    reader.children { field ->
                        // VersionId was read and dropped while versioning was out of scope. It is
                        // the whole operation now: a batch delete that ignores it answers `204`
                        // and leaves every version where it was.
                        when (field) {
                            "Key" -> key = ObjectKey(reader.textOf(field).toByteArray())
                            "ETag" -> eTag = reader.textOf(field).trim()
                            "LastModifiedTime" -> lastModifiedTime = reader.textOf(field).trim()
                            "Size" -> size = reader.textOf(field).trim()
                            "VersionId" -> versionId = reader.textOf(field).trim()
                        }
                    }
                    val parsed = key ?: throw XmlReader.MalformedXmlException("<Object> without <Key>")
                    if (targets.size >= MAX_DELETE_KEYS) {
                        throw XmlReader.MalformedXmlException("more than $MAX_DELETE_KEYS objects in one delete")
                    }
                    targets.add(Target(parsed, eTag, lastModifiedTime, size, versionId))
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
