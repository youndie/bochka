package io.github.youndie.bochka.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The health endpoint: the one answer this server gives with no signature and no bucket (M-143).
 *
 * Not added for convenience. A kubelet counts anything but a 2xx/3xx as a failure, and this
 * server's honest answer to an unauthenticated client is `403`; hence the chart used to carry an
 * `exec` probe — a `bash` fork every period inside a cgroup whose memory budget is counted almost
 * to the edge. With this endpoint the probe becomes an `httpGet` and stops costing a process.
 *
 * The assertions here are half negative, and that is the same rule as for permissions and for the
 * lock: a hole in signature checking is worth exactly how narrow it is. One path, two methods,
 * path-style only.
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
