import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Сервис конвейера: поиск статей Хабра, саммаризация через DeepSeek, хранение отчётов в SQLite"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        javaParameters = true
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

/** Имя без версии: так его знает systemd-юнит на сервере. */
tasks.bootJar {
    archiveFileName.set("habr-service.jar")
}

/** Обычный jar рядом с bootJar не нужен: на сервер едет только исполняемый. */
tasks.jar {
    enabled = false
}

/** Запуск из корня day19: там лежит .env с ключом DeepSeek, а база ложится в day19/data, а не внутрь модуля. */
tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
