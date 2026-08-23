package io.github.youndie.bochka.embedded

import io.github.youndie.bochka.http.HttpHandler
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.http.HttpResponse
import io.github.youndie.bochka.s3.sigv4.S3Error
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Отказы по заказу — единственное, в чём мок структурно сильнее настоящего сервера.
 *
 * Настоящее хранилище нельзя попросить ответить `503`: оно отвечает правду. А клиентский код,
 * который никто не может уронить, **не проверен на повторах** — и узнают об этом в проде, когда
 * повтор впервые понадобится. Мок это умеет от рождения, и это его единственное настоящее
 * преимущество; здесь оно берётся себе, ничего не отдавая взамен.
 *
 * Обёртка вокруг обработчика, а не правка сервера: путь запроса остаётся тем же самым, который
 * работает в проде, и выключенная обёртка — это одно чтение поля. Отказ вводится **до** разбора
 * и подписи, потому что клиент, проверяющий повторы, обычно проверяет их на пятисотых, а не
 * на отказе в доступе.
 */
internal class InjectedFailures(
    private val next: HttpHandler,
) : HttpHandler {
    private val remaining = AtomicInteger(0)
    private val status = AtomicReference(503)

    /**
     * Ответить [status] на следующие [times] запросов, каких бы то ни было.
     *
     * Статус, которому эта обёртка не может назвать код ошибки, **отвергается** (M-231). Клиент
     * переключается по коду, а не по числу, и заказанный `403`, приехавший как `InternalError`,
     * делает тест про отказ в доступе тестом про сбой сервера — причём зелёным.
     */
    fun failNext(
        status: Int,
        times: Int,
    ) {
        require(status in INJECTABLE) {
            "no S3 error code follows from status $status alone; injectable statuses are " +
                INJECTABLE.keys.sorted().joinToString(", ")
        }
        this.status.set(status)
        remaining.set(times)
    }

    fun clear() = remaining.set(0)

    override fun screen(head: HttpRequestParser.Head): HttpResponse? {
        // `getAndUpdate`, а не «прочитать и уменьшить»: два запроса сразу иначе оба увидят
        // единицу, и заказанный один отказ случится дважды. Тест, который так плавает, хуже
        // отсутствующего.
        val before = remaining.getAndUpdate { if (it > 0) it - 1 else 0 }
        if (before <= 0) return next.screen(head)

        val code = status.get()
        return HttpResponse(
            code,
            reasonFor(code),
            headers = listOf("Content-Type" to "application/xml"),
            body = document(code),
            contentLength = document(code).size.toLong(),
        )
    }

    override suspend fun handle(
        head: HttpRequestParser.Head,
        body: HttpHandler.RequestBody,
    ): HttpResponse = next.handle(head, body)

    override fun failed(
        head: HttpRequestParser.Head,
        cause: Throwable,
    ): HttpResponse = next.failed(head, cause)

    private fun reasonFor(code: Int) = INJECTABLE.getValue(code).reason

    /**
     * Тело ошибки настоящей формы, а не пустой ответ.
     *
     * Клиент, который проверяет свои повторы, разбирает ответ — и на пустом теле падает внутри
     * своего парсера вместо того, чтобы сделать повтор. Ровно это уже случалось здесь с `412`
     * без тела: статус ошибки без документа ошибки — другая поломка, а не более короткая.
     *
     * Тело — единственное место, где код виден на проводе, и оттого единственное, по которому
     * его вообще можно проверить: у `HEAD` тела нет, и клиент там называет код **сам** по статусу.
     */
    private fun document(code: Int): ByteArray =
        (
            """<?xml version="1.0" encoding="UTF-8"?><Error><Code>""" +
                INJECTABLE.getValue(code).code +
                "</Code><Message>injected by the test</Message><Resource></Resource>" +
                "<RequestId>injected</RequestId><HostId></HostId></Error>"
        ).toByteArray()

    private class Injected(
        val code: String,
        val reason: String,
    )

    companion object {
        /**
         * Статусы, которые заказать можно, и код ошибки для каждого.
         *
         * Список короткий не по недоделке: отказ вводится **до** разбора запроса, поэтому обёртка
         * не знает, про бакет он был или про ключ. У статусов, где код из самого статуса
         * не следует, — `404` (`NoSuchBucket`, `NoSuchKey`, `NoSuchUpload`) и `409`
         * (`BucketAlreadyExists`, `OperationAborted`) — выбор был бы выдумкой, и тест, различающий
         * эти случаи, писался бы на выдуманном ответе. Такой отказ спрашивают у сервера, заводя
         * состояние, а не у двойника.
         *
         * Имена берутся из [S3Error] там, где они там есть: у кода один дом, и переименование
         * в сервере не оставляет двойник отвечать прошлым именем.
         */
        private val INJECTABLE =
            mapOf(
                400 to Injected(S3Error.INVALID_REQUEST.code, "Bad Request"),
                403 to Injected(S3Error.ACCESS_DENIED.code, "Forbidden"),
                405 to Injected(S3Error.METHOD_NOT_ALLOWED.code, "Method Not Allowed"),
                // `408`, `429`, `502`, `503` и `504` — те, которые клиент повторяет сам, то есть
                // ровно то, ради чего заготовленный отказ и заводят. У `502` и `504` кода своего
                // нет: так отвечает не S3, а то, что стоит перед ним, — но клиент, ловящий их
                // как сбой, ловит их и здесь.
                408 to Injected("RequestTimeout", "Request Timeout"),
                412 to Injected(S3Error.PRECONDITION_FAILED.code, "Precondition Failed"),
                429 to Injected("SlowDown", "Too Many Requests"),
                500 to Injected(S3Error.INTERNAL_ERROR.code, "Internal Server Error"),
                501 to Injected(S3Error.NOT_IMPLEMENTED.code, "Not Implemented"),
                502 to Injected(S3Error.INTERNAL_ERROR.code, "Bad Gateway"),
                503 to Injected("ServiceUnavailable", "Service Unavailable"),
                504 to Injected(S3Error.INTERNAL_ERROR.code, "Gateway Timeout"),
                507 to Injected(S3Error.INSUFFICIENT_STORAGE.code, "Insufficient Storage"),
            )
    }
}
