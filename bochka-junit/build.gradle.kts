plugins {
    alias(libs.plugins.kotlin.jvm)
}

apply(from = rootProject.file("publishing.gradle.kts"))

dependencies {
    api(project(":bochka-embedded"))
    // `compileOnly`, а не `api`: расширение подключают в тестовый набор, где JUnit уже есть,
    // и своя копия его версии там только мешает — чужой проект выбирает её сам.
    compileOnly(libs.junit.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}
