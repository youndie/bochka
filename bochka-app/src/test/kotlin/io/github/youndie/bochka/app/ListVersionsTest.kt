package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /<bucket>?versions` (M-107).
 *
 * До этой вехи операция отвечала обычным листингом в другой обёртке — то есть говорила, что у
 * бакета по одной версии всего и ничего никогда не удалялось. Документ при этом был правильной
 * формы, и снаружи отличить его от правды было нельзя; поэтому тесты здесь считают **строки**,
 * а не проверяют статус.
 */
class ListVersionsTest {
    private fun S3Fixture.enable(bucket: String) {
        createBucket(bucket)
        send(
            "PUT",
            "/$bucket",
            query = "versioning",
            body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
        )
    }

    @Test
    fun `every version is a row, newest first and only one of them latest`() {
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            s3.put("photos", "a.txt", "второй")

            val body = s3.send("GET", "/photos", query = "versions").text

            assertEquals(2, Regex("<Version>").findAll(body).count(), body)
            assertEquals(1, Regex("<IsLatest>true</IsLatest>").findAll(body).count(), body)
            // Новая первой: этот порядок — определение операции, и он же определение того,
            // какая версия текущая.
            assertTrue(
                body.indexOf("<IsLatest>true</IsLatest>") < body.indexOf("<IsLatest>false</IsLatest>"),
                body,
            )
        }
    }

    @Test
    fun `a tombstone is a DeleteMarker row, not a Version with zero bytes`() {
        // Надгробие без ETag и Size — иначе клиент сравнит его с пустым объектом и найдёт равными.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            s3.put("photos", "a.txt", "первый")
            s3.send("DELETE", "/photos/a.txt")

            val body = s3.send("GET", "/photos", query = "versions").text

            assertEquals(1, Regex("<DeleteMarker>").findAll(body).count(), body)
            assertEquals(1, Regex("<Version>").findAll(body).count(), body)
            val marker = body.substring(body.indexOf("<DeleteMarker>"), body.indexOf("</DeleteMarker>"))
            assertTrue("<ETag>" !in marker && "<Size>" !in marker, marker)
        }
    }

    @Test
    fun `a bucket without versioning lists its objects at version null`() {
        // Это уже работало (M3) и должно продолжать: чужая уборка зовёт операцию перед каждым
        // тестом, и отказ здесь стоил 837 кейсов.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "a.txt", "первый")

            val body = s3.send("GET", "/photos", query = "versions").text

            assertTrue("<VersionId>null</VersionId>" in body, body)
            assertTrue("<Key>a.txt</Key>" in body, body)
        }
    }

    @Test
    fun `a page can end inside a key, and the two markers resume it`() {
        // Ради этого маркеров два: страница обрывается посреди версий одного ключа, и `key-marker`
        // в одиночку смог бы продолжить только с границы ключа — то есть либо повторив версии,
        // либо потеряв их.
        S3Fixture().use { s3 ->
            s3.enable("photos")
            repeat(3) { s3.put("photos", "a.txt", "версия $it") }

            val first = s3.send("GET", "/photos", query = "versions&max-keys=2").text
            assertEquals(2, Regex("<Version>").findAll(first).count(), first)
            assertTrue("<IsTruncated>true</IsTruncated>" in first, first)

            val keyMarker = first.substringAfter("<NextKeyMarker>").substringBefore("</NextKeyMarker>")
            val versionMarker =
                first.substringAfter("<NextVersionIdMarker>").substringBefore("</NextVersionIdMarker>")
            val second =
                s3
                    .send(
                        "GET",
                        "/photos",
                        query = "versions&key-marker=$keyMarker&version-id-marker=$versionMarker",
                    ).text

            assertEquals(1, Regex("<Version>").findAll(second).count(), second)
            assertTrue("версия 0" !in second, "продолжение не должно повторять уже отданное")
        }
    }
}
