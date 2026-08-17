plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":bochka-s3"))
    implementation(project(":bochka-http"))

    testImplementation(kotlin("test"))
}

// The `application` plugin, the distribution and the runtime profile baked into the start script
// arrive with M11 — there is no entry point yet, and the plugin fails the build without one.
