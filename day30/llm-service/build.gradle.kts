import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Приватный AI-сервис: локальная LLM в Ollama за OpenAI-совместимым API с ключами, лимитами и очередью"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        javaParameters = true
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

tasks.jar {
    enabled = false
}

tasks.named<BootJar>("bootJar") {
    archiveFileName = "llm-service.jar"
}

tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
