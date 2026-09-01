package io.github.youndie.bochka.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.fail

/**
 * Fills the prepared volume so that the next allocation has nowhere to go.
 *
 * **In three passes, and the passes are the whole point.** One pass in mebibytes stops as soon as a
 * mebibyte does not fit and leaves whole blocks free elsewhere — enough for a small file, which is
 * exactly what a compaction's replacement log is. That volume starved the compaction on one machine
 * and not on the other: same test, same code, two answers. Each pass afterwards takes what the one
 * before it left, down to half a kilobyte.
 *
 * The filler belongs outside any store's own tree: inside one it would be swept as an orphan, and
 * the test would be measuring the sweep instead of the failure.
 */
internal fun fillTheVolume(filler: Path) {
    var wrote = false
    for (blockSize in intArrayOf(64 * 1024, 4096, 512)) {
        val block = ByteArray(blockSize)
        try {
            Files.newOutputStream(filler, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { out ->
                // Bounded rather than endless: a volume that does not end is the finding, and a run
                // that discovers it by writing until something else stops it reports that something
                // else. The stand is tens of mebibytes, so 256 of them is generous.
                repeat((256 shl 20) / blockSize) {
                    out.write(block)
                    out.flush()
                    wrote = true
                }
            }
            fail("wrote 256 MiB of $blockSize-byte filler without filling the volume: this stand constrains nothing")
        } catch (_: IOException) {
            // Expected: this is how the volume is brought to its edge.
        }
    }
    if (!wrote) fail("the filler wrote nothing at all: the volume was already full before the test began")
}
