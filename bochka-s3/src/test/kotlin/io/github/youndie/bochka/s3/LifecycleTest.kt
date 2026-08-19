package io.github.youndie.bochka.s3

import io.github.youndie.bochka.core.ObjectKey
import io.github.youndie.bochka.s3.xml.S3Documents
import io.github.youndie.bochka.s3.xml.S3Requests
import io.github.youndie.bochka.s3.xml.XmlReader
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правила жизненного цикла: `docs/spec/s3-service-2.json` — `BucketLifecycleConfiguration`
 * (`:2127`), `LifecycleRule` (`:7896`), `LifecycleRuleFilter` (`:7960`),
 * `LifecycleRuleAndOperator` (`:7936`), `ExpirationStatus` (`:4881`).
 *
 * **Тела здесь — не сочинённые.** Каждое снято с botocore: тот же вызов, каким его делает сьют,
 * с перехватом `before-send`. Это важно ровно для трёх мест, где сочинённое тело было бы другим:
 * `<Filter />` и `<Prefix />` приезжают самозакрывающимися, `Date` приезжает моментом
 * (`2017-09-27T00:00:00Z`), а не датой, и заведомо неверная дата приезжает **исправной**, потому
 * что клиент понял её как секунды эпохи.
 */
class LifecycleTest {
    @Test
    fun `правило с префиксом самого правила разбирается и возвращается тем же документом`() {
        // `test_lifecycle_get:8451` сравнивает правила целиком: то, чем правило приехало, — часть
        // его содержания.
        val body =
            """
            <LifecycleConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Rule>
            <ID>rule1</ID><Expiration><Days>1</Days></Expiration><Prefix>test1/</Prefix>
            <Status>Enabled</Status></Rule></LifecycleConfiguration>
            """.trimIndent().replace("\n", "").toByteArray()

        val parsed = S3Requests.parseLifecycle(body)
        val rule = parsed.rules.single()

        assertEquals("rule1", rule.id)
        assertTrue(rule.enabled)
        assertEquals("test1/", rule.prefix)
        assertNull(rule.filter)
        assertEquals(1, rule.expiration?.days)

        val rendered = String(S3Documents.lifecycleResult(parsed))
        // Префикс уехал там же, где приехал, и `<Filter>` не появился.
        assertTrue("<Prefix>test1/</Prefix>" in rendered, rendered)
        assertFalse("<Filter>" in rendered, rendered)
    }

    @Test
    fun `выключенное правило хранится выключенным`() {
        val body =
            "<LifecycleConfiguration><Rule><ID>rule2</ID><Expiration><Days>2</Days></Expiration>" +
                "<Prefix>test2/</Prefix><Status>Disabled</Status></Rule></LifecycleConfiguration>"

        val parsed = S3Requests.parseLifecycle(body.toByteArray())

        assertFalse(parsed.rules.single().enabled)
        assertTrue(parsed.enabled.isEmpty())
        assertTrue("<Status>Disabled</Status>" in String(S3Documents.lifecycleResult(parsed)))
    }

    @Test
    fun `правилу без идентификатора его выдают`() {
        // `test_lifecycle_get_no_id:8494` требует `ID` в ответе у правила, которое приехало без
        // него. Придумать его больше некому, и придумывается он один раз — на записи.
        val body =
            "<LifecycleConfiguration><Rule><Expiration><Days>31</Days></Expiration>" +
                "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"

        val id =
            S3Requests
                .parseLifecycle(body.toByteArray())
                .rules
                .single()
                .id

        assertTrue(id.isNotEmpty())
    }

    @Test
    fun `Status разбирается ровно двумя написаниями`() {
        // `ExpirationStatus` — перечисление из двух значений (`:4881`), и `enabled` в нём нет.
        // `test_lifecycle_invalid_status:9037` ждёт именно `MalformedXML`: документ не является
        // документом, а не «число не то».
        for (status in listOf("enabled", "disabled", "invalid", "")) {
            val body =
                "<LifecycleConfiguration><Rule><ID>rule1</ID><Expiration><Days>2</Days></Expiration>" +
                    "<Prefix>test1/</Prefix><Status>$status</Status></Rule></LifecycleConfiguration>"
            assertFailsWith<XmlReader.MalformedXmlException>(status) {
                S3Requests.parseLifecycle(body.toByteArray())
            }
        }
    }

