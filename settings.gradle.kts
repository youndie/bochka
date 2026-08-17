// Lets Gradle fetch the JDK 25 toolchain itself. Without it the project only builds where somebody
// has already installed 25 by hand, and the failure on a fresh machine is a toolchain error that
// says nothing about what to install.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "bochka"

// The order here is the dependency order, and it is the whole of decision Р8: every module depends
// only on modules above it. Gradle rejects a cycle by itself, so "dependencies go inward" is
// checked by the build rather than by review — which is the only reason it will still be true in
// six months.
include(":bochka-core")
include(":bochka-s3")
include(":bochka-http")
include(":bochka-app")
include(":bochka-embedded")
include(":bochka-benchmark")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
