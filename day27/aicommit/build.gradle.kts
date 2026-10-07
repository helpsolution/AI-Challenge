plugins {
    kotlin("jvm")
    application
}

description = "CLI: сообщение коммита по staged-диффу от локальной LLM в Ollama"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":local-llm"))
}

application {
    mainClass = "advent.aicommit.MainKt"
    applicationName = "aicommit"
}
