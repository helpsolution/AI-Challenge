plugins {
    // Позволяет Gradle самому скачать нужный JDK, если его нет на машине (важно для VPS).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "day5"
