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
 * Lifecycle rules: `docs/spec/s3-service-2.json` — `BucketLifecycleConfiguration`
 * (`:2127`), `LifecycleRule` (`:7896`), `LifecycleRuleFilter` (`:7960`),
 * `LifecycleRuleAndOperator` (`:7936`), `ExpirationStatus` (`:4881`).
 *
 * **The bodies here are not invented.** Every one was taken off botocore: the same call the suite
 * makes, captured with a `before-send` hook. That matters in exactly three places where an invented
 * body would have been different: `<Filter />` and `<Prefix />` arrive self-closing, `Date` arrives
 * as an instant (`2017-09-27T00:00:00Z`) rather than as a date, and a date that is meant to be
 * invalid arrives **valid**, because the client read it as seconds since the epoch.
 */
class LifecycleTest {
    @Test
    fun `a rule with a prefix of its own parses and comes back as the same document`() {
        // `test_lifecycle_get:8451` compares whole rules: what the rule arrived as is part of what
        // it says.
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
        // The prefix left where it arrived, and no `<Filter>` appeared.
        assertTrue("<Prefix>test1/</Prefix>" in rendered, rendered)
        assertFalse("<Filter>" in rendered, rendered)
    }

    @Test
    fun `a disabled rule is stored disabled`() {
        val body =
            "<LifecycleConfiguration><Rule><ID>rule2</ID><Expiration><Days>2</Days></Expiration>" +
                "<Prefix>test2/</Prefix><Status>Disabled</Status></Rule></LifecycleConfiguration>"

        val parsed = S3Requests.parseLifecycle(body.toByteArray())

        assertFalse(parsed.rules.single().enabled)
        assertTrue(parsed.enabled.isEmpty())
        assertTrue("<Status>Disabled</Status>" in String(S3Documents.lifecycleResult(parsed)))
    }

