package io.github.youndie.bochka.embedded

import io.github.youndie.bochka.http.HttpHandler
import io.github.youndie.bochka.http.HttpRequestParser
import io.github.youndie.bochka.http.HttpResponse
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

    /** Ответить [status] на следующие [times] запросов, каких бы то ни было. */
    fun failNext(
        status: Int,
        times: Int,
    ) {
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

    private fun reasonFor(code: Int) =
        when (code) {
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }

    /**
     * Тело ошибки настоящей формы, а не пустой ответ.
     *
     * Клиент, который проверяет свои повторы, разбирает ответ — и на пустом теле падает внутри
     * своего парсера вместо того, чтобы сделать повтор. Ровно это уже случалось здесь с `412`
     * без тела: статус ошибки без документа ошибки — другая поломка, а не более короткая.
     */
    private fun document(code: Int): ByteArray =
        (
            """<?xml version="1.0" encoding="UTF-8"?><Error><Code>""" +
                (if (code == 503) "ServiceUnavailable" else "InternalError") +
                "</Code><Message>injected by the test</Message><Resource></Resource>" +
                "<RequestId>injected</RequestId><HostId></HostId></Error>"
        ).toByteArray()
}
