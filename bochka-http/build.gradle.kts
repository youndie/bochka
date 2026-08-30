plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("ru.workinprogress.sborka.mutation")
}

// Published because `:bochka-embedded` names it: a POM whose dependencies were never
// pushed resolves to nothing, and from the publishing side that looks exactly like a
// good publication. Only `:bochka-embedded` is a supported surface with a checked ABI.

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
