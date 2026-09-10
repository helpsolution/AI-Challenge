plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "advent"
version = "0.0.1-SNAPSHOT"
description = "AI Advent Challenge — день 8: работа с токенами"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        javaParameters = true
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // JdbcClient и управление транзакциями. JPA не берём: на одну таблицу это лишний слой.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Версию задаём явно: BOM Spring Boot драйвер SQLite не ведёт.
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
}
