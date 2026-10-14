import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Ассистент поддержки: RAG по документации продукта и данные клиента из CRM через MCP"

// Ktor 3.5 и MCP SDK 0.15 требуют API корутин 1.11; BOM Spring Boot задаёт 1.10.2.
extra["kotlin-coroutines.version"] = "1.11.0"

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
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    implementation("io.ktor:ktor-client-cio:3.5.1")
}

tasks.jar {
    enabled = false
}

tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
