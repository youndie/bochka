plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("ru.workinprogress.sborka.mutation")
}

/**
 * The container test lives in its own source set, and the first version did not — it sat in `test`
 * and was excluded by class name. That is the shape this repository refuses on purpose: the class
 * still declared a test, JUnit still saw it, and nothing ran it. A separate source set is the
 * honest version of the same intent — those tests are not part of the gate because they need a
 * Docker daemon and an image, not because somebody filtered them out.
 */
val containerTest by sourceSets.creating

dependencies {
    // `compileOnly`, for the reason `bochka-junit` gives about JUnit: this container goes into a
    // test source set where Testcontainers is already present, and a version pinned here would
    // only argue with the one the consumer chose.
    compileOnly(libs.testcontainers)

    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers)

    "containerTestImplementation"(project)
    // `test-junit5` by name rather than plain `test`: the variant of `kotlin-test` is chosen from
    // the framework of the source set's own test task, and a source set the Kotlin plugin did not
    // create has none to look at — so `kotlin.test.Test` resolves to nothing at all.
    "containerTestImplementation"(kotlin("test-junit5"))
    "containerTestRuntimeOnly"(libs.junit.engine)
    "containerTestImplementation"(libs.testcontainers)
    // A real client rather than a hand-rolled request, for the reason M31 gives: a server checked
    // by its author's own client is checked by the half of the protocol that author understood.
    "containerTestImplementation"(libs.minio)
}

// Starts the shipped image and talks to it:
//
//   ./gradlew :bochka-testcontainers:containerTest -Pbochka.image=bochka:smoke
//
// Not part of `check`, and the image is named rather than defaulted: the one worth testing is the
// one this branch would produce, which exists only in the CI job that builds it. Without a name the
// task refuses instead of passing quietly.
tasks.register<Test>("containerTest") {
    group = "verification"
    description = "Starts the shipped image through Testcontainers and talks to it"
    testClassesDirs = containerTest.output.classesDirs
    classpath = containerTest.runtimeClasspath
    useJUnitPlatform()
    val image = providers.gradleProperty("bochka.image").orElse(providers.environmentVariable("BOCHKA_IMAGE"))
    doFirst {
        check(image.isPresent && image.get().isNotBlank()) {
            "name the image to test: -Pbochka.image=bochka:smoke, or BOCHKA_IMAGE in the environment"
        }
        // Resolved here rather than at configuration time: handing `environment` a provider hands
        // it the provider's `toString`, and Docker is then asked for an image called
        // `or(or(provider(?), valueof(...)), fixed())`.
        environment("BOCHKA_IMAGE", image.get())
    }
}
