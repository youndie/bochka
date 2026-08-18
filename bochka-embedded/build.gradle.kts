plugins {
    alias(libs.plugins.kotlin.jvm)
}

apply(from = rootProject.file("publishing.gradle.kts"))

dependencies {
    // The supported surface, and the only module whose ABI is dumped and compared (root build
    // file). The others are published too — a POM that names a dependency nobody pushed resolves
    // to nothing, and that failure looks identical to a good publish from the publishing side —
    // but this is the one to depend on.
    api(project(":bochka-app"))
    api(project(":bochka-core"))

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