    @Test
    fun `слишком длинный идентификатор и повторённый идентификатор — InvalidArgument`() {
        // `test_lifecycle_id_too_long:9012` и `test_lifecycle_same_id:9024`. Оба — про разборчивый
        // документ, который нельзя исполнить, и код у них поэтому другой, чем у сломанного.
        val long =
            "<LifecycleConfiguration><Rule><ID>${"a".repeat(256)}</ID>" +
                "<Expiration><Days>2</Days></Expiration><Prefix>test1/</Prefix>" +
                "<Status>Enabled</Status></Rule></LifecycleConfiguration>"
        assertFailsWith<S3Requests.InvalidArgument> { S3Requests.parseLifecycle(long.toByteArray()) }

        val same =
            "<LifecycleConfiguration>" +
                "<Rule><ID>rule1</ID><Expiration><Days>1</Days></Expiration>" +
                "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule>" +
                "<Rule><ID>rule1</ID><Expiration><Days>2</Days></Expiration>" +
                "<Prefix>test2/</Prefix><Status>Enabled</Status></Rule>" +
                "</LifecycleConfiguration>"
        assertFailsWith<S3Requests.InvalidArgument> { S3Requests.parseLifecycle(same.toByteArray()) }

        // Ровно 255 — предел, а не запрет.
        val edge =
            "<LifecycleConfiguration><Rule><ID>${"a".repeat(255)}</ID>" +
                "<Expiration><Days>2</Days></Expiration><Prefix>test1/</Prefix>" +
                "<Status>Enabled</Status></Rule></LifecycleConfiguration>"
        assertEquals(
            255,
            S3Requests
                .parseLifecycle(edge.toByteArray())
                .rules
                .single()
                .id.length,
        )
    }

    @Test
    fun `ноль дней у истечения — InvalidArgument`() {
        // `test_lifecycle_expiration_days0:9111`, и комментарий кейса объясняет, почему это не
        // «просто отказ»: у перехода ноль дней законен, у истечения — нет.
        val body =
            "<LifecycleConfiguration><Rule><ID>rule1</ID><Expiration><Days>0</Days></Expiration>" +
                "<Prefix>days0/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"

        assertFailsWith<S3Requests.InvalidArgument> { S3Requests.parseLifecycle(body.toByteArray()) }
    }

    @Test
    fun `дата истечения обязана быть полночью UTC`() {
        // Оба тела сняты с botocore. Первое — `Date: '2017-09-27'`
        // (`test_lifecycle_set_date:9065`), второе — `Date: '20200101'`
        // (`test_lifecycle_set_invalid_date:9075`), которое клиент понял как секунды эпохи и
        // превратил в исправную дату с временем 19:08:21. Отличить одно от другого можно только
        // правилом «время всегда полночь»: без него второй кейс проходит, и правило со сроком
        // посреди дня остаётся в бакете.
        val midnight =
            "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                "<Expiration><Date>2017-09-27T00:00:00Z</Date></Expiration>" +
                "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"
        assertEquals(
            Instant.parse("2017-09-27T00:00:00Z"),
            S3Requests
                .parseLifecycle(midnight.toByteArray())
                .rules
                .single()
                .expiration
                ?.date,
        )

        val noon =
            "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                "<Expiration><Date>1970-08-22T19:08:21Z</Date></Expiration>" +
                "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"
        assertFailsWith<S3Requests.InvalidArgument> { S3Requests.parseLifecycle(noon.toByteArray()) }
    }

    @Test
    fun `правило с переходом отвергается по имени`() {
        // Класс хранения один, потому что диск один. `test_lifecycle_transition_set_invalid_date:9476`
        // ждёт от этого тела `400`, и получает его — но не за дату, а за сам переход, и это
        // записано здесь, чтобы кейс не выглядел проходящим по той причине, по которой он писался.
        val body =
            "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                "<Expiration><Date>2023-09-27T00:00:00Z</Date></Expiration>" +
                "<Transition><Date>1970-08-23T00:55:27Z</Date><StorageClass>GLACIER</StorageClass></Transition>" +
                "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"

        assertFailsWith<S3Requests.InvalidArgument> { S3Requests.parseLifecycle(body.toByteArray()) }
    }

