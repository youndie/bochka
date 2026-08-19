package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Ручка здоровья: единственный ответ этого сервера без подписи и без бакета (M-143).
 *
 * Заведена не ради удобства. Kubelet считает провалом любой ответ, кроме 2xx/3xx, а честный ответ
 * этого сервера неаутентифицированному клиенту — `403`; отсюда в чарте стояла проба `exec`, то есть
 * форк `bash` каждый период внутри cgroup, где бюджет памяти посчитан почти впритык. С этой ручкой
 * проба становится `httpGet` и перестаёт стоить процесса.
 *
 * Проверки здесь наполовину отрицательные, и это то же правило, что у прав и у замка: дыра в
 * проверке подписи ценна ровно тем, насколько она узкая. Один путь, два метода, только path-style.
 */
class HealthTest {
    private val s3 = S3Fixture()

    @AfterTest
    fun cleanup() = s3.close()

    @Test
    fun `an unsigned GET is answered, not refused`() {
        val answer = s3.unsigned("GET", "/-/healthy")

        assertEquals(200, answer.status)
        assertContains(String(answer.body), "ok")
    }

    @Test
    fun `HEAD is answered too, and with no body`() {
        val answer = s3.unsigned("HEAD", "/-/healthy")

        assertEquals(200, answer.status)
        assertEquals(0, answer.body.size)
    }

    @Test
    fun `the hole is one path and does not spread to its neighbours`() {
        // Every one of these is unsigned, and every one of them must still be refused. `-` is not
        // a bucket anybody can create (three-character floor, letter-or-digit edges), so none of
        // them is reachable by a legitimate client either way — what is asserted is that the
        // exemption from signature checking did not widen past the one route it was made for.
        assertEquals(403, s3.unsigned("GET", "/-/ready").status)
        assertEquals(403, s3.unsigned("GET", "/-/healthy/deeper").status)
        assertEquals(403, s3.unsigned("PUT", "/-/healthy").status)
        assertEquals(403, s3.unsigned("DELETE", "/-/healthy").status)
        assertEquals(403, s3.unsigned("GET", "/").status)
    }

    @Test
    fun `the answer is not a listing of anything`() {
        // What a store holds is not an orchestrator's business, and the handle is reachable by
        // anyone who can reach the port. It says the process answers; it says nothing else.
        s3.createBucket("photos")
        s3.put("photos", "a.txt", "hello".toByteArray())

        val body = String(s3.unsigned("GET", "/-/healthy").body)

        assertEquals("ok\n", body)
    }
}
