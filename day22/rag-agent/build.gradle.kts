import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "RAG-агент: вопрос → поиск чанков → промпт с контекстом → DeepSeek, и тот же вопрос без RAG"

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
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

/** Обычный jar рядом с bootJar не нужен: запускается только исполняемый. */
tasks.jar {
    enabled = false
}

/** Запуск из корня day22: там лежат .env, корпус и база знаний. */
tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
