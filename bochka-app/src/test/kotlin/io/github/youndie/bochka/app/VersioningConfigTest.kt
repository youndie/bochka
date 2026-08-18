package io.github.youndie.bochka.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `?versioning` — конфигурация версионирования бакета (M-103).
 *
 * Главный тест здесь — про бакет, которого никто не настраивал. `s3-service-2.json` описывает
 * `GetBucketVersioningOutput.Status` как необязательное поле, то есть пустой
 * `VersioningConfiguration` — это ответ, а не отказ. Этот репозиторий уже платил за обратное:
 * `NotImplemented` на `?versions` инструмент прочитал как «сервер сломан» и увёл за собой
 * 837 кейсов в чужой уборке (M3).
 */
class VersioningConfigTest {
    @Test
    fun `a bucket nobody configured answers with an empty configuration`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer = s3.send("GET", "/photos", query = "versioning")

            assertEquals(200, answer.status, answer.text)
            assertTrue("VersioningConfiguration" in answer.text, answer.text)
            assertTrue("Status" !in answer.text, "нет статуса, а не статус «выключено»: ${answer.text}")
        }
    }

    @Test
    fun `Enabled is stored and read back`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val put =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "versioning",
                    body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
                )

            assertEquals(200, put.status, put.text)
            assertTrue("<Status>Enabled</Status>" in s3.send("GET", "/photos", query = "versioning").text)
        }
    }

    @Test
    fun `Suspended is not the same as never configured`() {
        // Два разных ответа, и разница не косметическая: приостановленный бакет может держать
        // версии, сделанные пока он был включён.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>".toByteArray(),
            )

            s3.send(
                "PUT",
                "/photos",
                query = "versioning",
                body = "<VersioningConfiguration><Status>Suspended</Status></VersioningConfiguration>".toByteArray(),
            )

            assertTrue("<Status>Suspended</Status>" in s3.send("GET", "/photos", query = "versioning").text)
        }
    }

    @Test
    fun `Disabled is refused rather than accepted and ignored`() {
        // Обратно в «не настроен» S3 не умеет, и мы тоже. Принять `Disabled` значило бы оставить
        // клиента в уверенности, что версии перестали храниться.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            val answer =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "versioning",
                    body = "<VersioningConfiguration><Status>Disabled</Status></VersioningConfiguration>".toByteArray(),
                )

            assertEquals(400, answer.status, answer.text)
        }
    }
}
