package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Правила жизненного цикла бакета и два вопроса, которые к ним задают: подходит ли объект и когда
 * ему срок.
 *
 * Форма — `docs/spec/s3-service-2.json`: `BucketLifecycleConfiguration` (`:2127`) содержит
 * `LifecycleRule` (`:7896`), у которого обязателен один `Status` (`ExpirationStatus`, `:4881` —
 * ровно `Enabled` или `Disabled`), а всё остальное необязательно.
 *
 * ## Почему правило хранится в той форме, в какой приехало
 *
 * Префикс у правила бывает в двух местах: `<Prefix>` прямо в правиле (в модели помечен
 * `deprecated`) и `<Prefix>` внутри `<Filter>`. Это два разных документа, и `GetBucketLifecycle`
 * обязан вернуть тот, который прислали: `test_lifecycle_get:8451` сравнивает правила целиком, и
 * правило, приехавшее со старым префиксом и уехавшее с фильтром, — уже другое правило. Поэтому
 * [Rule.prefix] и [Filter.prefix] — разные поля, а не одно нормализованное.
 *
 * ## Условия фильтра складываются, все
 *
 * S3 требует, чтобы у `<Filter>` был **ровно один** член: префикс, тег или `<And>` со списком.
 * Сьют шлёт документы, которые этому не подчиняются — `Prefix` вместе с `Tag`
 * (`test_lifecycle_expiration_tags1:8620`), а то и `Prefix`, `Tag` и `And` втроём
 * (`setup_lifecycle_tags2:8667`); оба кейса помечены `fails_on_aws`, то есть настоящий S3 их
 * отвергает. Здесь они принимаются, и объект обязан удовлетворять **всем** названным условиям.
 *
 * Это выбор в пользу единственного разумного чтения, а не поблажка: фильтр перечисляет признаки
 * объекта, и объект либо обладает всеми, либо не подходит. Отвергать документ было бы тоже
 * защитимо — но тогда правило, которое клиент считает поставленным, не поставлено, и узнает он
 * об этом по невыполненному удалению.
 */
