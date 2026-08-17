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
    // The runtime profile the tests and benchmarks run under, so what ships is the process that was
    // measured. The distribution itself, its configuration and the image are M11 — what this line
    // does today is let a live client be pointed at a real socket.
    @Suppress("UNCHECKED_CAST")
    applicationDefaultJvmArgs = rootProject.extra["bochkaJvmArgs"] as List<String>
}
