plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Transport only. It does not depend on `:bochka-s3` on purpose — the S3 half is wired on top
    // in `:bochka-app`, so neither layer can quietly start reaching into the other.
    api(project(":bochka-core"))

    testImplementation(kotlin("test"))
}
