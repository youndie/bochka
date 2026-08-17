package io.github.youndie.bochka.benchmark

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a measurement is allowed to put its files.
 *
 * It refuses a volatile filesystem, and that is not fussiness. On Ubuntu `/tmp` is `tmpfs`, which
 * is memory: `fsync` there costs about a hundredth of a millisecond, so a durability measurement
 * run in the default temporary directory reports a barrier that does not exist. The neighbouring
 * broker published such a number and had to retract it, which is why this check exists here before
 * the first measurement rather than after the first retraction.
 *
 * The rule is a refusal rather than a warning on purpose: a warning in a log is indistinguishable
 * from no warning at all by the time the number reaches a README.
 */
object MeasurementDir {
    private val VOLATILE = setOf("tmpfs", "ramfs", "devtmpfs")

    class Refused(
        override val message: String,
    ) : RuntimeException(message)

    fun of(path: Path): Path {
        Files.createDirectories(path)
        val store = Files.getFileStore(path)
        if (store.type() in VOLATILE) {
            throw Refused(
                "$path is on ${store.type()}, which is memory: a measurement there describes RAM " +
                    "and says nothing about a disk. Point BOCHKA_MEASURE_DIR at real storage.",
            )
        }
        return path
    }

    /** Printed beside every number, because a number without its filesystem is about nothing. */
    fun describe(path: Path): String {
        val store = Files.getFileStore(path)
        return "${store.name()} (${store.type()})"
    }
}
