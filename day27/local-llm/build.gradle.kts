plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

description = "Клиент Ollama и генерация сообщения коммита — общее для CLI aicommit и веб-мастерской"

kotlin {
    jvmToolchain(21)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
