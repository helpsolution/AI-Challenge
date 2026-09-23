import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Сервис новостей: по расписанию собирает RSS в SQLite и отдаёт агрегированную сводку"

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
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

/** Запуск из корня day18, чтобы файл БД лежал в day18/data, а не внутри модуля. */
tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
