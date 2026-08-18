package io.github.youndie.bochka.junit

import io.github.youndie.bochka.embedded.Bochka
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * bochka на время тестового класса, со сбросом между тестами.
 *
 * ```kotlin
 * class MyTest {
 *     companion object {
 *         @JvmField
 *         @RegisterExtension
 *         val bochka = BochkaExtension()
 *     }
 *
 *     @Test fun `сохраняет отчёт`() {
 *         val s3 = S3Client.builder()
 *             .endpointOverride(URI.create(bochka.endpoint))
 *             .forcePathStyle(true)
 *             .build()
 *         // …
 *     }
 * }
 * ```
 *
 * ## Почему стор один на класс, а не один на тест
 *
 * Потому что дорого не состояние, а **старт**: новый стор — это новый журнал, новый сокет и новый
 * эндпоинт, который придётся куда-то передать. Состояние снимается [Bochka.reset], и это очистка
 * пары структур.
 *
 * Изоляция при этом не хуже: после каждого теста не остаётся ни объектов, ни бакетов, ни
 * заготовленных отказов. Что остаётся — порт и ключи, то есть ровно то, что тесту удобно считать
 * постоянным.
 *
 * **Тестам, которые идут параллельно в одном классе, этого мало**, и здесь это сказано вслух,
 * а не оставлено на догадку: они делят один стор, и сброс между ними сотрёт чужое. Для таких
 * заводите по расширению на тест (`@RegisterExtension` на нестатическом поле) — тогда цена старта
 * платится за изоляцию сознательно.
 */
class BochkaExtension
    @JvmOverloads
    constructor(
        private val durable: Boolean = false,
        private val log: Boolean = false,
    ) : BeforeAllCallback,
        AfterEachCallback,
        AfterAllCallback {
        private var running: Bochka? = null

        /** Поднятый сервер. Обращение до старта — ошибка использования, а не `null`. */
        val bochka: Bochka
            get() = running ?: error("bochka ещё не поднята: расширение зарегистрировано, но тест не начался")

        val endpoint: String get() = bochka.endpoint
        val accessKeyId: String get() = bochka.accessKeyId
        val secretKey: String get() = bochka.secretKey
        val region: String get() = bochka.region

        override fun beforeAll(context: ExtensionContext) {
            running = Bochka.start(durable = durable, log = log)
        }

        override fun afterEach(context: ExtensionContext) {
            running?.reset()
        }

        override fun afterAll(context: ExtensionContext) {
            running?.close()
            running = null
        }
    }
