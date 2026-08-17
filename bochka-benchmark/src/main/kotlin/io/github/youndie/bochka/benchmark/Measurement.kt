package io.github.youndie.bochka.benchmark

import java.lang.management.ManagementFactory

/**
 * One measured run, and the shape every number in this project is reported in.
 *
 * Two rules, both learned expensively next door.
 *
 * **The axis is CPU per byte, not throughput.** Throughput on a warm page cache measures the page
 * cache; two ways of moving the same bytes can have identical throughput and differ threefold in
 * processor time, and the one that matters to a server with other work to do is the second. The
 * clock is still reported, because a variant that is cheaper and slower is a different trade and
 * hiding it would be a choice made silently.
 *
 * **The claim is a ratio, never an absolute.** A threshold in nanoseconds on a shared machine
 * measures the machine, and the same measurement moved from an SSD to WSL2 to APFS produced three
 * different verdicts about the same code. Two variants run in the same process, minutes apart, on
 * the same files, and what is published is how they compare.
 */
class Measurement(
    val name: String,
    val bytes: Long,
    val wallNanos: Long,
    val cpuNanos: Long,
) {
    val gibibytes: Double get() = bytes / (1024.0 * 1024 * 1024)
    val cpuSecondsPerGiB: Double get() = (cpuNanos / 1e9) / gibibytes
    val gibPerSecond: Double get() = gibibytes / (wallNanos / 1e9)

    override fun toString(): String =
        "%-34s %8.3f GiB  %7.3f s wall  %7.3f s cpu  %6.3f cpu-s/GiB  %6.2f GiB/s".format(
            name,
            gibibytes,
            wallNanos / 1e9,
            cpuNanos / 1e9,
            cpuSecondsPerGiB,
            gibPerSecond,
        )

    companion object {
        private val threads = ManagementFactory.getThreadMXBean()

        /**
         * Times [work] on the calling thread, counting its own processor time.
         *
         * Per-thread rather than per-process, because the other side of a loopback socket is in
         * this process too: a whole-process figure would fold the reader's cost into the writer's
         * and flatten exactly the difference being measured. On Linux the thread figure includes
         * system time, which is where `sendfile` does its work — a measurement that counted only
         * user time would report zero-copy as free and be wrong in the other direction.
         */
        inline fun of(
            name: String,
            bytes: Long,
            work: () -> Unit,
        ): Measurement {
            val cpuBefore = currentThreadCpuNanos()
            val wallBefore = System.nanoTime()
            work()
            val wall = System.nanoTime() - wallBefore
            return Measurement(name, bytes, wall, currentThreadCpuNanos() - cpuBefore)
        }

        fun currentThreadCpuNanos(): Long =
            if (threads.isCurrentThreadCpuTimeSupported) threads.currentThreadCpuTime else 0L

        /**
         * Runs a variant several times and keeps the median, with the spread beside it.
         *
         * One shot per variant is not a measurement, and this stand proved it: the same
         * direct-buffer variant came back at 1.14 and then 2.42 processor-seconds per gibibyte in
         * two consecutive runs, while the variant next to it repeated to within one percent. A
         * single number from each would have supported opposite conclusions on consecutive days,
         * and both would have looked equally definite.
         *
         * The spread is printed rather than smoothed away: a variant that varies by half is not a
         * variant with a value, and saying so is the honest output.
         */
        inline fun repeated(
            name: String,
            bytes: Long,
            times: Int = 3,
            run: () -> Measurement,
        ): Repeated {
            val runs = (0 until times).map { run() }.sortedBy { it.cpuSecondsPerGiB }
            return Repeated(name, runs[runs.size / 2], runs.first(), runs.last())
        }

        /** `b` against `a`, which is the only form a claim about performance is made in here. */
        fun compare(
            a: Measurement,
            b: Measurement,
        ): String =
            "%s costs %.2fx the processor of %s per byte, and runs at %.2fx its rate".format(
                b.name,
                b.cpuSecondsPerGiB / a.cpuSecondsPerGiB,
                a.name,
                b.gibPerSecond / a.gibPerSecond,
            )
    }
}

/** A variant measured more than once: the median, and how far the runs were apart. */
class Repeated(
    val name: String,
    val median: Measurement,
    val fastest: Measurement,
    val slowest: Measurement,
) {
    /** How much the runs disagreed. Anything much above 1 means the stand, not the code. */
    val spread: Double get() = slowest.cpuSecondsPerGiB / fastest.cpuSecondsPerGiB

    override fun toString(): String {
        val line =
            "%-34s %8.3f GiB  %7.3f s wall  %6.3f cpu-s/GiB  %6.2f GiB/s  spread %.2fx".format(
                name,
                median.gibibytes,
                median.wallNanos / 1e9,
                median.cpuSecondsPerGiB,
                median.gibPerSecond,
                spread,
            )
        return if (spread > 1.3) "$line   <- too noisy to draw a conclusion from" else line
    }
}
