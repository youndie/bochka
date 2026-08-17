plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.benchmark) apply false
    alias(libs.plugins.ktlint)
}

/**
 * The runtime footprint bochka is developed under. A **constraint**, not a tuning knob: the tests
 * and the benchmark fork run inside it, so an allocation the hot path is not supposed to make fails
 * the gate instead of production.
 *
 * The mechanism is carried over from the neighbouring broker; the number is **not**, and the
 * difference matters (research, Р7). There the hot path holds no data, so one figure bounded the
 * whole process. Here the index holds every key in memory (Р1), so the two things a heap limit
 * would express are different:
 *
 * * **the hot path must not allocate** — parsing, framing and I/O. That is what this list enforces,
 *   and 64 MiB enforces it well;
 * * **how many objects fit** — a property of the index, published as a number once it has been
 *   measured (M-64), and deliberately absent from this file until then.
 *
 * So this figure is provisional in one direction only: it bounds development, and the shipped
 * default is the measured one. Whoever closes M-64 changes this line and says so in the backlog.
 */
val defaultJvmArgs =
    listOf(
        "-XX:+UseSerialGC",
        "-XX:ReservedCodeCacheSize=32M",
        "-XX:MaxDirectMemorySize=32M",
        "-Xss256k",
        "-XX:MaxMetaspaceSize=80M",
        "-Xmx64M",
    )

/**
 * `-Pbochka.jvmArgs="-Xmx4G -XX:+UseG1GC"` replaces the whole list for one invocation.
 *
 * For answering "what does the footprint cost", and nothing else. All-or-nothing rather than a
 * merge on purpose: a half-overridden profile is a third configuration nobody described, and a
 * number produced under it belongs to neither column of the comparison.
 */
val jvmArgs: List<String> =
    (project.findProperty("bochka.jvmArgs") as String?)
        ?.split(" ")
        ?.filter(String::isNotEmpty)
        ?: defaultJvmArgs

/**
 * Tells the footprint check that the profile was replaced on purpose, so it reports what it got
 * instead of failing. Only ever set when the override was actually passed.
 */
val footprintOverridden = project.hasProperty("bochka.jvmArgs")

extra["bochkaJvmArgs"] =
    if (footprintOverridden) jvmArgs + "-Dbochka.footprintOverridden=true" else jvmArgs

allprojects {
    // `io.github.<login>` — coordinates whose ownership is proved by owning the GitHub account.
    group = "io.github.youndie.bochka"
    // A default that is a snapshot on purpose: a build with no `-PVERSION` must not be able to
    // produce something that looks like a release.
    version = providers.gradleProperty("VERSION").getOrElse("0.1.0-SNAPSHOT")
}

// The point of this block: the gate is one command. `./gradlew check` runs the tests AND ktlint, in
// every module, without anybody remembering a second line in CI.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint)
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            // 25, and it is on the critical path rather than a preference: `FileChannel.map(mode,
            // offset, size, Arena)` gives a mapping without the 2 GB ceiling and with a
            // deterministic release, which is what the index log needs.
            jvmToolchain(25)

            // Public API is checked into `api/` and compared on every `check` — but only for the
            // one module anybody can depend on. `:bochka-app` and the rest publish nothing, and a
            // dump that changes on every internal edit trains everyone to update it without reading
            // the diff, which is the exact habit this check exists to prevent.
            if (project.name == "bochka-embedded") {
                @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
                abiValidation {
                    referenceDumpDir.set(rootProject.layout.projectDirectory.dir("api"))
                }
                // The plugin does not wire the check into `check`, and a gate nobody runs is not a
                // gate.
                tasks.named("check") { dependsOn(tasks.named("checkKotlinAbi")) }
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()

            @Suppress("UNCHECKED_CAST")
            val profile = rootProject.extra["bochkaJvmArgs"] as List<String>
            jvmArgs(profile)
            // The expectation travels with the profile rather than being copied into the test, so
            // the list has exactly one home. It is also itself a JVM argument, which is the point:
            // if the profile did not reach the forked JVM, neither did this, and the check fails on
            // a missing expectation instead of quietly having nothing to compare against.
            systemProperty("bochka.expectedJvmArgs", profile.joinToString(" "))

            // Tests read the specification out of the repository rather than off the network, and
            // they cite a file and a line in it (project rule 2). The path is injected because a
            // test's working directory is its module, and `../docs/spec` in a dozen tests is a
            // dozen places to fix when a module moves.
            systemProperty(
                "bochka.specDir",
                rootProject.layout.projectDirectory
                    .dir("docs/spec")
                    .asFile.absolutePath,
            )
            // The end-to-end tests drive the server with the JDK's own HTTP client, and one thing
            // they have to be able to set is `Host`: it decides virtual-hosted routing and it is
            // signed, so a mismatch between the two is exactly the failure worth reproducing. The
            // client refuses to set it unless told to here.
            systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")

            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
