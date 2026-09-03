package io.github.youndie.bochka.core

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every barrier this store's durability rests on is still in the compiled class.
 *
 * A gate on a **precondition**, like the zero-copy one in `:bochka-http`, and for a harder reason:
 * here the result is not merely identical, it is unobservable from inside a JVM at all. `fsync` is
 * what survives the **machine** stopping; the crash test kills a process, and a process death
 * leaves the page cache exactly where it was, so the parent reads back everything the child wrote
 * whether or not anything was flushed.
 *
 * That is measured rather than argued. With both `force` calls on the write path turned off, the
 * whole gate — **779 tests, four of them the crash tests** — stayed green. Nothing in this
 * repository was looking at this, which is also why mutating the calls away could not be caught:
 * the crash test spawns its writer as a separate JVM, so a mutation never reaches the code under
 * test in the first place.
 *
 * So the rule is a property of the bytecode. What it deliberately does **not** claim: that the
 * barrier is in the right place, or that it is called at the right moment. Order is what the crash
 * test is for, and it does test that. This one only says the call has not gone missing.
 */
class DurabilityBarrierTest {
    /**
     * Every function of [ObjectStore] that must reach the disk before it says it has.
     *
     * Named one at a time rather than counted, because a count is satisfied by any seven and this
     * has to be satisfied by these seven. The list was taken from the source, and the test that
     * follows it fails if a name here stops existing — a renamed function whose gate silently
     * checks nothing is the failure mode this shape of test has.
     */
    private val mustFlush =
        mapOf(
            "stage" to "the object's bytes, before the rename that publishes them",
            "copy" to "a server-side copy is a new object and gets the same barrier",
            "stagePartFrom" to "a part copied from another object",
            "completeUpload" to "the assembled multipart object",
            "write" to "the index record, after the file it points at",
            "syncDirectory" to "the directory entry, so the rename itself survives",
            "claimDirectory" to "the lock file naming the process that holds this store",
        )

    @Test
    fun `a store nobody configured is the one that flushes`() {
        // The half above this one guards the calls; this one guards the switch in front of them.
        // Every `force` on the write path stands under `if (durability == Durability.FSYNC)`, so
        // the bytecode gate is equally happy with a distribution that never reaches the disk — and
        // so is everything else here. Measured: with the default flipped to `NONE`, the whole gate
        // stayed green, crash tests included, for the reason this file already names — a process
        // death leaves the page cache where it was.
        //
        // The server builds its store without naming durability (Main.kt), so this default is the
        // one that ships. Every test that wants the barrier for its own reasons passes `FSYNC`
        // explicitly, which is why none of them was ever looking at this.
        val dir = Files.createTempDirectory("bochka-durability-default")
        try {
            ObjectStore(dir).use { store ->
                assertEquals(
                    ObjectStore.Durability.FSYNC,
                    store.durability,
                    "a store built the way the server builds it does not flush",
                )
            }
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun `every function that promises durability still calls force`() {
        val classes = Path.of("build/classes/kotlin/main")
        assertTrue(Files.isDirectory(classes), "no compiled classes at $classes; run this through the build")

        val flushing = HashSet<String>()
        val seen = HashSet<String>()
        Files.walk(classes).use { walk ->
            for (file in walk.filter { it.fileName.toString().startsWith("ObjectStore") }) {
                if (!file.toString().endsWith(".class")) continue
                ClassReader(Files.readAllBytes(file)).accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor {
                            // A lambda keeps the name of the function it was written in, so
                            // `copy$lambda$3` counts for `copy`. Anything else would make the rule
                            // depend on whether the enclosing call happened to be inline.
                            val owner = name.substringBefore('$')
                            seen += owner
                            return object : MethodVisitor(Opcodes.ASM9) {
                                override fun visitMethodInsn(
                                    opcode: Int,
                                    ownerClass: String,
                                    called: String,
                                    calledDescriptor: String,
                                    isInterface: Boolean,
                                ) {
                                    if (called == "force") flushing += owner
                                }
                            }
                        }
                    },
                    ClassReader.SKIP_FRAMES,
                )
            }
        }

        // First that the list still describes this class, and only then what it claims. A gate
        // whose subjects have been renamed away passes by having nothing to check.
        assertEquals(
            emptySet(),
            mustFlush.keys - seen,
            "these functions are gone from ObjectStore; the list above is describing an older version",
        )

        val missing = mustFlush.filterKeys { it !in flushing }
        assertTrue(
            missing.isEmpty(),
            "no barrier left in: " + missing.entries.joinToString("; ") { "${it.key} (${it.value})" },
        )
    }
}
