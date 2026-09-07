plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.github.youndie.sborka.jvm")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
    id("io.github.youndie.sborka.mutation")
}

dependencies {
    api(project(":bochka-embedded"))
    // `compileOnly` rather than `api`: the extension is added to a test source set where JUnit is
    // already present, and a copy of its version there only gets in the way — somebody else's
    // project picks that version itself.
    compileOnly(libs.junit.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}
