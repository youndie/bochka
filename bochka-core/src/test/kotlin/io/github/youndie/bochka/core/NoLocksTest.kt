package io.github.youndie.bochka.core

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The read path holds no monitor, and it is checked by reading the bytecode rather than by
 * remembering.
 *
 * A `synchronized` block on the path a `GET` takes does not fail a test — it makes every reader
 * queue behind every other one, which shows up as latency under load and as nothing at all in a
 * unit test. So the rule is a property of the compiled class, and this is what enforces it.
 *
 * The **writer** is deliberately outside the rule: appending to the index log is a serialisation
 * point by nature, and it uses a `ReentrantLock` — a lock, but one that can be timed out, tried,
 * and seen in a thread dump for what it is. What is banned here is the intrinsic monitor, which
 * offers none of that.
 */
class NoLocksTest {
    private val hotPath =
        setOf(
            "ObjectKey",
            "RecordLog",
            "RecordLog\$Window",
            "IndexRecord",
            "IndexRecord\$Companion",
        )

    @Test
    fun `nothing on the read path enters a monitor`() {
        val classes = Path.of("build/classes/kotlin/main")
        assertTrue(Files.isDirectory(classes), "no compiled classes at $classes; run this through the build")

        val offenders = ArrayList<String>()
        Files.walk(classes).use { walk ->
            for (file in walk.filter { it.toString().endsWith(".class") }) {
                val simple = file.fileName.toString().removeSuffix(".class")
                if (simple !in hotPath) continue
                val reader = ClassReader(Files.readAllBytes(file))
                reader.accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor {
                            if (access and Opcodes.ACC_SYNCHRONIZED != 0) {
                                offenders += "$simple.$name is declared synchronized"
                            }
                            return object : MethodVisitor(Opcodes.ASM9) {
                                override fun visitInsn(opcode: Int) {
                                    if (opcode == Opcodes.MONITORENTER) {
                                        offenders += "$simple.$name enters a monitor"
                                    }
                                }
                            }
                        }
                    },
                    ClassReader.SKIP_FRAMES,
                )
            }
        }

        assertTrue(offenders.isEmpty(), "the read path must hold no monitor: $offenders")
    }

    @Test
    fun `the check is looking at classes that exist`() {
        // Without this, renaming a class turns the gate above into a test of nothing, silently —
        // it would walk the directory, match none of the names and pass.
        val classes = Path.of("build/classes/kotlin/main")
        val found =
            Files
                .walk(classes)
                .use { walk ->
                    walk
                        .filter { it.toString().endsWith(".class") }
                        .map { it.fileName.toString().removeSuffix(".class") }
                        .toList()
                }.toSet()

        val missing = hotPath - found
        assertTrue(missing.isEmpty(), "the gate names classes that are not compiled any more: $missing")
    }
}
