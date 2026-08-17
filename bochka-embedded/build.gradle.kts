plugins {
    alias(libs.plugins.kotlin.jvm)
}

apply(from = rootProject.file("publishing.gradle.kts"))

dependencies {
    // The only module anybody outside can depend on, which is why it is also the only one whose
    // ABI is dumped and compared (root build file).
    api(project(":bochka-app"))

    testImplementation(kotlin("test"))
}
