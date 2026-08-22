package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.Credentials
import io.github.youndie.bochka.s3.sigv4.S3Error
import io.github.youndie.bochka.s3.sigv4.Sigv4
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подпись POST-формы — единственное место сервера, где встречаются **две** версии подписи.
 *
 * `ceph/s3-tests` шлёт вторую (`test_post_object_authenticated_request:1962`): поля
 * `AWSAccessKeyId` и `signature`, где подпись — base64 от `HMAC-SHA1(секрет, политика)`. Свежие
 * клиенты шлют четвёртую: `x-amz-algorithm`, `x-amz-credential`, `x-amz-date`, `x-amz-signature`.
 * Поддерживать надо обе, и выбор — по наличию поля, а не по версии клиента.
 *
 * Подписывается **та строка base64, что приехала**, а не пересобранная. Разбор политики
 * ([PostPolicy]) и её подпись — независимые операции над одним и тем же куском текста, и путать
 * их порядок нельзя: пересобранный JSON отличается пробелами, и подпись от него не сойдётся.
 *
 * Формы подписи здесь только две, и третьей — анонимной формы без подписи вовсе — здесь нет
 * намеренно: у неё нечего проверять. Такая форма до этого объекта не доходит, её решает вызывающий
 * (`S3Handler.postObject`, M-225) теми же двумя воротами, что и всякий запрос без учётных данных:
 * рубильник `BOCHKA_ANONYMOUS` и ACL бакета. Раньше здесь было записано, что бакета, открытого
 * на запись всем, этот сервер не заводит; с M27 заводит (`public-read-write` хранится
 * и исполняется), а с M28 такой запрос вообще доходит до модели доступа.
 */
object PostSignature {
    class Refused(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    /**
     * Проверяет подпись формы и возвращает идентификатор ключа, которым она подписана.
     *
     * @param fields поля формы, имена в нижнем регистре
     * @param policy строка base64 ровно так, как она приехала в поле `policy`
     */
    fun verify(
        fields: Map<String, String>,
        policy: String,
        credentials: Credentials,
        region: String,
    ): String {
        val v4 = fields["x-amz-signature"]
        val v2 = fields["signature"]
        return when {
            v4 != null -> verifyV4(fields, policy, v4, credentials, region)

            v2 != null -> verifyV2(fields, policy, v2, credentials)

            // A policy with no signature is an incomplete form, not a refused one: nobody has
            // been denied anything, the request is simply missing a part it declared. `400`, and
            // `test_post_object_missing_signature:2486` pins it. A form with neither policy nor
            // signature never reaches here — that one named nobody, and what it may do is the
            // bucket's ACL to say (M-225).
            else -> throw Refused(S3Error.MALFORMED_POST_REQUEST, "the form has a policy but no signature")
        }
    }

    private fun verifyV2(
        fields: Map<String, String>,
        policy: String,
        signature: String,
        credentials: Credentials,
    ): String {
        val accessKeyId =
            fields["awsaccesskeyid"]
                ?: throw Refused(S3Error.ACCESS_DENIED, "signature without AWSAccessKeyId")
        val secret =
            credentials.secretFor(accessKeyId)
                ?: throw Refused(S3Error.INVALID_ACCESS_KEY_ID, "unknown access key '$accessKeyId'")

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val expected = Base64.getEncoder().encodeToString(mac.doFinal(policy.toByteArray(Charsets.UTF_8)))

        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), signature.toByteArray(Charsets.UTF_8))) {
            throw Refused(S3Error.SIGNATURE_DOES_NOT_MATCH, "the v2 form signature does not match")
        }
        return accessKeyId
    }

    private fun verifyV4(
        fields: Map<String, String>,
        policy: String,
        signature: String,
        credentials: Credentials,
        region: String,
    ): String {
        val credential =
            fields["x-amz-credential"]
                ?: throw Refused(S3Error.ACCESS_DENIED, "x-amz-signature without x-amz-credential")
        // `<key>/<yyyyMMdd>/<region>/s3/aws4_request`. The key itself may contain no slash, so the
        // scope is taken from the tail: splitting from the head would break on a key that does.
        val parts = credential.split('/')
        if (parts.size != 5) throw Refused(S3Error.ACCESS_DENIED, "x-amz-credential is not a five-part scope")
        val (accessKeyId, date, credentialRegion, service, terminator) = parts

        if (service != "s3" || terminator != "aws4_request") {
            throw Refused(S3Error.ACCESS_DENIED, "x-amz-credential scope is not s3/aws4_request")
        }
        if (credentialRegion != region) {
            throw Refused(S3Error.SIGNATURE_DOES_NOT_MATCH, "region '$credentialRegion' is not '$region'")
        }
        val secret =
            credentials.secretFor(accessKeyId)
                ?: throw Refused(S3Error.INVALID_ACCESS_KEY_ID, "unknown access key '$accessKeyId'")

        val expected = Sigv4.signature(Sigv4.signingKey(secret, date, credentialRegion, service), policy)
        if (!Sigv4.signaturesMatch(expected, signature)) {
            throw Refused(S3Error.SIGNATURE_DOES_NOT_MATCH, "the v4 form signature does not match")
        }
        return accessKeyId
    }

    private operator fun <T> List<T>.component4(): T = this[3]

    private operator fun <T> List<T>.component5(): T = this[4]
}
