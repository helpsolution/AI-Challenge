import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "RAG-сервис недели 21+: пока только эмбеддинги текста через локальную Ollama"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        javaParameters = true
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

/** Обычный jar рядом с bootJar не нужен: запускается только исполняемый. */
tasks.jar {
    enabled = false
}

/** Запуск из корня day21: сюда же потом лягут данные индекса. */
tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
