import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Ассистент сопровождения проекта: файловые инструменты через MCP"

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
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    implementation("io.ktor:ktor-client-cio:3.5.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.jar {
    enabled = false
}

tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
