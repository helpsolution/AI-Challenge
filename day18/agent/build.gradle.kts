plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

description = "Новостной агент: чат с LLM поверх MCP-сервера новостей и сводка по расписанию"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("advent.news.agent.MainKt")
}

dependencies {
    // клиент MCP: тот же транспорт Streamable HTTP, что и у сервера
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")

    // клиент LLM: DeepSeek говорит на OpenAI-совместимом API
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-cio:3.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
}

/** Агент диалоговый: без этого Gradle не пробрасывает ввод с клавиатуры в процесс. */
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}
