package io.github.youndie.bochka.s3

/**
 * Правила CORS бакета и единственный вопрос, который к ним задают: подходит ли запрос.
 *
 * Форма — `docs/spec/s3-service-2.json`: `CORSConfiguration` (`:2241`) содержит `CORSRule`
 * (`:2253`), у которого обязательны `AllowedMethods` и `AllowedOrigins`.
 *
 * Хранение и разбор здесь скучные. Интересно одно — **сопоставление источника**, и оно нарочно
 * написано вручную, а не регуляркой.
 */
data class CorsRules(
    val rules: List<Rule>,
) {
    data class Rule(
        val id: String? = null,
        val allowedMethods: List<String> = emptyList(),
        val allowedOrigins: List<String> = emptyList(),
        val allowedHeaders: List<String> = emptyList(),
        val exposeHeaders: List<String> = emptyList(),
        val maxAgeSeconds: Int? = null,
    )

    /** Первое правило, разрешающее этот источник и этот метод, или `null`, если такого нет. */
    fun matching(
        origin: String,
        method: String,
    ): Rule? =
        rules.firstOrNull { rule ->
            rule.allowedMethods.any { it.equals(method, ignoreCase = true) } &&
                rule.allowedOrigins.any { matches(it, origin) }
        }

    companion object {
        /**
         * Сопоставление источника с образцом, где `*` — «любая последовательность».
         *
         * **Написано руками, и это не изобретение велосипеда, а отказ разрешить лишнее.**
         * Превратить образец в регулярку — самый короткий путь, и он неверен: в образце вида
         * `*.example.com` точка для регулярки значит «любой символ», то есть `appXexample.com`
         * подошёл бы. Для правила доступа «подошло лишнее» — это дыра, а не неточность.
         *
         * (В примерах здесь нарочно нет схемы: последовательность из двух косых и звёздочки
         * внутри комментария открывает **вложенный** комментарий — блочные комментарии в Kotlin
         * вкладываются, — и весь файл после этого перестаёт компилироваться.)
         *
         * S3 разрешает в образце ровно одну звёздочку; всё остальное сравнивается буквально,
         * включая схему и точки.
         */
        fun matches(
            pattern: String,
            origin: String,
        ): Boolean {
            val star = pattern.indexOf('*')
            if (star < 0) return pattern == origin
            val head = pattern.substring(0, star)
            val tail = pattern.substring(star + 1)
            // `head + tail` длиннее источника значит, что даже пустая подстановка не влезает —
            // и заодно защищает от перекрытия head и tail на коротком источнике.
            if (origin.length < head.length + tail.length) return false
            return origin.startsWith(head) && origin.endsWith(tail)
        }
    }
}