    @Test
    fun `пустой фильтр — это фильтр, который подходит всему`() {
        // Тело botocore для `Filter: {}` (`test_lifecycle_set_empty_filter:9349`).
        val body =
            "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>" +
                "<Filter /><Status>Enabled</Status></Rule></LifecycleConfiguration>"

        val rule = S3Requests.parseLifecycle(body.toByteArray()).rules.single()

        assertEquals(Lifecycle.Filter(), rule.filter)
        assertTrue(rule.expiration!!.expiredObjectDeleteMarker)
        assertTrue(rule.matches(ObjectKey.of("anything"), 1, emptyMap()))
    }

    @Test
    fun `условия фильтра складываются, сколько бы их ни назвали`() {
        // Тело botocore для `setup_lifecycle_tags2:8667`: `Prefix`, `Tag` и `And` в одном фильтре.
        // S3 такой документ отвергает — кейс помечен `fails_on_aws`, — а здесь он принимается и
        // означает «и то, и другое, и третье».
        val body =
            "<LifecycleConfiguration><Rule><Expiration><Days>1</Days></Expiration><ID>rule_tag1</ID>" +
                "<Filter><Prefix>days1/</Prefix><Tag><Key>tom</Key><Value>sawyer</Value></Tag>" +
                "<And><Prefix>days1</Prefix><Tag><Key>huck</Key><Value>finn</Value></Tag></And></Filter>" +
                "<Status>Enabled</Status></Rule></LifecycleConfiguration>"

        val rule = S3Requests.parseLifecycle(body.toByteArray()).rules.single()

        // У Тома только его собственный тег, у Гека — оба.
        assertFalse(rule.matches(ObjectKey.of("days1/tom"), 8, mapOf("tom" to "sawyer")))
        assertTrue(rule.matches(ObjectKey.of("days1/huck"), 9, mapOf("tom" to "sawyer", "huck" to "finn")))
        // Префикс всё ещё обязателен, даже когда теги совпали.
        assertFalse(rule.matches(ObjectKey.of("elsewhere/huck"), 9, mapOf("tom" to "sawyer", "huck" to "finn")))
    }

    @Test
    fun `размер сравнивается строго с обеих сторон`() {
        // Тело botocore для `test_lifecycle_expiration_size_gt:8909`: пустой префикс приезжает
        // самозакрывающимся элементом.
        val body =
            "<LifecycleConfiguration><Rule><Expiration><Days>1</Days></Expiration><ID>object_gt1</ID>" +
                "<Filter><Prefix /><ObjectSizeGreaterThan>2000</ObjectSizeGreaterThan></Filter>" +
                "<Status>Enabled</Status></Rule></LifecycleConfiguration>"

        val rule = S3Requests.parseLifecycle(body.toByteArray()).rules.single()

        assertEquals("", rule.filter?.prefix)
        assertFalse(rule.matches(ObjectKey.of("myobject_small"), 1000, emptyMap()))
        assertFalse(rule.matches(ObjectKey.of("myobject_edge"), 2000, emptyMap()))
        assertTrue(rule.matches(ObjectKey.of("myobject_big"), 3000, emptyMap()))
    }

    @Test
    fun `неактуальные версии и брошенные загрузки — свои члены правила`() {
        val body =
            "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                "<NoncurrentVersionExpiration><NoncurrentDays>2</NoncurrentDays>" +
                "<NewerNoncurrentVersions>5</NewerNoncurrentVersions></NoncurrentVersionExpiration>" +
                "<AbortIncompleteMultipartUpload><DaysAfterInitiation>3</DaysAfterInitiation>" +
                "</AbortIncompleteMultipartUpload>" +
                "<Prefix>past/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"

        val rule = S3Requests.parseLifecycle(body.toByteArray()).rules.single()

        assertEquals(Lifecycle.Noncurrent(2, 5), rule.noncurrent)
        assertEquals(3, rule.abortIncompleteUploadDays)
        assertNull(rule.expiration)

        val rendered = String(S3Documents.lifecycleResult(S3Requests.parseLifecycle(body.toByteArray())))
        assertTrue("<NewerNoncurrentVersions>5</NewerNoncurrentVersions>" in rendered, rendered)
        assertTrue("<DaysAfterInitiation>3</DaysAfterInitiation>" in rendered, rendered)
    }