data class Lifecycle(
    val rules: List<Rule>,
) {
    data class Rule(
        val id: String,
        val enabled: Boolean,
        /** `<Prefix>` самого правила — старая форма, в модели помечена `deprecated`. */
        val prefix: String? = null,
        val filter: Filter? = null,
        val expiration: Expiration? = null,
        val noncurrent: Noncurrent? = null,
        /** `AbortIncompleteMultipartUpload.DaysAfterInitiation` (`:1653`). */
        val abortIncompleteUploadDays: Int? = null,
    ) {
        /**
         * Подходит ли объект под правило.
         *
         * Теги — карта, а не список: у объекта они уникальны по ключу, и условие «`tom=sawyer`»
         * означает «есть тег с таким ключом и таким значением», а не «есть такая пара где-то
         * среди повторов».
         */
        fun matches(
            key: ObjectKey,
            size: Long,
            tags: Map<String, String>,
        ): Boolean {
            val bytes = key.toByteArray()
            if (prefix != null && !startsWith(bytes, prefix)) return false
            val f = filter ?: return true
            if (f.prefix != null && !startsWith(bytes, f.prefix)) return false
            if (!f.tags.all { tags[it.key] == it.value }) return false
            if (f.sizeGreaterThan != null && size <= f.sizeGreaterThan) return false
            if (f.sizeLessThan != null && size >= f.sizeLessThan) return false
            val and = f.and ?: return true
            if (and.prefix != null && !startsWith(bytes, and.prefix)) return false
            if (!and.tags.all { tags[it.key] == it.value }) return false
            if (and.sizeGreaterThan != null && size <= and.sizeGreaterThan) return false
            if (and.sizeLessThan != null && size >= and.sizeLessThan) return false
            return true
        }

        /**
         * Какой префикс правило называет, чем бы он ни был назван. Для брошенных загрузок: у
         * многочастной загрузки нет ни размера, ни тегов, поэтому от фильтра ей достаётся только
         * это.
         */
        fun statedPrefix(): String? = prefix ?: filter?.prefix ?: filter?.and?.prefix
    }

    /** `LifecycleRuleFilter` (`:7960`). */
    data class Filter(
        val prefix: String? = null,
        val tags: List<Tag> = emptyList(),
        val sizeGreaterThan: Long? = null,
        val sizeLessThan: Long? = null,
        val and: And? = null,
    )

    /** `LifecycleRuleAndOperator` (`:7936`) — то же самое, но списком тегов. */
    data class And(
        val prefix: String? = null,
        val tags: List<Tag> = emptyList(),
        val sizeGreaterThan: Long? = null,
        val sizeLessThan: Long? = null,
    )

    data class Tag(
        val key: String,
        val value: String,
    )

    /**
     * `LifecycleExpiration` (`:7878`): срок днями, срок датой или снятие одинокого надгробия.
     *
     * Три необязательных члена и ни одного обязательного, потому что правило бывает про разное:
     * `Days`/`Date` — про сам объект, `ExpiredObjectDeleteMarker` — про надгробие, под которым
     * не осталось версий.
     */
    data class Expiration(
        val days: Int? = null,
        val date: Instant? = null,
        val expiredObjectDeleteMarker: Boolean = false,
    )

    /**
     * `NoncurrentVersionExpiration` (`:9378`).
     *
     * [newerVersions] — сколько **свежих** неактуальных версий пережидают срок независимо от
     * возраста: `NewerNoncurrentVersions: 5` при десяти версиях оставляет текущую и пять
     * следующих за ней, а четыре нижние удаляет.
     */
    data class Noncurrent(
        val days: Int,
        val newerVersions: Int? = null,
    )

    /** Правила, которые сейчас что-то делают. Выключенное правило хранится и не исполняется. */
    val enabled: List<Rule> get() = rules.filter { it.enabled }

    /**
     * Когда истекает срок объекта и по какому правилу — или `null`, если ни по какому.
     *
     * Это ответ и для заголовка `x-amz-expiration`, и для обхода: одно место, потому что заголовок,
     * обещающий один срок, и обход, удаляющий в другой, — худший из возможных вариантов. Первое
     * подошедшее правило, а не самое раннее: S3 запрещает перекрывающиеся правила, и выбирать
     * между ними значило бы делать вид, что документ, который не должен был приехать, осмыслен.
     */
    fun expiryOf(
        key: ObjectKey,
        size: Long,
        tags: Map<String, String>,
        created: Instant,
        day: Duration,
    ): Pair<Instant, Rule>? {
        for (rule in enabled) {
            val expiration = rule.expiration ?: continue
            if (!rule.matches(key, size, tags)) continue
            val at = expiresAt(expiration, created, day) ?: continue
            return at to rule
        }
        return null
    }

    companion object {
        /**
         * Сколько длится «день» по умолчанию — сутки, и это единственное значение, при котором
         * округление до полуночи что-то значит.
         */
        val DAY: Duration = Duration.ofDays(1)

        /** Предел длины `ID` (`shapes.ID`, документация `PutBucketLifecycleConfiguration`). */
        const val MAX_ID_LENGTH: Int = 255

        /**
         * Момент истечения срока по правилу, или `null`, если правило про надгробие, а не про срок.
         *
         * **Округление вверх до полуночи UTC делается только тогда, когда «день» — настоящие
         * сутки.** У S3 округление есть потому, что день там календарный: «дата истечения
         * получается прибавлением `Days` к дате создания и округлением до ближайшей полуночи UTC».
         * Когда единицу укорачивают ради теста (см. `BOCHKA_LIFECYCLE_DAY_SECONDS`), календаря
         * нет вовсе, и округление к полуночи отложило бы срок на сутки вперёд — то есть отменило
         * бы укорачивание.
         */
        fun expiresAt(
            expiration: Expiration,
            created: Instant,
            day: Duration,
        ): Instant? {
            expiration.date?.let { return it }
            val days = expiration.days ?: return null
            val due = created.plus(day.multipliedBy(days.toLong()))
            if (day != DAY) return due
            val midnight = due.truncatedTo(ChronoUnit.DAYS)
            return if (midnight == due) due else midnight.plus(DAY)
        }

        private fun startsWith(
            key: ByteArray,
            prefix: String,
        ): Boolean {
            val bytes = prefix.toByteArray(StandardCharsets.UTF_8)
            if (key.size < bytes.size) return false
            for (i in bytes.indices) if (key[i] != bytes[i]) return false
            return true
        }
    }
}
