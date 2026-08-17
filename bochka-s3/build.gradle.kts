plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // The protocol layer names object keys, and a key is a storage type (Р3). It knows nothing
    // about sockets: everything here has to be testable on a recorded byte stream, because the
    // chunked upload path (§1.1) is otherwise only reachable through the network.
    api(project(":bochka-core"))

    testImplementation(kotlin("test"))
}
