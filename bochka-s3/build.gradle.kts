plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.github.youndie.sborka.jvm")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
    id("io.github.youndie.sborka.mutation")
}

// Published because `:bochka-embedded` names it: a POM whose dependencies were never
// pushed resolves to nothing, and from the publishing side that looks exactly like a
// good publication. Only `:bochka-embedded` is a supported surface with a checked ABI.

dependencies {
    // The protocol layer names object keys, and a key is a storage type (Р3). It knows nothing
    // about sockets: everything here has to be testable on a recorded byte stream, because the
    // chunked upload path (§1.1) is otherwise only reachable through the network.
    api(project(":bochka-core"))

    testImplementation(kotlin("test"))
}
