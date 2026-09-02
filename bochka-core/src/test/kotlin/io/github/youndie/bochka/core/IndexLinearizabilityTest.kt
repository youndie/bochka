package io.github.youndie.bochka.core

import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test

/**
 * The index under **every** interleaving of a scenario, rather than the ones a race happened to hit
 * (M-286).
 *
 * `ObjectStoreConcurrencyTest` runs real threads against a real store and is worth keeping: it
 * checks the journal, the lock and the file on disk, which is more than this can see. What it
 * cannot say is *why* it passed — a race that did not happen leaves no trace, and the strongest
 * claims this project makes are about races: an overwrite is one object rather than half of each,
 * and a conditional write decides its condition and its write in one step.
 *
 * Lincheck says that differently. It runs a small scenario across threads many times over, and
 * demands that every outcome be explainable by **some** sequential order of the same calls. An
 * outcome that no order explains is a counterexample it prints, with the interleaving.
 *
 * **The subject is the whole store, not a map**, and that is deliberate. The index is a
 * `ConcurrentSkipListMap` and testing it alone would be testing the JDK; what this project wrote is
 * the composition around it — the lock, the precondition read inside it, the version chosen at
 * commit. Each invocation gets its own directory: the store owns files, and Lincheck builds a fresh
 * instance per run.
 *
 * **Stress rather than model checking, and that is a limitation rather than a preference.** The
 * model checker is the half that would enumerate; it was tried here and killed at twenty-five
 * minutes without finishing three iterations, because it re-executes each interleaving many times
 * and every re-execution replays writes to a real disk.
 *
 * **What this test does not catch, measured rather than assumed.** Moving the precondition read
 * out from under the write lock — the check-then-act race these claims are about — leaves this
 * green: the window is a few instructions wide and stress mode has to land in it by luck, which is
 * the same luck `ObjectStoreConcurrencyTest` depends on. So this is not yet the promotion from
 * "proved by racing" to "proved by enumeration" that M-286 wanted; it is a cheap guard against
 * gross breakage of the index, and the enumeration needs a subject without a disk under it
 * (M-306).
 */
@Param(name = "key", gen = StringGen::class, conf = "2:ab")
class IndexLinearizabilityTest {
    private val home: Path = Files.createTempDirectory("lincheck-index")
    private val store = ObjectStore(home, ObjectStore.Durability.NONE).also { it.createBucket(BUCKET) }

    @Operation
    fun write(
        @Param(name = "key") key: String,
        value: Int,
    ): Long =
        runBlocking {
            store
                .put(BUCKET, ObjectKey.of(key), Metadata()) { sink ->
                    val bytes = value.toString().toByteArray()
                    sink.write(bytes, 0, bytes.size)
                }.size
        }

    @Operation
    fun writeIfAbsent(
        @Param(name = "key") key: String,
        value: Int,
    ): String =
        runBlocking {
            val bytes = value.toString().toByteArray()
            // Staged first and committed under the condition, which is what a conditional write is
            // here: `put` is the two steps together without one. The bytes exist on disk either
            // way — a refused commit leaves an orphan, by design.
            val staged = store.stage { sink -> sink.write(bytes, 0, bytes.size) }
            try {
                store.commit(
                    BUCKET,
                    ObjectKey.of(key),
                    Metadata(),
                    staged,
                    precondition = ObjectStore.Precondition(ifNoneMatch = listOf("*")),
                )
                "written"
            } catch (_: ObjectStore.PreconditionFailed) {
                // The refusal is a result, not an error: "somebody else got there first" is exactly
                // what this operation is for, and a scenario where both threads are told they won
                // is the counterexample worth finding.
                "refused"
            }
        }

    @Operation
    fun size(
        @Param(name = "key") key: String,
    ): Long? = store.get(BUCKET, ObjectKey.of(key))?.size

    @Operation
    fun remove(
        @Param(name = "key") key: String,
    ): Boolean = store.delete(BUCKET, ObjectKey.of(key)).existed

    @Operation
    fun listed(): Int = store.list(BUCKET, maxKeys = 10).keys.size

    @Test
    fun `writes, conditional writes, deletes and a listing are linearizable`() {
        StressOptions()
            // Small on purpose. Every invocation is a fresh store with a directory and a journal,
            // so the cost here is milliseconds of disk rather than microseconds of memory: a
            // hundred thousand invocations would be an hour. Two keys and two threads is where the
            // interesting interleavings are anyway — a race needs two calls about one key.
            .iterations(10)
            .invocationsPerIteration(100)
            .threads(2)
            .actorsPerThread(3)
            .check(this::class)
    }

    @OptIn(ExperimentalPathApi::class)
    protected fun finalize() {
        // Lincheck builds an instance per invocation and never disposes of one, so the store's
        // channels and its directory would otherwise be left to the end of the JVM. Thousands of
        // open journals is how a run that measures the index ends up measuring file descriptors.
        runCatching {
            store.close()
            home.deleteRecursively()
        }
    }

    private companion object {
        const val BUCKET = "b"
    }
}
