plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("ru.workinprogress.sborka.mutation")
}

dependencies {
    // `compileOnly`, for the reason `bochka-junit` gives about JUnit: this container goes into a
    // test source set where Testcontainers is already present, and a version pinned here would
    // only argue with the one the consumer chose.
    compileOnly(libs.testcontainers)

    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers)
    // A real client rather than a hand-rolled request, for the reason M31 gives: a server checked
    // by its author's own client is checked by the half of the protocol that author understood.
    testImplementation(libs.minio)
}

/**
 * The container test, which is not part of `check` and says why out loud.
 *
 * It needs a Docker daemon and an image, and neither belongs in a gate that a laptop runs on a
 * plane. More to the point, the image it should test is **this branch's**, not the published one:
 * the CI job that builds `bochka:smoke` is the only place where that image exists, so that is the
 * job which runs this.
 *
 *   ./gradlew :bochka-testcontainers:containerTest -Pbochka.image=bochka:smoke
 *
 * No skip anywhere: without the property the task refuses rather than passing quietly.
 */
val containerTest =
    tasks.register<Test>("containerTest") {
        group = "verification"
        description = "Starts the published image through Testcontainers and talks to it"
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        val image = providers.gradleProperty("bochka.image").orElse(providers.environmentVariable("BOCHKA_IMAGE"))
        doFirst {
            check(image.isPresent && image.get().isNotBlank()) {
                "name the image to test: -Pbochka.image=bochka:smoke, or BOCHKA_IMAGE in the environment"
            }
            // Resolved here rather than at configuration time: handing `environment` a provider
            // hands it the provider's `toString`, and the container then asks Docker for an image
            // called `or(or(provider(?), valueof(...)), fixed())`.
            environment("BOCHKA_IMAGE", image.get())
        }
    }

// Excluded from the ordinary test task rather than skipped inside it: a test that returns on its
// first line looks exactly like a test that passed.
tasks.test {
    exclude("**/BochkaContainerTest.class")
}

tasks.check {
    // Deliberately **not** `dependsOn(containerTest)`: the image does not exist at gate time.
    // `.github/workflows/check.yml` runs it in the job that builds one.
}
