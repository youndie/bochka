package io.github.youndie.bochka.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Random sequences of writes, deletes and restarts against a model that is obviously right.
 *
 * The model is a sorted map. What is being checked is that the store agrees with it after any
 * sequence of operations and after being closed and reopened at any point — which is the property
 * the log, the recovery and the in-memory index exist to provide together, and which the
 * hand-written tests only check along the paths somebody thought of.
 *
 * The keys are drawn from a deliberately small alphabet: shared prefixes and near-misses are where
 * an ordering or a prefix bound goes wrong, and random long keys almost never collide.
 */
class ObjectStorePropertiesTest {
    private val dir: Path = Files.createTempDirectory("bochka-properties")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun key(random: Random): String {
        val alphabet = listOf("a", "b", "a/", "b/", "1", "2", "é", "😀")
        return (0..random.nextInt(1, 4)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
    }

    @Test
    fun `the store agrees with a sorted map through writes, deletes and restarts`() =
        runTest {
            val model = sortedMapOf<String, String>(compareBy { it })
            val random = Random(20260817)
            var store = ObjectStore(dir, ObjectStore.Durability.NONE)
            store.createBucket("b")

            repeat(2000) { step ->
                when (random.nextInt(10)) {
                    in 0..5 -> {
                        val k = key(random)
                        val value = "value-$step"
                        store.put("b", ObjectKey.of(k), null) { out -> out.write(value.toByteArray()) }
                        model[k] = value
                    }

                    in 6..7 -> {
                        val k = key(random)
                        store.delete("b", ObjectKey.of(k))
                        model.remove(k)
                    }

                    8 -> {
                        // A restart at an arbitrary moment: everything acknowledged has to come
                        // back, in the same order, pointing at the same bytes.
                        store.close()
                        store = ObjectStore(dir, ObjectStore.Durability.NONE)
                    }

                    else -> {
                        val listed = store.list("b", ByteArray(0), Int.MAX_VALUE)
                        assertEquals(
                            model.keys.sortedWith(compareBy(UNSIGNED_BYTES) { it.toByteArray() }),
                            listed.map { it.first.toString() },
                            "step $step: the listing disagrees with the model",
                        )
                    }
                }
            }

            val listed = store.list("b", ByteArray(0), Int.MAX_VALUE)
            assertEquals(
                model.keys.sortedWith(compareBy(UNSIGNED_BYTES) { it.toByteArray() }),
                listed.map { it.first.toString() },
            )
            for ((key, stored) in listed) {
                assertEquals(model[key.toString()], String(Files.readAllBytes(store.pathOf(stored))), "$key")
            }
            store.close()
        }

    @Test
    fun `a prefix bounds the listing exactly`() =
        runTest {
            ObjectStore(dir, ObjectStore.Durability.NONE).use { store ->
                store.createBucket("b")
                val random = Random(4)
                val all = HashSet<String>()
                repeat(500) {
                    val k = key(random)
                    all.add(k)
                    store.put("b", ObjectKey.of(k), null) { out -> out.write(k.toByteArray()) }
                }

                for (prefix in listOf("", "a", "a/", "b", "é", "😀")) {
                    val expected =
                        all
                            .filter {
                                it.startsWith(
                                    prefix,
                                )
                            }.sortedWith(compareBy(UNSIGNED_BYTES) { it.toByteArray() })
                    val actual = store.list("b", prefix.toByteArray(), Int.MAX_VALUE).map { it.first.toString() }
                    assertEquals(expected, actual, "prefix '$prefix'")
                }
            }
        }

    private companion object {
        /** The order a listing is defined by; `String` comparison is a different one (§1.5). */
        val UNSIGNED_BYTES: Comparator<ByteArray> = Comparator { a, b -> java.util.Arrays.compareUnsigned(a, b) }
    }
}
