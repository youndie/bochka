package io.github.youndie.bochka.s3

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Политика POST-формы (M-101), по форме из `test_post_object_authenticated_request:1970`.
 *
 * Документ здесь пишется руками ровно так, как его пишет сьют, — с теми же пробелами и порядком.
 * Собрать его сериализатором значило бы проверять согласие двух наших представлений вместо
 * согласия с тем, что приезжает от клиента.
 */
class PostPolicyTest {
    private fun encode(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())

    private val now = Instant.parse("2026-08-18T12:00:00Z")

    private val document =
        """
        {"expiration": "2026-08-18T13:00:00Z",
         "conditions": [
          {"bucket": "photos"},
          ["starts-with", "${'$'}key", "foo"],
          {"acl": "private"},
          ["starts-with", "${'$'}Content-Type", "text/plain"],
          ["content-length-range", 0, 1024]
         ]
        }
        """.trimIndent()

    private fun fields(vararg extra: Pair<String, String>) =
        mapOf(
            "bucket" to "photos",
            "key" to "foo.txt",
            "acl" to "private",
            "content-type" to "text/plain",
        ) + extra

    @Test
    fun `условия всех четырёх форм разбираются`() {
        val policy = PostPolicy.decode(encode(document))

        assertEquals(Instant.parse("2026-08-18T13:00:00Z"), policy.expiration)
        assertEquals(5, policy.conditions.size)
        assertEquals(PostPolicy.Condition.Exact("bucket", "photos"), policy.conditions[0])
        assertEquals(PostPolicy.Condition.StartsWith("key", "foo"), policy.conditions[1])
        assertEquals(PostPolicy.Condition.LengthRange(0, 1024), policy.conditions[4])
    }

    @Test
    fun `форма, подходящая под все условия, проходит`() {
        val policy = PostPolicy.decode(encode(document))

        PostPolicy.check(policy, fields(), fileLength = 3, now = now)
    }

    @Test
    fun `истёкшая политика отвергается прежде всего остального`() {
        val policy = PostPolicy.decode(encode(document))

        val refused =
            assertFailsWith<PostPolicy.Refused> {
                PostPolicy.check(policy, fields(), 3, Instant.parse("2026-08-18T14:00:00Z"))
            }
        assertEquals(403, refused.error.status)
    }

    @Test
    fun `ключ, не начинающийся с разрешённого префикса, отвергается`() {
        val policy = PostPolicy.decode(encode(document))

        assertFailsWith<PostPolicy.Refused> {
            PostPolicy.check(policy, fields("key" to "bar.txt"), 3, now)
        }
    }

    @Test
    fun `файл за границами диапазона — EntityTooLarge, а не отказ в доступе`() {
        // Разные коды, потому что клиент чинит разное: превышение длины — это его файл,
        // а отказ в доступе — его подпись.
        val policy = PostPolicy.decode(encode(document))

        val refused = assertFailsWith<PostPolicy.Refused> { PostPolicy.check(policy, fields(), 2000, now) }
        assertEquals("EntityTooLarge", refused.error.code)
    }

    @Test
    fun `поле, которого политика не покрыла, отвергается`() {
        // Главная проверка файла. Подписавший разрешил конкретный набор; поле сверх него — это
        // то, чего он не разрешал, и пропустить его значит дать загружающему подставить
        // что угодно к чужой подписи.
        val policy = PostPolicy.decode(encode(document))

        assertFailsWith<PostPolicy.Refused> {
            PostPolicy.check(policy, fields("x-amz-meta-secret" to "подставлено"), 3, now)
        }
    }

    @Test
    fun `подпись и её спутники условия не требуют`() {
        // Требовать условие на `signature` значило бы требовать от клиента подписать свою подпись.
        val policy = PostPolicy.decode(encode(document))

        PostPolicy.check(
            policy,
            fields("policy" to "…", "signature" to "…", "awsaccesskeyid" to "k", "x-ignore-extra" to "z"),
            3,
            now,
        )
    }

    @Test
    fun `политика не в base64 отвергается как малформед, а не как отказ в доступе`() {
        val refused = assertFailsWith<PostPolicy.Refused> { PostPolicy.decode("не base64!!") }
        assertEquals("MalformedPolicyDocument", refused.error.code)
    }

    @Test
    fun `политика без expiration отвергается`() {
        // `test_post_object_missing_expires_condition:2814` ждёт 400. Политика без срока — это
        // бессрочный пропуск, и выдавать его молча тому, кто про срок забыл, нельзя.
        val refused =
            assertFailsWith<PostPolicy.Refused> {
                PostPolicy.decode(encode("""{"conditions": [{"bucket": "photos"}]}"""))
            }
        assertEquals("MalformedPolicyDocument", refused.error.code)
    }

    @Test
    fun `expiration читается с учётом регистра`() {
        // `test_post_object_expires_is_case_sensitive:2654`. `EXPIRATION` — это не срок,
        // а опечатка, и молчаливо превратить её в «без срока» значит снять ограничение.
        assertFailsWith<PostPolicy.Refused> {
            PostPolicy.decode(encode("""{"EXPIRATION": "2099-01-01T00:00:00Z", "conditions": []}"""))
        }
    }

    @Test
    fun `политика без conditions отвергается`() {
        // `test_post_object_missing_conditions_list:2814`. Пустой список условий разрешал бы
        // положить что угодно куда угодно — это не «политика без ограничений», это не политика.
        assertFailsWith<PostPolicy.Refused> {
            PostPolicy.decode(encode("""{"expiration": "2099-01-01T00:00:00Z"}"""))
        }
    }

    @Test
    fun `экранирование JSON снимается до сравнения`() {
        // `test_post_object_escaped_field_values:2257`: условие подписано на префикс из обратного
        // слэша и доллара, а в документе слэш удвоен. Сравнение байт в байт потребовало бы от
        // клиента лишний слэш и отвергло бы форму, которую политика разрешает.
        //
        // Строки здесь склеены из символов, а не написаны литералом: доллар и слэш экранируются
        // и в Kotlin, и в JSON, и литерал, читающийся правильно, обычно означает не то.
        val prefix = "" + '\\' + '$' + "foo"
        val json =
            """{"expiration": "2099-01-01T00:00:00Z", "conditions": """ +
                """[["starts-with", "${'$'}key", "\\${'$'}foo"]]}"""

        val policy = PostPolicy.decode(encode(json))

        assertEquals(PostPolicy.Condition.StartsWith("key", prefix), policy.conditions[0])
        PostPolicy.check(policy, mapOf("key" to "$prefix.txt"), fileLength = 3, now = now)
    }
}
