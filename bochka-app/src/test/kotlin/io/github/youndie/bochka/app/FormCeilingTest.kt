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
 * what it refuses (research, §1.2); here the protections are the policy `content-length-range`,
 * the declared length, and a ceiling on what is collected.
 *
 * The range half is checked in [PostObjectTest]. The other two were checked by nothing: removing
 * the ceiling left the whole gate green, and a server that collects whatever arrives is one
 * unsigned request away from spending its heap on somebody else's upload.
 *
 * **The declared half is what this test stands on, and the reason is worth reading.** The first
 * version streamed past the ceiling and let the collector refuse - and it failed once in four runs
 * with `500 InternalError`, because growing a buffer to sixteen mebibytes costs about three times
 * that while it doubles, and the test JVM runs with a quarter of the heap the distribution ships
 * with. The refusal was losing a race to an `OutOfMemoryError`. The answer was not a bigger heap
 * for the test but a better server: a form that **states** a length over the ceiling is now
 * refused from the head, with no bytes read at all, which is this project's own rule applied where
 * it turned out to be possible after all.
 *
 * What remains unexercised here is the collector's own ceiling on a body that declares no length -
 * a chunked form. Reaching it needs more heap than this JVM has, and a test that skipped itself
 * would read exactly like one that passed.
 */
class FormCeilingTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    @Test
    fun `a form that states more than the ceiling is refused without its body`() {
        s3.createBucket("photos")
        val boundary = "----bochkaceiling"
        val declared = S3Handler.MAX_FORM_BODY + CHUNK.toLong()

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
                out.flush()

                // Nothing else is sent: the head alone has to be enough, and a server that waits
                // for the body would hang here until the read timeout rather than answer.
                String(socket.getInputStream().readBytes(), Charsets.ISO_8859_1)
            }

        assertEquals("HTTP/1.1 400 Bad Request", answer.lineSequence().first(), answer.take(200))
        assertContains(answer, "EntityTooLarge")
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
