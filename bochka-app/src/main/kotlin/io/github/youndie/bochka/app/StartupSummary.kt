package io.github.youndie.bochka.app

import io.github.youndie.bochka.core.RecordLog

/**
 * How long the start took, and what took it (M-293).
 *
 * Recovery has been measured — under 0.9 s for half a million objects — and the index line beside
 * this one has said what the log held since M-222. What neither said is **how long any of it
 * took**, so a start that suddenly takes eight seconds leaves an operator with a stopwatch and a
 * guess. A number that arrives with its own breakdown is the difference between "it is slow" and
 * "it is slow because the log is eleven times the live set".
 *
 * Two numbers rather than one, for the reason this project applies to every measurement: a total
 * on its own names nothing. The share spent reading the index is the half that grows with the
 * store, and everything else — the JVM, the configuration, binding a socket — is the half that
 * does not.
 */
object StartupSummary {
    /**
     * @param totalMillis from the process starting to the socket being ready to answer
     * @param recoveryMillis the part of it spent opening the store and replaying its log
     */
    fun of(
        totalMillis: Long,
        recoveryMillis: Long,
        recovery: RecordLog.Recovery,
    ): String {
        val rest = (totalMillis - recoveryMillis).coerceAtLeast(0)
        return buildString {
            append("started in ").append(totalMillis).append(" ms: ")
            append(recoveryMillis).append(" ms reading ").append(recovery.records).append(" index records, ")
            append(rest).append(" ms everything else")
            // Named only when it happened, and named loudly when it did: a discarded tail is what
            // the **last** shutdown left, and it is the one line that says the process before this
            // one did not stop cleanly.
            if (recovery.discardedBytes > 0) {
                append(" (a torn tail of ")
                    .append(recovery.discardedBytes)
                    .append(" bytes was discarded: the last stop was not a clean one)")
            }
        }
    }
}
