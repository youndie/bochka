plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Published because `:bochka-embedded` names it: a POM whose dependencies were never
// pushed resolves to nothing, and from the publishing side that looks exactly like a
// good publication. Only `:bochka-embedded` is a supported surface with a checked ABI.
apply(from = rootProject.file("publishing.gradle.kts"))

dependencies {
    // `api`: the store hands out a suspending `put`, because the bytes come off a socket and a
    // blocking bridge would park a dispatcher thread for the length of an upload.
    api(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    // For the no-locks gate: the rule is checked by reading the bytecode this module compiles to.
    testImplementation(libs.asm)
}
