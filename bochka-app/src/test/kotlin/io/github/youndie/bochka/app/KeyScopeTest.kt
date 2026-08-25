package io.github.youndie.bochka.app

import io.github.youndie.bochka.s3.sigv4.KeyScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A key's scope (M19) — what is actually being asked for under the word "permissions".
 *
 * Almost everything here is about the **negative**, and that is a necessity rather than a style:
 * the value of a setting like this lies exactly in what it stops you doing. There are few positive
 * assertions and they cannot be relied on — a key that "can do everything" passes all of them in
 * `ro` mode too.
 *
 * The milestone moves no external number: S3 has no such notion, and the suite has no tests for it.
 */
class KeyScopeTest {
    private fun readOnly() = KeyScope(KeyScope.Mode.RO)

    private fun scopedTo(vararg buckets: String) = KeyScope(KeyScope.Mode.RW, buckets.toSet())

    @Test
    fun `a read-only key reads and lists`() {
        S3Fixture().use { s3 ->
            s3.createBucket("photos")
            s3.put("photos", "a.txt", "тело")
        }
        S3Fixture(scope = readOnly()).use { s3 ->
            // The bucket and the object are put around HTTP: this fixture's key can no longer do
            // that, which is exactly what the next test checks.
            s3.store.createBucket("photos")

            assertEquals(200, s3.send("GET", "/photos", query = "list-type=2").status)
            assertEquals(200, s3.send("GET", "/").status)
            assertEquals(404, s3.get("photos", "a.txt").status, "reading is allowed, but there is no object")
        }
    }

    @Test
    fun `a read-only key does not write, delete or finish an upload`() {
        S3Fixture(scope = readOnly()).use { s3 ->
            s3.store.createBucket("photos")

            assertEquals(403, s3.put("photos", "a.txt", "тело").status)
            assertEquals(403, s3.send("DELETE", "/photos/a.txt").status)
            assertEquals(403, s3.send("PUT", "/other").status, "creating a bucket is a write too")
            assertEquals(403, s3.send("POST", "/photos/a.txt", query = "uploads").status)
            assertEquals(403, s3.send("POST", "/photos", query = "delete").status)
        }
    }

    @Test
    fun `a refused write leaves nothing in the store`() {
        // The refusal is decided in `screen`, that is from the headers alone (§1.2) — and the
        // connection closes, because the body was left unread.
        //
        // The body here is small **on purpose**. The first version sent four mebibytes to "prove"
        // they were not read, and hung the run: the server answers and closes the connection while
        // the client is still writing, and both sides wait for each other. That the refusal comes
        // before the body is proved by how `screen` is built, not by the size of a request; what is
        // checked here is only that no write happened.
        S3Fixture(scope = readOnly()).use { s3 ->
            s3.store.createBucket("photos")

            val answer = s3.put("photos", "a.txt", "тело")

            assertEquals(403, answer.status)
            assertEquals(0, s3.store.objectCount)
        }
    }

    @Test
    fun `a bucket outside the scope does not exist rather than being refused`() {
        // Invisibility matters more than a refusal: an `AccessDenied` confirms the name is taken,
        // and that is already telling somebody about another party's bucket. Hence `NoSuchBucket`.
        S3Fixture(scope = scopedTo("photos")).use { s3 ->
            s3.store.createBucket("photos")
            s3.store.createBucket("secrets")

            assertEquals(404, s3.send("GET", "/secrets", query = "list-type=2").status)
            assertEquals(404, s3.get("secrets", "a.txt").status)
            assertEquals(200, s3.send("GET", "/photos", query = "list-type=2").status)
        }
    }

    @Test
    fun `a bucket outside the scope is not in the list either`() {
        S3Fixture(scope = scopedTo("photos")).use { s3 ->
            s3.store.createBucket("photos")
            s3.store.createBucket("secrets")

            val listing = s3.send("GET", "/").text

            assertTrue("photos" in listing, listing)
            assertTrue("secrets" !in listing, "the listing showed somebody else's bucket: $listing")
        }
    }

    @Test
    fun `a key nobody narrowed keeps everything`() {
        // A setting that only narrows cannot lock the owner out of their own store — so the absence
        // of an entry means full access rather than none.
        S3Fixture().use { s3 ->
            assertEquals(200, s3.createBucket("photos").status)
            assertEquals(200, s3.put("photos", "a.txt", "тело").status)
        }
    }

    @Test
    fun `the scope format is parsed, and a bad one is refused at startup`() {
        assertEquals(
            mapOf(
                "backup" to KeyScope(KeyScope.Mode.RO, setOf("photos", "reports")),
                "app" to KeyScope(KeyScope.Mode.RW),
            ),
            KeyScope.parse(listOf("backup=ro@photos|reports", "app=rw")),
        )
        // Fail at startup rather than quietly hand out the wrong access: a setting with a typo is
        // better seen as a refusal to start.
        assertFailsWith<IllegalArgumentException> { KeyScope.parse(listOf("backup=readonly")) }
        assertFailsWith<IllegalArgumentException> { KeyScope.parse(listOf("backup")) }
    }
}
