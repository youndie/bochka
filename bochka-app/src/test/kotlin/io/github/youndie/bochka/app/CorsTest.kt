package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CORS: конфигурация и preflight (M-93, M-94).
 *
 * Форма — `docs/spec/s3-service-2.json`: `CORSConfiguration` (`:2241`) содержит `CORSRule`
 * (`:2253`) с обязательными `AllowedMethods` и `AllowedOrigins`.
 *
 * Хранение здесь такое же скучное, как у тегов; вся логика — в `OPTIONS`. Он единственный
 * отвечает **без подписи**, и это не послабление: preflight браузер шлёт до всякой авторизации,
 * по определению RFC, и подписать его нечем.
 */
class CorsTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    private val rules =
        (
            "<CORSConfiguration><CORSRule>" +
                "<AllowedMethod>GET</AllowedMethod><AllowedMethod>PUT</AllowedMethod>" +
                "<AllowedOrigin>https://example.com</AllowedOrigin>" +
                "<AllowedHeader>x-amz-*</AllowedHeader>" +
                "<MaxAgeSeconds>3000</MaxAgeSeconds>" +
                "</CORSRule></CORSConfiguration>"
        ).toByteArray()

    @Test
    fun `бакет без CORS отвечает NoSuchCORSConfiguration`() {
        s3.createBucket("photos")

        val answer = s3.send("GET", "/photos", query = "cors")

        assertEquals(404, answer.status, answer.text)
        assertContains(answer.text, "NoSuchCORSConfiguration")
    }

    @Test
    fun `конфигурация кладётся, читается и снимается`() {
        s3.createBucket("photos")

        assertEquals(200, s3.send("PUT", "/photos", query = "cors", body = rules).status)

        val read = s3.send("GET", "/photos", query = "cors")
        assertEquals(200, read.status, read.text)
        assertContains(read.text, "<AllowedMethod>GET</AllowedMethod>")
        assertContains(read.text, "<AllowedOrigin>https://example.com</AllowedOrigin>")

        assertEquals(204, s3.send("DELETE", "/photos", query = "cors").status)
        assertEquals(404, s3.send("GET", "/photos", query = "cors").status)
    }

    @Test
    fun `preflight разрешённого источника и метода отвечает заголовками доступа`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = rules)

        val answer =
            s3.options(
                "/photos/a.txt",
                listOf("Origin" to "https://example.com", "Access-Control-Request-Method" to "GET"),
            )

        assertEquals(200, answer.status, answer.text)
        assertEquals("https://example.com", answer.header("Access-Control-Allow-Origin"))
        assertContains(answer.header("Access-Control-Allow-Methods")!!, "GET")
    }

    @Test
    fun `preflight чужого источника отвергается, а не разрешается молча`() {
        // Ответ `200` без заголовков доступа браузер прочтёт как запрет — но правильный ответ
        // здесь `403`, и разница видна тому, кто отлаживает: «правило не подошло» против
        // «правило подошло и ничего не разрешило».
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = rules)

        val answer =
            s3.options(
                "/photos/a.txt",
                listOf("Origin" to "https://evil.example", "Access-Control-Request-Method" to "GET"),
            )

        assertEquals(403, answer.status, answer.text)
        assertNull(answer.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun `preflight запрещённого метода отвергается, даже если источник разрешён`() {
        s3.createBucket("photos")
        s3.send("PUT", "/photos", query = "cors", body = rules)

        val answer =
            s3.options(
                "/photos/a.txt",
                listOf("Origin" to "https://example.com", "Access-Control-Request-Method" to "DELETE"),
            )

        assertEquals(403, answer.status, answer.text)
    }

    @Test
    fun `preflight к бакету без конфигурации отвергается`() {
        s3.createBucket("photos")

        val answer =
            s3.options(
                "/photos/a.txt",
                listOf("Origin" to "https://example.com", "Access-Control-Request-Method" to "GET"),
            )

        assertEquals(403, answer.status, answer.text)
    }

    @Test
    fun `подстановочный знак в источнике сопоставляется по правилу S3, а не регуляркой`() {
        // S3 разрешает **одну** звёздочку в любом месте источника и сопоставляет буквально всё
        // остальное. Регулярка на этом месте — это способ разрешить лишнее: точка в `example.com`
        // в ней значит «любой символ».
        s3.createBucket("photos")
        val wildcard =
            (
                "<CORSConfiguration><CORSRule>" +
                    "<AllowedMethod>GET</AllowedMethod>" +
                    "<AllowedOrigin>https://*.example.com</AllowedOrigin>" +
                    "</CORSRule></CORSConfiguration>"
            ).toByteArray()
        s3.send("PUT", "/photos", query = "cors", body = wildcard)

        fun preflight(origin: String) =
            s3.options("/photos/a.txt", listOf("Origin" to origin, "Access-Control-Request-Method" to "GET")).status

        assertEquals(200, preflight("https://app.example.com"))
        assertEquals(403, preflight("https://appXexample.com"), "точка — это точка, а не любой символ")
        assertEquals(403, preflight("http://app.example.com"), "схема сопоставляется буквально")
    }

    @Test
    fun `preflight сверяет и запрошенные заголовки, а не только метод с источником`() {
        // M-177, `test_cors_header_option:7016`. Правило разрешает метод и источник и **не
        // называет ни одного заголовка** — а браузер спрашивает про `x-amz-meta-header2`.
        // `ExposeHeaders` тут не при чём: он про то, что браузеру дадут прочитать в ответе,
        // а preflight спрашивает про `AllowedHeaders`. Не глядя на них, сервер разрешал
        // запрос, который S3 отвергает, — то есть открывал чуть шире, чем просили.
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.send(
                "PUT",
                "/photos",
                query = "cors",
                body =
                    (
                        "<CORSConfiguration><CORSRule><AllowedMethod>GET</AllowedMethod>" +
                            "<AllowedOrigin>*</AllowedOrigin>" +
                            "<ExposeHeader>x-amz-meta-header1</ExposeHeader></CORSRule></CORSConfiguration>"
                    ).toByteArray(),
            )

            val refused =
                s3.options(
                    "/photos/bar",
                    headers =
                        listOf(
                            "Origin" to "example.origin",
                            "Access-Control-Request-Method" to "GET",
                            "Access-Control-Request-Headers" to "x-amz-meta-header2",
                        ),
                )
            assertEquals(403, refused.status, refused.text)

            // Без спрошенных заголовков то же правило по-прежнему подходит: правило не стало
            // строже, строже стал вопрос.
            val allowed =
                s3.options(
                    "/photos/bar",
                    headers = listOf("Origin" to "example.origin", "Access-Control-Request-Method" to "GET"),
                )
            assertEquals(200, allowed.status, allowed.text)
        }
    }

    @Test
    fun `названный заголовок и звёздочка разрешают preflight`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")

            fun rules(allowed: String) =
                s3.send(
                    "PUT",
                    "/photos",
                    query = "cors",
                    body =
                        (
                            "<CORSConfiguration><CORSRule><AllowedMethod>GET</AllowedMethod>" +
                                "<AllowedOrigin>*</AllowedOrigin>$allowed</CORSRule></CORSConfiguration>"
                        ).toByteArray(),
                )

            fun ask(headers: String) =
                s3.options(
                    "/photos/bar",
                    headers =
                        listOf(
                            "Origin" to "example.origin",
                            "Access-Control-Request-Method" to "GET",
                            "Access-Control-Request-Headers" to headers,
                        ),
                )

            rules("<AllowedHeader>x-amz-meta-header2</AllowedHeader>")
            // Имена заголовков сравниваются без учёта регистра — так их сравнивает HTTP.
            assertEquals(200, ask("X-Amz-Meta-Header2").status)
            // Спрошены два, разрешён один — этого мало.
            assertEquals(403, ask("x-amz-meta-header2, x-amz-meta-header3").status)

            rules("<AllowedHeader>*</AllowedHeader>")
            assertEquals(200, ask("x-amz-meta-header2, anything-at-all").status)

            rules("<AllowedHeader>x-amz-*</AllowedHeader>")
            assertEquals(200, ask("x-amz-meta-header2").status)
            assertEquals(403, ask("x-other").status)
        }
    }
}
