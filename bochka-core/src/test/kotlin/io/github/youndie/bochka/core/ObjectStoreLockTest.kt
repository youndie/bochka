package io.github.youndie.bochka.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One process to a data directory (M-224).
 *
 * The price of not having this was measured rather than imagined: two servers pointed at one
 * directory each accepted a hundred and fifty writes and answered `200` to every one of them, and
 * a third process opening that directory afterwards recovered **nothing** — zero records on NFS,
 * two on ext4, some thirty kilobytes of journal discarded as unreadable
 * ([docs/measurements.md](../../../../../../../docs/measurements.md), M-183). Three hundred
 * acknowledged writes, no data, and no error anybody could have acted on.
 *
 * That is not a network-filesystem problem, which is what made it worth a lock rather than a note:
 * a second `docker run` beside a pod, on the same volume, on a local disk, does exactly the same.
 *
 * **The other half of the requirement lives in [ObjectStoreCrashTest].** A lock that outlived the
 * process holding it would turn every crash into a store that cannot be opened — strictly worse
 * than what it prevents. Nothing here asserts that, because the crash test already kills a writer
 * with `SIGKILL` and opens the directory afterwards: if the lock went stale, every round of it
 * would fail.
 */
class ObjectStoreLockTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("bochka-lock")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a second store on the same directory is refused`() {
        withDir { dir ->
            ObjectStore(dir).use {
                val refused = assertFailsWith<ObjectStore.DirectoryInUse> { ObjectStore(dir) }
                assertTrue(
                    dir.toString() in refused.message,
                    "the refusal has to name the directory: ${refused.message}",
                )
            }
        }
    }

    @Test
    fun `the directory is free again once the store is closed`() {
        withDir { dir ->
            ObjectStore(dir).close()
            // And the second store is a working one, not merely one that opened: a lock taken and
            // never released would make this the last store this directory ever has.
            ObjectStore(dir).use { store ->
                store.createBucket("b")
                assertTrue(store.bucketNames().contains("b"))
            }
        }
    }
}