    @Test
    fun `a rule without an identifier is given one`() {
        // `test_lifecycle_get_no_id:8494` demands an `ID` in the answer for a rule that arrived
        // without one. There is nobody else to invent it, and it is invented once, on the write.
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
    fun `Status parses in exactly two spellings`() {
        // `ExpirationStatus` is an enumeration of two values (`:4881`), and `enabled` is not one of
        // them. `test_lifecycle_invalid_status:9037` expects `MalformedXML` specifically: the
        // document is not a document, rather than "the value is wrong".
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
    fun `an identifier that is too long, and a repeated one, are InvalidArgument`() {
        // `test_lifecycle_id_too_long:9012` and `test_lifecycle_same_id:9024`. Both are about a
        // document that parses and cannot be carried out, which is why their code differs from a
        // malformed one's.
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

        // Exactly 255 is the bound, not the refusal.
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
    fun `zero days on an expiration is InvalidArgument`() {
        // `test_lifecycle_expiration_days0:9111`, and the case's own comment explains why this is
        // not "a refusal like any other": zero days is legal on a transition and not on an
        // expiration.
        val body =
            "<LifecycleConfiguration><Rule><ID>rule1</ID><Expiration><Days>0</Days></Expiration>" +
                "<Prefix>days0/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"

        assertFailsWith<S3Requests.InvalidArgument> { S3Requests.parseLifecycle(body.toByteArray()) }
    }

    @Test
    fun `an expiration date has to be midnight UTC`() {
        // Both bodies were taken off botocore. The first is `Date: '2017-09-27'`
        // (`test_lifecycle_set_date:9065`); the second is `Date: '20200101'`
        // (`test_lifecycle_set_invalid_date:9075`), which the client read as seconds since the epoch
        // and turned into a valid date at 19:08:21. The only thing that tells them apart is the
        // rule "the time is always midnight": without it the second case passes and a rule expiring
        // in the middle of a day stays in the bucket.
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
    fun `a rule with a transition is refused by name`() {
        // There is one storage class because there is one disk.
        // `test_lifecycle_transition_set_invalid_date:9476` expects `400` from this body and gets
        // it — but for the transition rather than for the date, and that is written down here so
        // the case does not look like it passes for the reason it was written for.
        val body =
            "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                "<Expiration><Date>2023-09-27T00:00:00Z</Date></Expiration>" +
                "<Transition><Date>1970-08-23T00:55:27Z</Date><StorageClass>GLACIER</StorageClass></Transition>" +
                "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"

        assertFailsWith<S3Requests.InvalidArgument> { S3Requests.parseLifecycle(body.toByteArray()) }
    }

    @Test
    fun `an empty filter is a filter that matches everything`() {
        // The botocore body for `Filter: {}` (`test_lifecycle_set_empty_filter:9349`).
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
    fun `filter conditions add up, however many are named`() {
        // The botocore body for `setup_lifecycle_tags2:8667`: `Prefix`, `Tag` and `And` in one
        // filter. S3 refuses such a document — the case is marked `fails_on_aws` — while here it is
        // accepted and means "all of them at once".
        val body =
            "<LifecycleConfiguration><Rule><Expiration><Days>1</Days></Expiration><ID>rule_tag1</ID>" +
                "<Filter><Prefix>days1/</Prefix><Tag><Key>tom</Key><Value>sawyer</Value></Tag>" +
                "<And><Prefix>days1</Prefix><Tag><Key>huck</Key><Value>finn</Value></Tag></And></Filter>" +
                "<Status>Enabled</Status></Rule></LifecycleConfiguration>"

        val rule = S3Requests.parseLifecycle(body.toByteArray()).rules.single()

        // Tom carries only his own tag; Huck carries both.
        assertFalse(rule.matches(ObjectKey.of("days1/tom"), 8, mapOf("tom" to "sawyer")))
        assertTrue(rule.matches(ObjectKey.of("days1/huck"), 9, mapOf("tom" to "sawyer", "huck" to "finn")))
        // The prefix still has to match, even once the tags do.
        assertFalse(rule.matches(ObjectKey.of("elsewhere/huck"), 9, mapOf("tom" to "sawyer", "huck" to "finn")))
    }

    @Test
    fun `size is compared strictly on both sides`() {
        // The botocore body for `test_lifecycle_expiration_size_gt:8909`: an empty prefix arrives as
        // a self-closing element.
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
    fun `noncurrent versions and abandoned uploads are members of their own`() {
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
    fun `a document that has been through the writer parses into the same thing`() {
        // A round trip rather than a comparison with itself: what was parsed is written, what was
        // written is parsed again, and the two models have to agree. That is what the server does
        // between a `PUT` and a `GET`.
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
    fun `a term in days rounds up to midnight UTC`() {
        // The S3 rule: the expiry date is the creation date plus `Days`, rounded up to the next
        // midnight UTC. An object created at 14:30 lives until the midnight a little over a day
        // later.
        val created = Instant.parse("2026-08-19T14:30:00Z")
        val expiration = Lifecycle.Expiration(days = 1)

        assertEquals(
            Instant.parse("2026-08-21T00:00:00Z"),
            Lifecycle.expiresAt(expiration, created, Lifecycle.DAY),
        )
        // Already midnight: nothing to round, and no extra day is added.
        assertEquals(
            Instant.parse("2026-08-21T00:00:00Z"),
            Lifecycle.expiresAt(expiration, Instant.parse("2026-08-20T00:00:00Z"), Lifecycle.DAY),
        )
    }

    @Test
    fun `a shortened day is not rounded at all`() {
        // S3 rounds because its day is a calendar one. A five-second unit has no calendar, and
        // rounding it "to midnight" would push the term a whole day out — which would undo the
        // shortening the unit is shortened for.
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
    fun `a term given as a date is taken as it is, and a tombstone rule gives no term at all`() {
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
    fun `an object's term comes from the first matching rule, and disabled ones are not looked at`() {
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
