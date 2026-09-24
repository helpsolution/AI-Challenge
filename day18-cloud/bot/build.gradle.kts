plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

description = "Telegram-бот: команды и сводка статей Хабра по таймеру, через MCP-сервер и DeepSeek"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("advent.habr.bot.MainKt")
}

dependencies {
    // клиент MCP: тот же транспорт Streamable HTTP, что и у сервера
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")

    // HTTP к Telegram Bot API и к DeepSeek — без SDK: нужны три метода Telegram и один метод LLM
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-cio:3.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
}

/** Толстый jar без версии в имени: на сервер едет один файл, и его знает systemd-юнит. */
tasks.jar {
    archiveFileName.set("bot.jar")
    manifest {
        attributes["Main-Class"] = "advent.habr.bot.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

/** Запуск из корня day18-cloud: там лежат .env и каталог data. */
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
