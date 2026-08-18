package io.github.youndie.bochka.s3

import io.github.youndie.bochka.s3.sigv4.S3Error

/**
 * `multipart/form-data` — пятая форма входного пути, и единственная, которая переворачивает его
 * правило.
 *
 * Весь остальной вход построен на том, что отказ возможен **до** чтения тела: подпись живёт
 * в заголовках, и `403` не стоит пяти гигабайт (§1.2). У формы политика и подпись лежат
 * **внутри тела**, между полями, — аутентифицировать, не прочитав его, нельзя в принципе.
 * Это форма протокола, а не наша недоработка, и защита от неё одна: [LIMIT] до разбора и
 * `content-length-range` из самой политики после.
 *
 * Поле `file` по определению последнее: всё, что после него, S3 игнорирует, и клиенты на это
 * рассчитывают. Поэтому разбор идёт по порядку и останавливается на нём.
 */
object PostForm {
    /**
     * Потолок на форму целиком, кроме содержимого файла.
     *
     * Двадцать килобайт — это политика, подпись и десяток полей с запасом; форма больше этого
     * либо ошибка, либо попытка заставить сервер разобрать то, чего он не собирался читать.
     * Проверяется **до** разбора, потому что после уже поздно.
     */
    const val LIMIT: Int = 20 * 1024

    class Malformed(
        val error: S3Error,
        override val message: String,
    ) : RuntimeException(message)

    /**
     * Разобранная форма: поля до `file` и границы самого файла в исходном массиве.
     *
     * Содержимое файла **не копируется** — хранятся смещение и длина. Форма приезжает целиком
     * в память (иначе подпись не проверить), и делать вторую копию гигабайта ради удобства
     * значило бы удвоить единственное место, где этот сервер вынужден держать тело.
     */
    data class Parsed(
        val fields: Map<String, String>,
        val fileOffset: Int,
        val fileLength: Int,
        val fileName: String?,
    ) {
        operator fun get(name: String): String? = fields[name.lowercase()]
    }

    /** `multipart/form-data; boundary=…` — граница из заголовка, без неё разбирать нечего. */
    fun boundaryOf(contentType: String?): String? {
        val value = contentType ?: return null
        if (!value.startsWith("multipart/form-data", ignoreCase = true)) return null
        val marker = value.indexOf("boundary=", ignoreCase = true)
        if (marker < 0) return null
        return value
            .substring(marker + "boundary=".length)
            .trim()
            .trim('"')
            .takeIf { it.isNotEmpty() }
    }

    fun parse(
        body: ByteArray,
        boundary: String,
    ): Parsed {
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val fields = LinkedHashMap<String, String>()
        var fileOffset = -1
        var fileLength = 0
        var fileName: String? = null

        var at = indexOf(body, delimiter, 0)
        if (at < 0) throw Malformed(S3Error.MALFORMED_POST_REQUEST, "в теле нет границы формы")

        while (at >= 0) {
            var cursor = at + delimiter.size
            // `--` после границы — конец формы. Проверяется до перевода строки, потому что
            // у последней границы его может и не быть.
            if (cursor + 1 < body.size && body[cursor] == '-'.code.toByte() && body[cursor + 1] == '-'.code.toByte()) {
                break
            }
            cursor = skipEndOfLine(body, cursor) ?: break

            val headerEnd = indexOf(body, DOUBLE_EOL, cursor)
            if (headerEnd < 0) throw Malformed(S3Error.MALFORMED_POST_REQUEST, "у части формы нет заголовков")
            val headers = String(body, cursor, headerEnd - cursor, Charsets.ISO_8859_1)
            val contentStart = headerEnd + DOUBLE_EOL.size

            val next = indexOf(body, delimiter, contentStart)
            if (next < 0) throw Malformed(S3Error.MALFORMED_POST_REQUEST, "часть формы не закрыта границей")
            // Перед границей стоит перевод строки, принадлежащий разделителю, а не содержимому.
            val contentEnd = trimTrailingEndOfLine(body, contentStart, next)

            val name = dispositionValue(headers, "name")?.lowercase()
            if (name == "file") {
                fileOffset = contentStart
                fileLength = contentEnd - contentStart
                fileName = dispositionValue(headers, "filename")
                // Всё после `file` S3 игнорирует, и клиенты на это рассчитывают.
                break
            }
            if (name != null) {
                fields[name] = String(body, contentStart, contentEnd - contentStart, Charsets.UTF_8)
            }
            at = next
        }

        if (fileOffset < 0) throw Malformed(S3Error.MALFORMED_POST_REQUEST, "в форме нет поля file")
        return Parsed(fields, fileOffset, fileLength, fileName)
    }

    private val DOUBLE_EOL = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

    private fun skipEndOfLine(
        body: ByteArray,
        from: Int,
    ): Int? {
        var i = from
        if (i < body.size && body[i] == '\r'.code.toByte()) i++
        if (i < body.size && body[i] == '\n'.code.toByte()) i++
        return if (i > from) i else null
    }

    private fun trimTrailingEndOfLine(
        body: ByteArray,
        from: Int,
        to: Int,
    ): Int {
        var end = to
        if (end > from && body[end - 1] == '\n'.code.toByte()) end--
        if (end > from && body[end - 1] == '\r'.code.toByte()) end--
        return end
    }

    /** `Content-Disposition: form-data; name="key"; filename="a.txt"` — значение по имени. */
    private fun dispositionValue(
        headers: String,
        attribute: String,
    ): String? {
        for (line in headers.split("\r\n")) {
            if (!line.startsWith("Content-Disposition", ignoreCase = true)) continue
            val marker = line.indexOf("$attribute=\"", ignoreCase = true)
            if (marker < 0) continue
            val start = marker + attribute.length + 2
            val end = line.indexOf('"', start)
            if (end < 0) return null
            // The part's headers were decoded byte-for-byte as Latin-1, which is what keeps the
            // delimiter search honest — but `filename` carries whatever the file was called, and
            // a browser puts it there as raw UTF-8. Round-tripping through the bytes is what turns
            // `Ñ\u0081Ð½Ð¸Ð¼Ð¾Ðº.txt` back into the name the user picked. Without it the key stored is
            // mojibake, and it is mojibake **only** for non-ASCII names — which is how it survives
            // every test written in English.
            return String(line.substring(start, end).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }
        return null
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
        from: Int,
    ): Int {
        if (needle.isEmpty()) return from
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
