plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.benchmark) apply false
    alias(libs.plugins.sborkaJvm) apply false
    alias(libs.plugins.sborkaLint) apply false
    alias(libs.plugins.sborkaPublish) apply false
    alias(libs.plugins.sborkaMutation) apply false
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
 * So this figure bounds **development**. What ships is [shippedJvmArgs], which differs in exactly
 * one line and for exactly one measured reason (M-64).
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
 * What the distribution runs under.
 *
 * The same profile with a different heap, and the difference is the whole of M-64. The index holds
 * every key in memory, measured at 650 bytes each (`docs/measurements.md`), and half the heap is
 * what it may use — so 64 MiB would ship a store that refuses its 49 909th object. 512 MiB is
 * 399 215 objects, which is a number worth publishing rather than a number that happens.
 *
 * Both come from `Runtime.maxMemory()` rather than from `-Xmx`, because that is what the code
 * divides — and under `-XX:+UseSerialGC` the two are not the same number. A 512 MiB heap reports
 * 494.9 MiB: one survivor space is not counted, since objects can never be allocated across both
 * at once. Documented here as ~413 000 for a year, which is `-Xmx` over 650 and nothing the
 * process would ever print.
 *
 * Development stays at 64 MiB because it is enforcing something else there: that the hot path
 * does not allocate. Two numbers because they are two requirements, not one requirement measured
 * twice.
 */
val shippedJvmArgs = defaultJvmArgs.map { if (it == "-Xmx64M") "-Xmx512M" else it }

/**
 * The second shipped profile, and the whole of M33: the same list with a quarter of the heap.
 *
 * It exists because the chart cannot know how many objects a deployment will hold, and the heap is
 * what decides. Measured (M-233): 99 816 objects instead of 399 215, and in exchange a read of a
 * 300 MiB object costs 132 ms instead of 455 — because the heap and the page cache come out of one
 * cgroup, and the read path is `transferTo` from a **hot** file. The process itself needs 215 MiB
 * rather than 553.
 *
 * A whole profile rather than a knob, for the same reason `bochka.jvmArgs` replaces the list
 * instead of merging into it: a half-overridden profile is a third configuration nobody described,
 * and a number produced under it belongs to neither column.
 */
val smallJvmArgs = defaultJvmArgs.map { if (it == "-Xmx64M") "-Xmx128M" else it }

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
extra["bochkaShippedJvmArgs"] = if (footprintOverridden) jvmArgs else shippedJvmArgs

// An override is for answering "what does the footprint cost", and it answers it about one
// process: both scripts then carry the same replaced list, so the distribution still ships two
// entry points and they still describe the same profile.
extra["bochkaSmallJvmArgs"] = if (footprintOverridden) jvmArgs else smallJvmArgs

// The group, the version, the toolchain, the ktlint wiring, the JUnit platform, the test logging and
// the whole pitest harness came from here. They come from `ru.workinprogress.sborka` now, applied per
// module, with the numbers in `gradle.properties`.
//
// What stays is what is bochka's: the JVM-argument profiles above, the ABI dump of the one module
// anybody depends on, and the inputs those repository-wide checks read.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            // Public API is checked into `api/` and compared on every `check` — but only for the
            // module whose surface is the supported one. The others are published as well (see
            // `:bochka-embedded`: a POM naming a dependency nobody pushed resolves to nothing), and
            // a dump that changes on every internal edit trains everyone to update it without
            // reading the diff, which is the exact habit this check exists to prevent.
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

        // The same arguments, to the JVMs pitest forks. A minion is not the `Test` task and gets
        // nothing from it: without this the suite fails inside one with no mutation applied, pitest
        // refuses to mutate a suite that fails on its own, and `mutationTest` stops before it starts.
        plugins.withId("ru.workinprogress.sborka.mutation") {
            @Suppress("UNCHECKED_CAST")
            val profile = rootProject.extra["bochkaJvmArgs"] as List<String>
            extensions.configure<ru.workinprogress.sborka.MutationOptions>("sborkaMutation") {
                forkJvmArgs.addAll(profile)
                forkJvmArgs.add("-Dbochka.expectedJvmArgs=${profile.joinToString(" ")}")
                forkJvmArgs.add(
                    "-Dbochka.repoRoot=${rootProject.layout.projectDirectory.asFile.absolutePath}",
                )
                forkJvmArgs.add(
                    "-Dbochka.specDir=${
                        rootProject.layout.projectDirectory
                            .dir("docs/spec")
                            .asFile.absolutePath
                    }",
                )
                forkJvmArgs.add("-Djdk.httpclient.allowRestrictedHeaders=host")
                forkJvmArgs.add(
                    "-Dbochka.expectedJunit=${
                        rootProject.libs.versions.junit
                            .get()
                    }",
                )
            }
        }

        tasks.withType<Test>().configureEach {
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
            systemProperty("bochka.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)

            // What the catalog says JUnit is, handed to the test that checks it is true. Carried as
            // a property rather than read from the catalog inside the test, because a test that
            // reads the same file the build reads agrees with it by construction.
            systemProperty(
                "bochka.expectedJunit",
                rootProject.libs.versions.junit
                    .get(),
            )

            // And the tree those checks read is declared as an input, or Gradle keeps the task
            // up to date exactly when the thing being guarded changed. Caught by positive control:
            // Russian added to two `ci/` scripts left the run green, because a script is not an
            // input of anything the compiler touched, and only `--rerun-tasks` saw it. A gate that
            // stops running when its subject changes is worse than no gate — it reports success.
            if (project.name == "bochka-app") {
                inputs
                    .files(
                        rootProject.fileTree(rootProject.layout.projectDirectory) {
                            include(
                                "**/*.kt",
                                "**/*.kts",
                                "**/*.toml",
                                "**/*.yml",
                                "**/*.yaml",
                                "ci/**/*.py",
                                "ci/**/*.sh",
                                "ci/**/*.txt",
                            )
                            exclude("**/build/**", "**/.gradle/**", "**/.claude/**")
                        },
                    ).withPropertyName("sourcesReadByRepositoryWideChecks")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            }

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

            // Where the crash test puts the store, when it is being asked a question about a
            // filesystem rather than about the code (M-183). Passed through rather than defaulted
            // here: an empty value means the temp directory, which is every ordinary run.
            System.getProperty("bochka.crashDir")?.let { systemProperty("bochka.crashDir", it) }
        }
    }
}
