package io.github.youndie.bochka.s3

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Разбор `multipart/form-data` — на записанных байтах, без сокета (Р8).
 *
 * Форма собирается здесь руками, а не клиентом: смысл проверки в том, чтобы **байты были ровно
 * такие**, какие шлёт браузер, включая `\r\n` в разделителях. Библиотека, собравшая форму за нас,
 * проверяла бы согласие двух наших же представлений.
 */
class PostFormTest {
    private fun form(
        boundary: String,
        vararg parts: Pair<String, String>,
    ): ByteArray =
        buildString {
            for ((name, value) in parts) {
                append("--$boundary\r\n")
                if (name == "file") {
                    append("Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\n")
                } else {
                    append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                }
                append(value)
                append("\r\n")
            }
            append("--$boundary--\r\n")
        }.toByteArray()

    @Test
    fun `поля читаются, а содержимое файла не копируется`() {
        val body = form("XYZ", "key" to "foo.txt", "acl" to "private", "file" to "bar")

        val parsed = PostForm.parse(body, "XYZ")

        assertEquals("foo.txt", parsed["key"])
        assertEquals("private", parsed["acl"])
        assertEquals("a.txt", parsed.fileName)
        assertContentEquals(
            "bar".toByteArray(),
            body.copyOfRange(parsed.fileOffset, parsed.fileOffset + parsed.fileLength),
        )
    }

    @Test
    fun `всё после file игнорируется, как это делает S3`() {
        // Клиенты на это рассчитывают: поля после файла в подпись не входят и смысла не имеют.
        val body = form("XYZ", "key" to "foo.txt", "file" to "bar", "ignored" to "мусор")

        val parsed = PostForm.parse(body, "XYZ")

        assertNull(parsed["ignored"])
        assertEquals(3, parsed.fileLength)
    }

    @Test
    fun `имена полей нечувствительны к регистру`() {
        val body = form("XYZ", "Content-Type" to "text/plain", "file" to "bar")

        assertEquals("text/plain", PostForm.parse(body, "XYZ")["content-type"])
    }

    @Test
    fun `перевод строки перед границей принадлежит разделителю, а не файлу`() {
        // Классическая ошибка на единицу: `\r\n` перед `--boundary` — часть разделителя.
        // Отдать его как содержимое значит записать объект на два байта длиннее присланного.
        val body = form("XYZ", "file" to "bar")

        assertEquals(3, PostForm.parse(body, "XYZ").fileLength)
    }

    @Test
    fun `пустой файл — это ноль байтов, а не ошибка`() {
        val body = form("XYZ", "key" to "empty", "file" to "")

        assertEquals(0, PostForm.parse(body, "XYZ").fileLength)
    }

    @Test
    fun `форма без файла отвергается`() {
        val body = form("XYZ", "key" to "foo.txt")

        assertFailsWith<PostForm.Malformed> { PostForm.parse(body, "XYZ") }
    }

    @Test
    fun `граница берётся из заголовка и только у multipart`() {
        assertEquals("XYZ", PostForm.boundaryOf("multipart/form-data; boundary=XYZ"))
        assertEquals("XYZ", PostForm.boundaryOf("multipart/form-data; boundary=\"XYZ\""))
        assertNull(PostForm.boundaryOf("application/octet-stream"))
        assertNull(PostForm.boundaryOf("multipart/form-data"))
        assertNull(PostForm.boundaryOf(null))
    }

    @Test
    fun `a filename outside ASCII survives the part headers`() {
        // The part's headers are read byte-for-byte, and `filename` is the one place inside them
        // that carries user text. Read as Latin-1 it becomes mojibake, and since the key of a form
        // upload can be built from it, the object lands under a name nobody asked for.
        val body =
            (
                "--B\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"снимок.txt\"\r\n" +
                    "\r\n" +
                    "тело\r\n" +
                    "--B--\r\n"
            ).toByteArray(Charsets.UTF_8)

        assertEquals("снимок.txt", PostForm.parse(body, "B").fileName)
    }
}
