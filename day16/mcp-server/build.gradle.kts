plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

description = "AI Advent Challenge - день 16: MCP-сервер поверх сервиса погоды"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("advent.day16.mcp.server.MainKt")
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
}

/** Толстый jar: его путь можно без затей прописать в конфиг Claude Desktop или Claude Code. */
tasks.jar {
    manifest {
        attributes["Main-Class"] = "advent.day16.mcp.server.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
