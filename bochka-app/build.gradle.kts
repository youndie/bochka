plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Published because `:bochka-embedded` names it: a POM whose dependencies were never
// pushed resolves to nothing, and from the publishing side that looks exactly like a
// good publication. Only `:bochka-embedded` is a supported surface with a checked ABI.
apply(from = rootProject.file("publishing.gradle.kts"))

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
