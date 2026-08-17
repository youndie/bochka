import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

/*
 * Publishing to a Maven repository (research, Р9).
 *
 * The address, the login and the password come from the Gradle properties `BOCHKA_REPO_URL` /
 * `BOCHKA_REPO_USER` / `BOCHKA_REPO_SECRET` or from environment variables of the same names.
 * None of it lives in the repository: everybody who builds bochka has their own destination, and
 * without configuration `publishToMavenLocal` is what remains.
 *
 * Applied to `:bochka-embedded` only. The server itself is delivered as an image; the embeddable
 * one is the artefact somebody compiles against, and it is also the only module whose ABI is
 * dumped (root build file).
 *
 * A `plugins { }` block is unavailable here — this is an applied script, not a build file — so the
 * plugin is added with `apply` and the extension configured through `configure`.
 */
apply(plugin = "maven-publish")

configure<PublishingExtension> {
    repositories {
        maven {
            name = "bochkaRepo"
            url =
                uri(
                    providers.gradleProperty("BOCHKA_REPO_URL").orNull
                        ?: System.getenv("BOCHKA_REPO_URL")
                        ?: "https://reposilite.kotlin.website/snapshots",
                )
            credentials {
                username =
                    providers.gradleProperty("BOCHKA_REPO_USER").orNull
                        ?: System.getenv("BOCHKA_REPO_USER")
                password =
                    providers.gradleProperty("BOCHKA_REPO_SECRET").orNull
                        ?: System.getenv("BOCHKA_REPO_SECRET")
            }
        }
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                // In English, like everything a consumer sees: a repository browser shows this.
                description.set(
                    "An S3-compatible object store you can start from a test: one process, " +
                        "one disk, no cluster",
                )
                url.set("https://github.com/youndie/bochka")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("youndie")
                        url.set("https://github.com/youndie")
                    }
                }
                scm {
                    url.set("https://github.com/youndie/bochka")
                    connection.set("scm:git:https://github.com/youndie/bochka.git")
                    developerConnection.set("scm:git:ssh://git@github.com/youndie/bochka.git")
                }
            }
        }
    }
}
