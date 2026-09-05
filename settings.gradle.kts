// Lets Gradle fetch the JDK 25 toolchain itself. Without it the project only builds where somebody
// has already installed 25 by hand, and the failure on a fresh machine is a toolchain error that
// says nothing about what to install.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // The repositories with their content filters, the shared `wip` catalog, and the check that this
    // repository's `.editorconfig` is the one the rest of them use.
    id("ru.workinprogress.sborka.settings") version "0.2.0.30"
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
// A module of its own, so that `bochka-embedded` does not drag JUnit along for those who do not
// use it.
include(":bochka-junit")
include(":bochka-testcontainers")
include(":bochka-benchmark")
// Fuzz targets for the parsers that read unauthenticated bytes (M38). Separate because the fuzzer
// needs a heap the footprint gate does not allow anywhere else.
include(":bochka-fuzz")
