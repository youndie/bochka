plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.benchmark)
}

dependencies {
    implementation(project(":bochka-core"))
    implementation(libs.benchmark.runtime)
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

benchmark {
    targets {
        register("main")
    }
    configurations {
        named("main") {
            // The measured JVM runs under the same footprint as everything else. JMH passes the
            // host JVM's arguments to the fork, which is why the check in `RuntimeFootprint` exists
            // rather than a comment saying it does.
            iterations = 5
            warmups = 5
            iterationTime = 2
            iterationTimeUnit = "s"
        }
    }
}

// The benchmark fork inherits the host JVM's arguments from JMH, so the profile has to be on the
// JVM that kotlinx-benchmark launches. Set here rather than in the root build file because the
// benchmark plugin creates these tasks itself, after the root configuration has run.
tasks.withType<JavaExec>().configureEach {
    @Suppress("UNCHECKED_CAST")
    jvmArgs(rootProject.extra["bochkaJvmArgs"] as List<String>)
}

// The measurements of M8, which are not benchmarks in the JMH sense and are deliberately not run
// by `check`: they move gigabytes through a disk, take minutes, and answer questions whose answer
// belongs in a document rather than in a pass/fail gate. What they found is in
// docs/measurements.md.
//
// `-Pbochka.measure=serve|write|assemble|all`; `BOCHKA_MEASURE_DIR` says where the files go and
// is refused if it points at a memory filesystem.
tasks.register<JavaExec>("measure") {
    group = "verification"
    description = "Runs the M8 measurements and prints what they found"
    mainClass.set("io.github.youndie.bochka.benchmark.Measurements")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(project.findProperty("bochka.measure") as String? ?: "all")
}
