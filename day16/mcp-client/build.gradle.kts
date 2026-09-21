plugins {
    kotlin("jvm")
    application
}

description = "AI Advent Challenge - день 16: минимальный MCP-клиент"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("advent.day16.mcp.client.MainKt")
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
}

/** Путь к jar сервера по умолчанию задан относительно корня day16 — оттуда же удобно запускать клиент. */
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
