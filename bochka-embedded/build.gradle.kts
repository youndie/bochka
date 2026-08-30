plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("ru.workinprogress.sborka.mutation")
}

dependencies {
    // The supported surface, and the only module whose ABI is dumped and compared (root build
    // file). The others are published too — a POM that names a dependency nobody pushed resolves
    // to nothing, and that failure looks identical to a good publish from the publishing side —
    // but this is the one to depend on.
    api(project(":bochka-app"))
    api(project(":bochka-core"))

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    // Somebody else's S3 client, in the tests of the module somebody else's tests would use
    // (M-230). The live-client harness is four containers looking at the server from outside the
    // process, and none of them can be the client of the embedded mode: on the JVM that client is
    // a library in the same JVM. So the oracle for this module belongs in `check`, not in a script
    // that needs docker.
    testImplementation(libs.minio)
}
