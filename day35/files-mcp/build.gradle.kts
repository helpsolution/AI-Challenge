plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("advent.files.mcp.MainKt") }
dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("io.ktor:ktor-server-cio:3.5.1")
    implementation("io.ktor:ktor-server-sse:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
}
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
