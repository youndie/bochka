plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Transport only. It does not depend on `:bochka-s3` on purpose — the S3 half is wired on top
    // in `:bochka-app`, so neither layer can quietly start reaching into the other.
    api(project(":bochka-core"))

    // `api`, not `implementation`: the session loop hands out suspending functions and takes a
    // CoroutineScope, so coroutines are part of the surface rather than a detail behind it. The
    // neighbouring project shipped a release that would not compile for consumers by getting this
    // exact line wrong.
    api(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