    @Test
    fun `документ, прошедший через отрисовку, разбирается в то же самое`() {
        // Круговой ход, а не сравнение с самим собой: разобранное отрисовывается, отрисованное
        // разбирается снова, и две модели обязаны совпасть. Это то, что делает сервер между
        // `PUT` и `GET`.
        val body =
            "<LifecycleConfiguration><Rule><Expiration><Days>1</Days></Expiration><ID>rule_tag1</ID>" +
                "<Filter><Prefix>days1/</Prefix><Tag><Key>tom</Key><Value>sawyer</Value></Tag>" +
                "<And><Prefix>days1</Prefix><Tag><Key>huck</Key><Value>finn</Value></Tag>" +
                "<ObjectSizeLessThan>4096</ObjectSizeLessThan></And></Filter>" +
                "<Status>Enabled</Status></Rule></LifecycleConfiguration>"

        val once = S3Requests.parseLifecycle(body.toByteArray())
        val twice = S3Requests.parseLifecycle(S3Documents.lifecycleResult(once))

        assertEquals(once, twice)
    }

    @Test
    fun `срок в сутках округляется вверх до полуночи UTC`() {
        // Правило S3: дата истечения — дата создания плюс `Days`, округлённая до ближайшей
        // полуночи UTC. Объект, созданный в 14:30, живёт до полуночи через сутки с лишним.
        val created = Instant.parse("2026-08-19T14:30:00Z")
        val expiration = Lifecycle.Expiration(days = 1)

        assertEquals(
            Instant.parse("2026-08-21T00:00:00Z"),
            Lifecycle.expiresAt(expiration, created, Lifecycle.DAY),
        )
        // Уже полночь — округлять нечего, и лишние сутки не добавляются.
        assertEquals(
            Instant.parse("2026-08-21T00:00:00Z"),
            Lifecycle.expiresAt(expiration, Instant.parse("2026-08-20T00:00:00Z"), Lifecycle.DAY),
        )
    }

    @Test
    fun `укороченный день не округляется вовсе`() {
        // Округление у S3 есть потому, что день там календарный. Пятисекундной единице календаря
        // нет, и округление «до полуночи» отложило бы срок на сутки вперёд — то есть отменило бы
        // укорачивание, ради которого единицу и укорачивают.
        val created = Instant.parse("2026-08-19T14:30:03Z")

        assertEquals(
            Instant.parse("2026-08-19T14:30:08Z"),
            Lifecycle.expiresAt(Lifecycle.Expiration(days = 1), created, Duration.ofSeconds(5)),
        )
        assertEquals(
            Instant.parse("2026-08-19T14:30:28Z"),
            Lifecycle.expiresAt(Lifecycle.Expiration(days = 5), created, Duration.ofSeconds(5)),
        )
    }

    @Test
    fun `срок датой берётся как есть, а надгробное правило срока не даёт вовсе`() {
        val date = Instant.parse("2015-01-01T00:00:00Z")

        assertEquals(
            date,
            Lifecycle.expiresAt(
                Lifecycle.Expiration(date = date),
                Instant.parse("2026-08-19T00:00:00Z"),
                Lifecycle.DAY,
            ),
        )
        assertNull(
            Lifecycle.expiresAt(
                Lifecycle.Expiration(expiredObjectDeleteMarker = true),
                Instant.parse("2026-08-19T00:00:00Z"),
                Lifecycle.DAY,
            ),
        )
    }

    @Test
    fun `срок объекта ищется по первому подошедшему правилу, а выключенные не смотрятся вовсе`() {
        val body =
            "<LifecycleConfiguration>" +
                "<Rule><ID>off</ID><Expiration><Days>1</Days></Expiration>" +
                "<Prefix>a/</Prefix><Status>Disabled</Status></Rule>" +
                "<Rule><ID>on</ID><Expiration><Days>3</Days></Expiration>" +
                "<Prefix>a/</Prefix><Status>Enabled</Status></Rule>" +
                "</LifecycleConfiguration>"
        val lifecycle = S3Requests.parseLifecycle(body.toByteArray())
        val created = Instant.parse("2026-08-19T14:30:00Z")

        val hit = lifecycle.expiryOf(ObjectKey.of("a/x"), 10, emptyMap(), created, Lifecycle.DAY)
        assertEquals("on", hit?.second?.id)
        assertEquals(Instant.parse("2026-08-23T00:00:00Z"), hit?.first)

        assertNull(lifecycle.expiryOf(ObjectKey.of("b/x"), 10, emptyMap(), created, Lifecycle.DAY))
    }
}
