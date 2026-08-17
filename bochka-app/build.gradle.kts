plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":bochka-s3"))
    implementation(project(":bochka-http"))

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
