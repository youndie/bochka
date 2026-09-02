package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The storage class is in the index, not in memory (M-301).
 *
 * A field that survives until the process restarts is not a stored field, and the difference is
 * invisible in every test that does not reopen the store — which is every other test of this
 * feature. The journal is where an object's class has to live, and adding a field there is the one
 * part of this change that could be got wrong quietly: an old record must keep decoding to exactly
 * what it meant, which for a record written before classes existed is `STANDARD`.
 */
class StorageClassRecordTest {
    private val home: Path = Files.createTempDirectory("bochka-storage-class")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() = home.deleteRecursively()

    @Test
    fun `a class written before a restart is there after it`() =
        runTest {
            ObjectStore(home, ObjectStore.Durability.NONE).use { store ->
                store.createBucket("photos")
                store.put("photos", ObjectKey.of("cold"), Metadata(), storageClass = "ONEZONE_IA") { sink ->
                    sink.write("bytes".toByteArray(), 0, 5)
                }
                store.put("photos", ObjectKey.of("warm"), Metadata()) { sink ->
                    sink.write("bytes".toByteArray(), 0, 5)
                }
            }

            ObjectStore(home, ObjectStore.Durability.NONE).use { reopened ->
                assertEquals("ONEZONE_IA", reopened.get("photos", ObjectKey.of("cold"))?.storageClass)
                // The other half, and it is the one that says an old journal still reads right: a
                // version written without a class is STANDARD rather than null or empty.
                assertEquals("STANDARD", reopened.get("photos", ObjectKey.of("warm"))?.storageClass)
            }
        }
}
