package io.github.youndie.bochka.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write lock, put under the only conditions that can say anything about it: more than one
 * thread (M-245).
 *
 * Every other test in this module is single-threaded, and `writing` is a `ReentrantLock` — so a
 * thread that never releases it takes it again without noticing, and the release is unobservable.
 * That is not a guess: deleting the `unlock` from five places survived the whole suite, and it was
 * a mutation run that said so.
 *
 * The failure a missing release produces is a **hang**, not a wrong answer, so everything here is
 * bounded by a deadline and the deadline is asserted. A test that would hang forever reports the
 * defect only to whoever is watching the terminal.
 */
class ObjectStoreConcurrencyTest {
    private val dir: Path = Files.createTempDirectory("bochka-concurrency")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun `writers on different threads all get through, and none is left holding the lock`() {
        ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
            store.createBucket("photos")

            // Distinct keys per thread: what is being asked is whether the writers take turns, not
            // which of them wins a race for one key. The second question is asked below.
            val threads = 8
            val perThread = 40
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val pool = Executors.newFixedThreadPool(threads)

            repeat(threads) { worker ->
                pool.execute {
                    runCatching {
                        start.await()
                        runBlocking {
                            repeat(perThread) { n ->
                                store.write("photos", "w$worker/k$n", "from $worker")
                            }
                        }
                    }.onFailure { failure.compareAndSet(null, it) }
                }
            }
            start.countDown()
            pool.shutdown()

            assertTrue(
                pool.awaitTermination(60, TimeUnit.SECONDS),
                "the writers did not finish: a lock that is taken and not released blocks every " +
                    "thread after the first, and this is what that looks like from outside",
            )
            failure.get()?.let { throw AssertionError("a writer failed", it) }

            for (worker in 0 until threads) {
                for (n in 0 until perThread) {
                    assertEquals("from $worker", store.read("photos", "w$worker/k$n"), "w$worker/k$n")
                }
            }
        }
    }

    @Test
    fun `a delete and a compaction run against live writers without stalling them`() {
        // The other three places the lock is taken. A compaction rewrites the whole log while
        // writers append to it, and a delete decides what is there at the same moment a commit
        // does — those are the two reasons the lock exists at all, and the reason `deleteBucket`
        // joined them in M-220.
        ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
            store.createBucket("photos")
            store.createBucket("scratch")

            val rounds = 60
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val pool = Executors.newFixedThreadPool(4)

            pool.execute {
                runCatching {
                    start.await()
                    runBlocking { repeat(rounds) { store.write("photos", "live/$it", "$it") } }
                }.onFailure { failure.compareAndSet(null, it) }
            }
            pool.execute {
                runCatching {
                    start.await()
                    runBlocking { repeat(rounds) { store.write("photos", "doomed/$it", "$it") } }
                    repeat(rounds) { store.delete("photos", ObjectKey.of("doomed/$it")) }
                }.onFailure { failure.compareAndSet(null, it) }
            }
            pool.execute {
                runCatching {
                    start.await()
                    repeat(8) { store.compact() }
                }.onFailure { failure.compareAndSet(null, it) }
            }
            pool.execute {
                // An empty bucket, so the answer is known: it goes, and it goes while the log is
                // being rewritten underneath.
                runCatching {
                    start.await()
                    assertTrue(store.deleteBucket("scratch"), "an empty bucket has to be removable")
                }.onFailure { failure.compareAndSet(null, it) }
            }

            start.countDown()
            pool.shutdown()

            assertTrue(
                pool.awaitTermination(60, TimeUnit.SECONDS),
                "delete, compaction and writes did not finish together: an unreleased lock stalls " +
                    "whichever of them asks for it next",
            )
            failure.get()?.let { throw AssertionError("a worker failed", it) }

            for (n in 0 until rounds) {
                assertEquals("$n", store.read("photos", "live/$n"), "live/$n")
                assertNull(store.get("photos", ObjectKey.of("doomed/$n")), "doomed/$n")
            }
            assertTrue("scratch" !in store.bucketNames())
        }
    }

    @Test
    fun `two writers of one key leave one object rather than half of each`() {
        // The lock's own purpose, asked directly. Both answers are correct — the last commit wins —
        // and what must not happen is a third: bytes of one write under the ETag of the other.
        ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
            store.createBucket("photos")

            val threads = 6
            val rounds = 40
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val pool = Executors.newFixedThreadPool(threads)

            repeat(threads) { worker ->
                pool.execute {
                    runCatching {
                        start.await()
                        runBlocking { repeat(rounds) { store.write("photos", "contended", "from $worker") } }
                    }.onFailure { failure.compareAndSet(null, it) }
                }
            }
            start.countDown()
            pool.shutdown()

            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "the contended writers did not finish")
            failure.get()?.let { throw AssertionError("a writer failed", it) }

            val stored = store.get("photos", ObjectKey.of("contended"))
            val bytes = String(Files.readAllBytes(store.pathOf(stored!!)))
            assertTrue(bytes.startsWith("from "), bytes)
            assertEquals(bytes.length.toLong(), stored.size, "the size belongs to the bytes that are there")
        }
    }

    private suspend fun ObjectStore.write(
        bucket: String,
        key: String,
        content: String,
    ) = put(bucket, ObjectKey.of(key), Metadata(contentType = "text/plain")) { out ->
        val bytes = content.toByteArray()
        out.write(bytes, 0, bytes.size)
    }

    private fun ObjectStore.read(
        bucket: String,
        key: String,
    ): String? {
        val stored = get(bucket, ObjectKey.of(key)) ?: return null
        return String(Files.readAllBytes(pathOf(stored)))
    }
}
