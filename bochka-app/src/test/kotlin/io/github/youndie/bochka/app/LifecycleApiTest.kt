package io.github.youndie.bochka.app

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `?lifecycle` in full: three methods, five refusals and the `x-amz-expiration` header.
 *
 * The shape is in `docs/spec/s3-service-2.json`, `BucketLifecycleConfiguration` (`:2127`) and
 * `LifecycleRule` (`:7896`). The request bodies were taken off botocore rather than invented: half
 * the assertions here are about what the document **arrives as** rather than about what it looks
 * like in the documentation.
 */
class LifecycleApiTest {
    @Test
    fun `a bucket with no rules answers with a named refusal rather than an empty document`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer = s3.send("GET", "/photos", query = "lifecycle")

            assertEquals(404, answer.status, answer.text)
            assertTrue("NoSuchLifecycleConfiguration" in answer.text, answer.text)
        }
    }

    @Test
    fun `rules are put, read and removed`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val put = s3.send("PUT", "/photos", query = "lifecycle", body = TWO_RULES.toByteArray())
            assertEquals(200, put.status, put.text)

            val read = s3.send("GET", "/photos", query = "lifecycle")
            assertEquals(200, read.status, read.text)
            assertTrue("<ID>test1/</ID>" in read.text, read.text)
            assertTrue("<Days>31</Days>" in read.text, read.text)
            assertTrue("<Days>120</Days>" in read.text, read.text)
            // The prefix left as the same member it arrived as: `test_lifecycle_get:8451` compares
            // whole rules, and a rule with `<Filter>` instead of `<Prefix>` is a different rule.
            assertTrue("<Prefix>test1/</Prefix>" in read.text, read.text)
            assertFalse("<Filter>" in read.text, read.text)

            assertEquals(204, s3.send("DELETE", "/photos", query = "lifecycle").status)
            assertEquals(404, s3.send("GET", "/photos", query = "lifecycle").status)
            // And once more against nothing: `test_lifecycle_delete:8462` pins `204` on both sides
            // — before the rules are put in place and after they are removed.
            assertEquals(204, s3.send("DELETE", "/photos", query = "lifecycle").status)
        }
    }

    @Test
    fun `a rule without an identifier gets one on the write and keeps it on the read`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            val body =
                "<LifecycleConfiguration><Rule><Expiration><Days>31</Days></Expiration>" +
                    "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule></LifecycleConfiguration>"

            assertEquals(200, s3.send("PUT", "/photos", query = "lifecycle", body = body.toByteArray()).status)

            val first = s3.send("GET", "/photos", query = "lifecycle").text
            assertTrue("<ID>" in first, first)
            // The same one on a second read rather than a new one every time: the identifier was
            // invented once and lives in the stored document.
            assertEquals(first, s3.send("GET", "/photos", query = "lifecycle").text)
        }
    }

    @Test
    fun `a broken document and an unenforceable one are refused with different codes`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            fun refusal(body: String) = s3.send("PUT", "/photos", query = "lifecycle", body = body.toByteArray())

            // `Status: enabled` — the document is not a document (`ExpirationStatus`, `:4881`).
            val status = refusal(rule("<Expiration><Days>2</Days></Expiration>", status = "enabled"))
            assertEquals(400, status.status, status.text)
            assertTrue("MalformedXML" in status.text, status.text)

            // Everything below parses and cannot be carried out.
            val unworkable =
                listOf(
                    "zero days" to rule("<Expiration><Days>0</Days></Expiration>"),
                    "a long ID" to rule("<Expiration><Days>2</Days></Expiration>", id = "a".repeat(256)),
                    "a date that is not midnight" to rule("<Expiration><Date>1970-08-22T19:08:21Z</Date></Expiration>"),
                    "a transition between storage classes" to
                        rule(
                            "<Expiration><Date>2023-09-27T00:00:00Z</Date></Expiration>" +
                                "<Transition><Date>2030-01-01T00:00:00Z</Date>" +
                                "<StorageClass>GLACIER</StorageClass></Transition>",
                        ),
                )
            for ((what, body) in unworkable) {
                val answer = refusal(body)
                assertEquals(400, answer.status, "$what: ${answer.text}")
                assertTrue("InvalidArgument" in answer.text, "$what: ${answer.text}")
            }

            // And none of the refusals left a configuration behind.
            assertEquals(404, s3.send("GET", "/photos", query = "lifecycle").status)
        }
    }

    @Test
    fun `two rules with one identifier are a refusal`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            val body =
                "<LifecycleConfiguration>" +
                    "<Rule><ID>rule1</ID><Expiration><Days>1</Days></Expiration>" +
                    "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule>" +
                    "<Rule><ID>rule1</ID><Expiration><Days>2</Days></Expiration>" +
                    "<Prefix>test2/</Prefix><Status>Enabled</Status></Rule>" +
                    "</LifecycleConfiguration>"

            val answer = s3.send("PUT", "/photos", query = "lifecycle", body = body.toByteArray())

            assertEquals(400, answer.status, answer.text)
            assertTrue("InvalidArgument" in answer.text, answer.text)
        }
    }

    @Test
    fun `an empty filter arrives as a self-closing element and is accepted`() {
        // Exactly the body botocore puts on the wire for `Filter: {}`
        // (`test_lifecycle_set_empty_filter:9349`). Until M23 the server answered it with
        // `MalformedXML`, because the XML reader refused `<x/>` — and the standard client has no
        // other form.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            val body =
                "<LifecycleConfiguration><Rule><ID>rule1</ID>" +
                    "<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>" +
                    "<Filter /><Status>Enabled</Status></Rule></LifecycleConfiguration>"

            assertEquals(200, s3.send("PUT", "/photos", query = "lifecycle", body = body.toByteArray()).status)
        }
    }

    @Test
    fun `x-amz-expiration answers on a write and on a read`() {
        // `test_lifecycle_expiration_header_put:9162` and `…_head:9174`. The header's form is
        // `expiry-date="…", rule-id="…"`, and the case parses it with a regular expression — so it
        // checks that form rather than the presence of something.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send("PUT", "/photos", query = "lifecycle", body = rule().toByteArray())

            val written = s3.put("photos", "days1/foo", "bar")
            val header = written.header("x-amz-expiration")
            assertNotNull(header, "the header is missing on the write")
            assertTrue(Regex("""expiry-date="(.+)", rule-id="rule1"""").containsMatchIn(header), header)
            // Midnight UTC rather than "a day from now": the rounding is part of what S3 promises.
            assertTrue(header.contains("00:00:00 GMT"), header)

            assertEquals(header, s3.send("HEAD", "/photos/days1/foo").header("x-amz-expiration"))
            assertEquals(header, s3.get("photos", "days1/foo").header("x-amz-expiration"))
        }
    }

    @Test
    fun `x-amz-expiration is absent when the object does not match the rule`() {
        // The second half of `test_lifecycle_expiration_header_tags_head:9192`, and the half that is
        // easy to leave undone: the case puts a rule on a tag, reads the header, changes the tag in
        // the rule, and demands the header be gone.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "obj_key1", "body")
            s3.send("PUT", "/photos/obj_key1", query = "tagging", body = TAGGING.toByteArray())

            s3.send("PUT", "/photos", query = "lifecycle", body = taggedRule("key1", "tag1").toByteArray())
            assertNotNull(s3.send("HEAD", "/photos/obj_key1").header("x-amz-expiration"))

            s3.send("PUT", "/photos", query = "lifecycle", body = taggedRule("key2", "tag1").toByteArray())
            assertNull(s3.send("HEAD", "/photos/obj_key1").header("x-amz-expiration"))

            // And an object outside the prefix carries no header either, under the same rule.
            s3.send("PUT", "/photos", query = "lifecycle", body = rule().toByteArray())
            s3.put("photos", "elsewhere/foo", "bar")
            assertNull(s3.send("HEAD", "/photos/elsewhere/foo").header("x-amz-expiration"))
        }
    }

    @Test
    fun `a shortened day shows in the header, not only in the sweep`() {
        // The unit of a "day" reaches the header from the same setting the sweep deletes on. If the
        // header were always computed in twenty-four hours, the server would promise one term and
        // delete on another — and the only way to see that would be a vanished object.
        S3Fixture(lifecycleDay = Duration.ofSeconds(10)).use { s3 ->
            s3.createBucket("photos")
            s3.send("PUT", "/photos", query = "lifecycle", body = rule().toByteArray())

            val header = s3.put("photos", "days1/foo", "bar").header("x-amz-expiration")

            assertNotNull(header)
            // Ten seconds from now is today, not the midnight a day away.
            assertFalse(header.contains("00:00:00 GMT"), header)
        }
    }

    private companion object {
        val TWO_RULES =
            "<LifecycleConfiguration>" +
                "<Rule><ID>test1/</ID><Expiration><Days>31</Days></Expiration>" +
                "<Prefix>test1/</Prefix><Status>Enabled</Status></Rule>" +
                "<Rule><ID>test2/</ID><Expiration><Days>120</Days></Expiration>" +
                "<Prefix>test2/</Prefix><Status>Enabled</Status></Rule>" +
                "</LifecycleConfiguration>"

        val TAGGING =
            "<Tagging><TagSet><Tag><Key>key1</Key><Value>tag1</Value></Tag>" +
                "<Tag><Key>key5</Key><Value>tag5</Value></Tag></TagSet></Tagging>"

        fun rule(
            what: String = "<Expiration><Days>1</Days></Expiration>",
            id: String = "rule1",
            status: String = "Enabled",
            prefix: String = "days1/",
        ) = "<LifecycleConfiguration><Rule><ID>$id</ID>$what" +
            "<Prefix>$prefix</Prefix><Status>$status</Status></Rule></LifecycleConfiguration>"

        fun taggedRule(
            key: String,
            value: String,
        ) = "<LifecycleConfiguration><Rule><ID>rule1</ID><Expiration><Days>1</Days></Expiration>" +
            "<Filter><Tag><Key>$key</Key><Value>$value</Value></Tag></Filter>" +
            "<Status>Enabled</Status></Rule></LifecycleConfiguration>"
    }
}
