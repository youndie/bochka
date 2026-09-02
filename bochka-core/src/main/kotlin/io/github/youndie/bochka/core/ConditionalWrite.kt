package io.github.youndie.bochka.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one step a conditional write is: read what the key holds, decide, and write — with nothing
 * able to happen in between.
 *
 * ## Why this is a class rather than two lines
 *
 * It was two lines inside [ObjectStore.commit], under the same lock and correct, with a comment
 * saying so. A comment is not checkable: moving the read a few lines up is a one-character diff
 * that no test in this repository noticed (M-286, measured — the stress run stayed green), and the
 * failure it produces is two clients both told they created a key that only one of them created.
 *
 * As a class the order is not a convention any more: the caller hands over *how* to read and *what*
 * to write, and cannot put anything between them. And it can be enumerated — it holds a lock and a
 * lambda, no files, so Lincheck's model checker finishes on it in seconds where against the whole
 * store it did not finish in twenty-five minutes.
 *
 * The lock is passed in rather than created here: [ObjectStore] takes it in eight other places for
 * things that are not conditional writes, and two locks would be two orders.
 */
internal class ConditionalWrite(
    private val lock: ReentrantLock,
) {
    /**
     * Runs [write] only if [precondition] holds for what [current] returns, all under the lock.
     *
     * Throws [ObjectStore.PreconditionFailed] with the outcome, which is what the caller turns into
     * the status a client sees: "there is no object at this key" and "the object is not the one
     * described" are different refusals and different codes.
     */
    fun <T> install(
        precondition: ObjectStore.Precondition,
        current: () -> ObjectStore.Stored?,
        write: () -> T,
    ): T =
        lock.withLock {
            when (val outcome = precondition.holdsFor(current())) {
                ObjectStore.Outcome.HELD -> {
                    write()
                }

                ObjectStore.Outcome.ABSENT -> {
                    throw ObjectStore.PreconditionFailed(outcome, "there is no object at this key")
                }

                ObjectStore.Outcome.MISMATCH -> {
                    throw ObjectStore.PreconditionFailed(outcome, "the object is not the one described")
                }
            }
        }
}
