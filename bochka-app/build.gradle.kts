plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("ru.workinprogress.sborka.mutation")
    application
}

// Published because `:bochka-embedded` names it: a POM whose dependencies were never
// pushed resolves to nothing, and from the publishing side that looks exactly like a
// good publication. Only `:bochka-embedded` is a supported surface with a checked ABI.

dependencies {
    // `api`, not `implementation`: `S3Handler`'s constructor takes an `ObjectStore`, a
    // `SignatureVerifier` and an `S3Router`, so those types are part of this module's surface
    // whether the build file says so or not. `implementation` only hid it — from the build,
    // not from anybody who tried to construct one, which is what `:bochka-embedded` does.
    api(project(":bochka-s3"))
    api(project(":bochka-http"))

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

application {
    mainClass.set("io.github.youndie.bochka.app.Main")
    // The shipped profile, which is the development one with a larger heap: the index holds every
    // key, so the heap is the published ceiling on the number of objects (M-64). Everything else
    // about it — the collector, the code cache, the direct memory — is what the tests and the
    // benchmarks run under, so what ships is the process that was measured.
    @Suppress("UNCHECKED_CAST")
    applicationDefaultJvmArgs = rootProject.extra["bochkaShippedJvmArgs"] as List<String>
}

/**
 * The second entry point, carrying the small profile (M-235).
 *
 * A whole start script rather than a variable the first one reads: the heap decides the published
 * object ceiling, so a deployment that runs one of these is running a different product promise,
 * and it should have to name which. `JAVA_OPTS` stays refused for the same reason.
 */
@Suppress("UNCHECKED_CAST")
val smallStartScript =
    tasks.register<CreateStartScripts>("smallStartScript") {
        applicationName = "bochka-app-small"
        mainClass.set(application.mainClass)
        classpath = tasks.startScripts.get().classpath
        outputDir =
            layout.buildDirectory
                .dir("scriptsSmall")
                .get()
                .asFile
        defaultJvmOpts = rootProject.extra["bochkaSmallJvmArgs"] as List<String>
    }

distributions {
    named("main") {
        contents {
            from(smallStartScript) { into("bin") }
        }
    }
}

/**
 * Both scripts carry a **whole** profile, and they differ in exactly one flag.
 *
 * The check exists because the failure it catches is silent and was made once already, by hand: an
 * arm of a comparison that differs in two things measures neither of them, and an arm that lost a
 * flag entirely still starts and still answers. Nothing else in the build would notice — the flags
 * live in a generated shell script that no test reads.
 */
val checkProfiles =
    tasks.register("checkStartScriptProfiles") {
        dependsOn(tasks.startScripts, smallStartScript)
        val shipped = tasks.startScripts.map { it.outputDir!!.resolve("bochka-app") }
        val small = smallStartScript.map { it.outputDir!!.resolve("bochka-app-small") }
        doLast {
            // The line the script hands the JVM, read whole rather than searched for a pattern.
            // What stood here matched `-X…` and nothing else, so a `-D`, an `--add-opens` or an
            // `-ea` in one arm and not the other passed unseen — and the whole point of this check
            // is that an arm differing in two things measures neither. Proved by giving the small
            // profile a `-Dfoo=bar`: the check, and the gate around it, stayed green.
            fun flagsOf(file: java.io.File): List<String> {
                val line =
                    file
                        .readLines()
                        .firstOrNull { it.startsWith("DEFAULT_JVM_OPTS=") }
                        ?: error("no DEFAULT_JVM_OPTS in $file: the generated script changed shape")
                return Regex("\"([^\"]*)\"")
                    .findAll(line.substringAfter('='))
                    .map { it.groupValues[1] }
                    .sorted()
                    .toList()
            }

            val a = flagsOf(shipped.get())
            val b = flagsOf(small.get())
            check(a.isNotEmpty()) { "the shipped start script carries no JVM flags at all: ${shipped.get()}" }
            val onlyInA = a - b.toSet()
            val onlyInB = b - a.toSet()
            check(onlyInA.size == 1 && onlyInB.size == 1) {
                "the two profiles must differ in exactly one flag, and they differ in " +
                    "${onlyInA.size + onlyInB.size}: $onlyInA against $onlyInB"
            }
            check(onlyInA.single().startsWith("-Xmx") && onlyInB.single().startsWith("-Xmx")) {
                "the one difference has to be the heap, and it is ${onlyInA.single()} against ${onlyInB.single()}"
            }
        }
    }

tasks.check { dependsOn(checkProfiles) }
