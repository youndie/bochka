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
            // The repository root, for checks that read **sources** rather than compiled classes: a
            // test's working directory is its own module, while the rule about the language of the
            // code is one rule for the whole repository.
            systemProperty("bochka.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)

            // And the tree those checks read is declared as an input, or Gradle keeps the task
            // up to date exactly when the thing being guarded changed. Caught by positive control:
            // Russian added to two `ci/` scripts left the run green, because a script is not an
            // input of anything the compiler touched, and only `--rerun-tasks` saw it. A gate that
            // stops running when its subject changes is worse than no gate — it reports success.
            if (project.name == "bochka-app") {
                inputs
                    .files(
                        rootProject.fileTree(rootProject.layout.projectDirectory) {
                            include("**/*.kt", "**/*.kts", "ci/**/*.py", "ci/**/*.sh")
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

            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }

        // Mutation testing as a run rather than an exercise.
        //
        // In M28 seven mutations were made by hand and found two guards **nothing** caught, and two
        // tests that passed for a different reason than the one written in their names. Such a test
        // is green, looks like coverage, and stays green if the check it was written for is
        // removed. Hence the task: the same question asked in order, rather than wherever attention
        // happened to reach.
        //
        // The task is **not part of `check`** and must not be. It is slow, and its result is not a
        // threshold but a list of survivors, each of which is read on its own: a survival percentage
        // is as meaningless a number as a coverage percentage (`docs/mutation.md`).
        if (project.name != "bochka-benchmark") {
            val pitest = configurations.create("pitest")
            dependencies.add("pitest", rootProject.libs.pitest.cli)
            dependencies.add("pitest", rootProject.libs.pitest.junit5)

            tasks.register<JavaExec>("mutationTest") {
                group = "verification"
                description = "Breaks this module one place at a time and says what the tests did not notice"

                // The classes have to be compiled and the tests have to be green beforehand: pitest
                // refuses to mutate code whose suite fails without a mutation, and that is the
                // right refusal.
                dependsOn(tasks.named("test"))

                classpath = pitest
                mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
                javaLauncher.set(
                    project.extensions
                        .getByType<JavaToolchainService>()
                        .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) },
                )

                val sourceSets = project.extensions.getByType<SourceSetContainer>()
                val main = sourceSets.getByName("main")
                val test = sourceSets.getByName("test")
                val reportDir = layout.buildDirectory.dir("reports/pitest")

                // The forked test's profile travels here too. A pitest minion is a JVM of its own,
                // and the test comparing `bochka.expectedJvmArgs` fails inside one without any
                // mutation at all — at which point the run has not "found a defect", it has not
                // started. pitest separates these on commas, so a value with spaces inside one
                // argument gets through and one with a comma does not.
                @Suppress("UNCHECKED_CAST")
                val profile = rootProject.extra["bochkaJvmArgs"] as List<String>
                val specDir =
                    rootProject.layout.projectDirectory
                        .dir("docs/spec")
                        .asFile.absolutePath
                val forkArgs =
                    profile +
                        listOf(
                            "-Dbochka.expectedJvmArgs=${profile.joinToString(" ")}",
                            "-Dbochka.specDir=$specDir",
                            "-Djdk.httpclient.allowRestrictedHeaders=host",
                        )

                // Only this module's code is mutated, but everything it lives on goes on the path.
                val mutable = main.output.classesDirs
                val fullPath = test.runtimeClasspath

                // Narrow the run to one class or family: `-PmutationTarget=…S3Handler`. A whole
                // module takes tens of minutes, and narrowing it is the only way to ask about
                // **one** place and get the answer today.
                val target = providers.gradleProperty("mutationTarget").getOrElse("io.github.youndie.bochka.*")

                argumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "--reportDir",
                            reportDir.get().asFile.absolutePath,
                            "--targetClasses",
                            target,
                            "--targetTests",
                            "io.github.youndie.bochka.*",
                            "--sourceDirs",
                            main.allSource.srcDirs.joinToString(",") { it.absolutePath },
                            "--mutableCodePaths",
                            mutable.joinToString(",") { it.absolutePath },
                            "--classPath",
                            fullPath.joinToString(",") { it.absolutePath },
                            "--testPlugin",
                            "junit5",
                            "--outputFormats",
                            "HTML,XML",
                            "--timestampedReports",
                            "false",
                            "--jvmArgs",
                            forkArgs.joinToString(","),
                            "--threads",
                            Runtime.getRuntime().availableProcessors().toString(),
                        )
                    },
                )
            }
        }
    }
}
