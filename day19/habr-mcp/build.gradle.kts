plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

description = "MCP-сервер: четыре инструмента конвейера поверх REST API habr-service"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("advent.pipeline.mcp.MainKt")
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")

    // сервер: Streamable HTTP из MCP SDK построен на Ktor, SSE обязателен даже в stateless-режиме
    implementation("io.ktor:ktor-server-core:3.5.1")
    implementation("io.ktor:ktor-server-cio:3.5.1")
    implementation("io.ktor:ktor-server-sse:3.5.1")

    // клиент: ходим в habr-service, не блокируя поток корутины
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-cio:3.5.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
}

/** Толстый jar без версии в имени: на сервер едет один файл, и его знает systemd-юнит. */
tasks.jar {
    archiveFileName.set("habr-mcp.jar")
    manifest {
        attributes["Main-Class"] = "advent.pipeline.mcp.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
