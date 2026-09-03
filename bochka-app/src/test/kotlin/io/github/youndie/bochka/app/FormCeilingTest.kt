package io.github.youndie.bochka.app

import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A form larger than the ceiling is refused rather than collected (M16).
 *
 * This is the one place the server holds a body in memory, and it holds it because it has no
 * choice: a form carries its signature **inside** the body, so the bytes exist before anything
 * about them can be trusted. Everywhere else this server refuses from the head and never reads
 * what it refuses (research, §1.2); here the only protections are the policy's
 * `content-length-range` and this ceiling, which stands **before** the policy is even parsed.
 *
 * The range half is checked in [PostObjectTest]. The ceiling half was checked by nothing: removing
 * it entirely left the whole gate green, and a server that collects whatever arrives is one
 * unsigned request away from spending its heap on somebody else's upload.
 *
 * Written to a socket by hand and streamed rather than assembled: a test that built the body in
 * memory would need as much heap as the thing it is guarding against, on a JVM deliberately
 * limited to 128 MiB. The server answers and closes while the write is still going, so the write
 * is allowed to fail - what matters is the answer that came back.
 */
class FormCeilingTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    @Test
    fun `a form larger than the ceiling is refused, and its bytes are never collected`() {
        s3.createBucket("photos")
        val boundary = "----bochkaceiling"
        val declared = S3Handler.MAX_FORM_BODY + CHUNK * 8L

        val answer =
            Socket("127.0.0.1", s3.port).use { socket ->
                socket.soTimeout = 30_000
                val head =
                    "POST /photos HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${s3.port}\r\n" +
                        "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
                        "Content-Length: $declared\r\n" +
                        "Connection: close\r\n\r\n"
                val out = socket.getOutputStream()
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n".toByteArray())

                // One buffer, written many times: the point of the ceiling is that a body can be
                // bigger than what anyone wants to hold, and a test that holds it proves nothing.
                val filler = ByteArray(CHUNK) { 'x'.code.toByte() }
                var written = 0L
                runCatching {
                    while (written < declared) {
                        out.write(filler)
                        written += CHUNK
                    }
                    out.flush()
                }

                String(socket.getInputStream().readBytes(), Charsets.ISO_8859_1)
            }

        assertEquals("HTTP/1.1 400 Bad Request", answer.lineSequence().first(), answer.take(200))
        assertContains(answer, "EntityTooLarge")
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
