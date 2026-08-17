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
