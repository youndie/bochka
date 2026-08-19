package io.github.youndie.bochka.core

/**
 * Which collector this store was measured under, and where the measurements stop (M-156, M-157).
 *
 * The choice of collector is not a tuning knob here, and that is why it is announced rather than
 * left in the build file. Two things depend on it, and both are published numbers:
 *
 * * **the object ceiling**, because it is derived from `Runtime.maxMemory()` and that is a property
 *   of the collector. At the same `-Xmx512M` the shipped profile reports 494 MiB under Serial, 455
 *   under Parallel and 512 under G1 — so the ceiling is 399 215, 367 404 or 412 977 for one heap
 *   size (`docs/measurements.md`, M-152). A collector swapped in a wrapper moves a number in the
 *   README without touching a line of it;
 * * **the pause a request can meet**, which grows with the live set and the live set here is the
 *   index. Measured under Serial: 0.93 s at a 512 MiB heap, 3.84 s at 2 GiB, 7.56 s at 4 GiB,
 *   single-threaded and stop-the-world.
 *
 * The form of the answer is deliberate and was decided before the measurement, because both
 * alternatives are worse. A **silent threshold** on `-Xmx` would mean 511 and 513 MiB behave
 * differently with nothing in any log saying why. A **menu of profiles** is N promises instead of
 * one, each of which had to be measured; a profile nobody measured is worse than no profile,
 * because it looks chosen.
 *
 * So: one shipped profile, announced at startup, and a loud line — **not a refusal** — when the
 * process is outside what was measured. The difference from the object ceiling is deliberate too:
 * going over that is a refusal to start, because there the server cannot do what it says it does.
 * Here it can, just worse, and a person is entitled to raise the heap. They are not entitled to
 * not be told.
 */
object GcProfile {
    /**
     * The collector the numbers in `docs/measurements.md` were taken under, and the one the
     * distribution sets.
     *
     * Serial, and the measurement is why rather than habit. Against G1 on the shipped heap it is
     * three times worse on a full collection (0.93 s against 0.30) and identical on everything the
     * application actually felt — under eight gibibytes of garbage neither collector did a full
     * collection at all, and both stalled at most 18 ms. What Serial wins is footprint: 504 MiB of
     * RSS against 605, twenty percent, on a deployment whose memory limit is derived from exactly
     * that number (M-148). ParallelGC was refused by the measurement rather than by taste — 139
     * full collections in one run and stalls up to 992 ms.
     */
    const val MEASURED_COLLECTOR = "Serial"

    /**
     * The largest heap the shipped collector was measured on and found acceptable.
     *
     * A round number between two measured points rather than a measured point itself, and it is the
     * honest place for it: at 512 MiB a full collection is 0.93 s, at 2 GiB it is 3.84 s, and the
     * live set — hence the pause — grows with the heap. A gibibyte is where the pause reaches
     * roughly two seconds. Above it nothing breaks; it stops being a number this project has stood
     * behind.
     */
    const val MEASURED_HEAP_BYTES = 1024L * 1024 * 1024

    /** What the process is running under, as the JVM reports it: `Serial`, `Parallel`, `G1`, `Z`. */
    fun collector(): String {
        val names =
            java.lang.management.ManagementFactory
                .getGarbageCollectorMXBeans()
                .joinToString(" ") { it.name }
        return when {
            "MarkSweepCompact" in names -> "Serial"
            "PS " in names -> "Parallel"
            names.startsWith("G1") -> "G1"
            "Z" in names -> "Z"
            "Shenandoah" in names -> "Shenandoah"
            else -> names.substringBefore(' ').ifEmpty { "unknown" }
        }
    }

    /** The line printed at startup beside the object ceiling. */
    fun describe(
        collector: String = collector(),
        heapBytes: Long = Runtime.getRuntime().maxMemory(),
    ): String = "collector: $collector at ${heapBytes / (1024 * 1024)} MiB of heap"

    /**
     * What to say when this process is outside what was measured, or null when it is inside.
     *
     * Two ways out of the envelope and they are different failures, so they read differently. A
     * heap larger than what was measured keeps every promise except the size of the pause. A
     * collector other than the measured one moves the **published ceiling**, which is a number
     * somebody sized a deployment by.
     */
    fun beyondWhatWasMeasured(
        collector: String = collector(),
        heapBytes: Long = Runtime.getRuntime().maxMemory(),
    ): String? =
        when {
            collector != MEASURED_COLLECTOR -> {
                "collector: $collector is not the one this distribution was measured under " +
                    "($MEASURED_COLLECTOR). The object ceiling printed above is derived from " +
                    "Runtime.maxMemory(), which every collector reports differently — measured at the " +
                    "same -Xmx512M: 455 MiB under Parallel, 494 under Serial, 512 under G1. Nothing " +
                    "is broken; the published number is now about a configuration nobody measured."
            }

            heapBytes > MEASURED_HEAP_BYTES -> {
                "heap: ${heapBytes / (1024 * 1024)} MiB is beyond what this distribution was measured " +
                    "on (up to ${MEASURED_HEAP_BYTES / (1024 * 1024)} MiB). The live set is the index, " +
                    "so a full collection grows with it: measured under $MEASURED_COLLECTOR at 0.93 s " +
                    "on 512 MiB, 3.84 s on 2 GiB and 7.56 s on 4 GiB, stop-the-world " +
                    "(docs/measurements.md). This is a note, not a refusal."
            }

            else -> {
                null
            }
        }
}
