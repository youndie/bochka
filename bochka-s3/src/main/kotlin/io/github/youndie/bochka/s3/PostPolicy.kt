package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.S3Error
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Политика POST-формы: что клиенту **позволено положить**, подписанное владельцем ключа.
 *
 * Документ — JSON в base64, и подписывается он целиком как строка. Отсюда правило, которое легко
 * нарушить: **проверять надо ту строку, что приехала**, а не результат её разбора. Пересобранный
 * JSON отличается от присланного пробелами и порядком, и подпись от него не сойдётся — а если
 * подпись считать от пересобранного, то подписью станет наше представление, а не клиентское.
 *
 * Разбор здесь ручной и намеренно узкий: нужны `expiration` и `conditions`, где условие — это либо
 * объект из одной пары, либо тройка `["starts-with", "$поле", "префикс"]`, либо
 * `["content-length-range", от, до]`. Тащить сюда разборщик JSON общего назначения значило бы
 * завести зависимость ради пяти форм записи.
 */
object PostPolicy {
    class Refused(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    sealed interface Condition {
        data class Exact(
            val field: String,
            val value: String,
        ) : Condition

        data class StartsWith(
            val field: String,
            val prefix: String,
        ) : Condition

        data class LengthRange(
            val from: Long,
            val to: Long,
        ) : Condition
    }

    data class Policy(
        val expiration: Instant,
        val conditions: List<Condition>,
    )

    /**
     * Поля, которые в условиях не участвуют.
     *
     * Их шлёт браузер или подписывающая сторона, и требовать для них условие значило бы требовать
     * от клиента подписать собственную подпись.
     *
     * **`x-amz-checksum-*` здесь по другой причине, и её стоит назвать** (M-158). Список существует
     * затем, чтобы загружающий не мог подставить к подписанной политике что-то, чего подписывавший
     * не разрешал: непокрытое поле — это поле, которым можно воспользоваться. Контрольная сумма
     * воспользоваться нечем: она ничего не расширяет и не называет, а худшее, что может сделать, —
     * отказать в загрузке. Поэтому условие для неё не требуется, а сама она **проверяется**
     * (`test_post_object_upload_checksum:15299` шлёт её без условия и следом шлёт неверную).
     */
    private val NOT_CONDITIONED =
        setOf(
            "file",
            "policy",
            "signature",
            "awsaccesskeyid",
            "x-amz-signature",
            "x-amz-algorithm",
            "x-amz-credential",
            "x-amz-date",
            "x-amz-checksum-",
            "x-ignore-",
        )

    fun decode(encoded: String): Policy {
        val json =
            try {
                String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "политика не является base64")
            }

        // Оба ключа обязательны, и оба читаются с учётом регистра. `EXPIRATION` — это не
        // «политика без срока», а политика, которую подписывавший считал ограниченной по времени:
        // принять её значило бы выдать бессрочный пропуск тому, кто просил суточный.
        // `test_post_object_expires_is_case_sensitive:2631` и `…_condition_is_case_sensitive:2598`
        // требуют на оба `400`, и требуют по этой причине.
        val stated =
            valueOf(json, "expiration")
                ?: throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "в политике нет expiration")
        val expiration =
            try {
                Instant.parse(stated)
            } catch (_: DateTimeParseException) {
                throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "expiration не разбирается: '$stated'")
            }
        return Policy(expiration, conditionsOf(json))
    }

    /**
     * Проверяет форму против политики.
     *
     * Порядок проверок — от дешёвого к дорогому и от общего к частному: срок годности, затем
     * условия, затем длина. Клиент, приславший протухшую политику, не должен узнавать об этом
     * после разбора десяти условий.
     *
     * @param fields поля формы **плюс `bucket`**, которого среди них нет: бакет POST-загрузки едет
     *   в URL, а условие на него есть в каждой политике. Подставляет его вызывающий, потому что
     *   маршрут знает только он.
     */
    fun check(
        policy: Policy,
        fields: Map<String, String>,
        fileLength: Long,
        now: Instant,
    ) {
        if (now.isAfter(policy.expiration)) {
            throw Refused(S3Error.ACCESS_DENIED, "политика истекла ${policy.expiration}")
        }

        val covered = HashSet<String>()
        for (condition in policy.conditions) {
            when (condition) {
                is Condition.Exact -> {
                    covered += condition.field
                    val actual =
                        fields[condition.field]
                            ?: throw Refused(S3Error.ACCESS_DENIED, "политика требует поле ${condition.field}")
                    if (actual != condition.value) {
                        throw Refused(
                            S3Error.ACCESS_DENIED,
                            "${condition.field} равно '$actual', политика разрешает '${condition.value}'",
                        )
                    }
                }

                is Condition.StartsWith -> {
                    covered += condition.field
                    val actual =
                        fields[condition.field]
                            ?: throw Refused(S3Error.ACCESS_DENIED, "политика требует поле ${condition.field}")
                    if (!actual.startsWith(condition.prefix)) {
                        throw Refused(
                            S3Error.ACCESS_DENIED,
                            "${condition.field} равно '$actual', политика требует начала '${condition.prefix}'",
                        )
                    }
                }

                is Condition.LengthRange -> {
                    if (fileLength < condition.from || fileLength > condition.to) {
                        throw Refused(
                            S3Error.ENTITY_TOO_LARGE,
                            "файл в $fileLength байт вне ${condition.from}..${condition.to}",
                        )
                    }
                }
            }
        }

        // Поле, которое политика не покрыла, — это поле, которое подписавший не разрешал.
        // Пропустить его значит дать загружающему подставить что угодно к подписанной политике,
        // и это дыра, а не послабление.
        for (name in fields.keys) {
            if (name in covered) continue
            if (name in NOT_CONDITIONED || NOT_CONDITIONED.any { it.endsWith("-") && name.startsWith(it) }) continue
            throw Refused(S3Error.ACCESS_DENIED, "поле '$name' не разрешено политикой")
        }
    }

    // --- разбор, ровно настолько узкий, насколько нужно -------------------------------------------

    private fun valueOf(
        json: String,
        key: String,
    ): String? {
        val marker = json.indexOf("\"$key\"")
        if (marker < 0) return null
        val colon = json.indexOf(':', marker + key.length + 2)
        if (colon < 0) return null
        val quote = json.indexOf('"', colon)
        if (quote < 0) return null
        val end = json.indexOf('"', quote + 1)
        if (end < 0) return null
        return json.substring(quote + 1, end)
    }

    private fun conditionsOf(json: String): List<Condition> {
        val marker = json.indexOf("\"conditions\"")
        if (marker < 0) throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "в политике нет conditions")
        val open = json.indexOf('[', marker)
        if (open < 0) throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "conditions не список")

        val conditions = ArrayList<Condition>()
        var i = open + 1
        var depth = 1
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '[' -> {
                    val end = matching(json, i, '[', ']')
                    conditions += tripleOf(json.substring(i + 1, end))
                    i = end + 1
                }

                '{' -> {
                    val end = matching(json, i, '{', '}')
                    conditions += pairOf(json.substring(i + 1, end))
                    i = end + 1
                }

                ']' -> {
                    depth--
                    i++
                }

                else -> {
                    i++
                }
            }
        }
        return conditions
    }

    private fun matching(
        json: String,
        from: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var i = from
        while (i < json.length) {
            when (json[i]) {
                open -> {
                    depth++
                }

                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "условие не закрыто")
    }

    private fun pairOf(inner: String): Condition {
        val parts = quoted(inner)
        if (parts.size != 2) throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "условие-объект не из одной пары")
        return Condition.Exact(parts[0].lowercase(), parts[1])
    }

    private fun tripleOf(inner: String): Condition {
        val parts = quoted(inner)
        if (parts.size >= 1 && parts[0].equals("content-length-range", ignoreCase = true)) {
            val numbers = Regex("-?\\d+").findAll(inner).map { it.value.toLong() }.toList()
            if (numbers.size < 2) throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "content-length-range без границ")
            return Condition.LengthRange(numbers[0], numbers[1])
        }
        if (parts.size == 3 && parts[0].equals("starts-with", ignoreCase = true)) {
            return Condition.StartsWith(parts[1].removePrefix("$").lowercase(), parts[2])
        }
        if (parts.size == 3 && parts[0].equals("eq", ignoreCase = true)) {
            return Condition.Exact(parts[1].removePrefix("$").lowercase(), parts[2])
        }
        throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "неизвестное условие: $inner")
    }

    /**
     * Строки условия, уже без экранирования JSON.
     *
     * Разэкранирование здесь не украшение: `test_post_object_escaped_field_values:2257` подписывает
     * условие на префикс `\$foo`, и в документе он лежит как `\\$foo`. Сравнить его с полем как
     * есть значит потребовать от клиента прислать лишний обратный слэш — то есть отвергнуть
     * форму, которую сам же и разрешил.
     */
    private fun quoted(inner: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(inner)
            .map { unescape(it.groupValues[1]) }
            .toList()

    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = text[i + 1]) {
                'n' -> {
                    out.append('\n')
                }

                't' -> {
                    out.append('\t')
                }

                'r' -> {
                    out.append('\r')
                }

                'u' -> {
                    if (i + 5 >= text.length) throw Refused(S3Error.MALFORMED_POLICY_DOCUMENT, "оборванная \\u")
                    out.append(text.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 4
                }

                else -> {
                    out.append(next)
                }
            }
            i += 2
        }
        return out.toString()
    }
}
