package io.github.youndie.bochka.core

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.Test

/**
 * The conditional write, enumerated (M-306) — which is what M-286 wanted and could not have.
 *
 * The subject there was the whole store, and the whole store owns a directory, a journal and a
 * lock: the model checker re-executes each interleaving many times, every re-execution replayed
 * writes to a real disk, and it was killed at twenty-five minutes without finishing three
 * iterations. Stress mode did finish and proved nothing — moving the precondition read out from
 * under the lock left it green.
 *
 * [ConditionalWrite] is the same code path with the files taken out: it holds a lock and calls two
 * lambdas, so the model checker walks its interleavings in seconds. What it says is the strongest
 * claim this store makes about concurrent writes — **two clients cannot both be told they created
 * the same key** — and it says it by enumeration rather than by racing threads and hoping.
 *
 * The map here is the test's own, and that is not a weakening: what is under test is the order of
 * the read and the write, and the map is a `ConcurrentHashMap` either way. What used to be
 * untestable was never the map.
 */
class ConditionalWriteLinearizabilityTest {
    private val entries = ConcurrentHashMap<String, ObjectStore.Stored>()
    private val conditional = ConditionalWrite(ReentrantLock())

    private fun version(tag: String) =
        ObjectStore.Stored(
            fileId = tag,
            size = tag.length.toLong(),
            eTag = tag,
            lastModified = Instant.EPOCH,
            metadata = Metadata(),
        )

    /** `If-None-Match: *` — create this key only if nobody got there first. */
    @Operation
    fun createIfAbsent(tag: String): String =
        try {
            conditional.install(
                ObjectStore.Precondition(ifNoneMatch = listOf("*")),
                current = { entries[KEY] },
            ) {
                entries[KEY] = version(tag)
                "created"
            }
        } catch (_: ObjectStore.PreconditionFailed) {
            // A result rather than an error: "somebody else got there first" is what this operation
            // is for, and a run where both callers are told they created it is the counterexample
            // worth finding.
            "refused"
        }

    /** `If-Match: <etag>` — replace this key only if it still holds what the caller saw. */
    @Operation
    fun replaceIfUnchanged(
        seen: String,
        tag: String,
    ): String =
        try {
            conditional.install(
                ObjectStore.Precondition(ifMatch = listOf(seen)),
                current = { entries[KEY] },
            ) {
                entries[KEY] = version(tag)
                "replaced"
            }
        } catch (_: ObjectStore.PreconditionFailed) {
            "refused"
        }

    @Operation
    fun read(): String? = entries[KEY]?.eTag

    @Operation
    fun remove(): Boolean = entries.remove(KEY) != null

    @Test
    fun `a conditional write is one step under every interleaving`() {
        ModelCheckingOptions()
            .iterations(30)
            .invocationsPerIteration(1_000)
            .threads(2)
            .actorsPerThread(3)
            .check(this::class)
    }

    private companion object {
        const val KEY = "photos/report.txt"
    }
}
