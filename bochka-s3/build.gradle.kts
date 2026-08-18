plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Published because `:bochka-embedded` names it: a POM whose dependencies were never
// pushed resolves to nothing, and from the publishing side that looks exactly like a
// good publication. Only `:bochka-embedded` is a supported surface with a checked ABI.
apply(from = rootProject.file("publishing.gradle.kts"))

dependencies {
    // The protocol layer names object keys, and a key is a storage type (Р3). It knows nothing
    // about sockets: everything here has to be testable on a recorded byte stream, because the
    // chunked upload path (§1.1) is otherwise only reachable through the network.
    api(project(":bochka-core"))

    testImplementation(kotlin("test"))
}
