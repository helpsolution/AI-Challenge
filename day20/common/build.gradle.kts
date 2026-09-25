plugins { kotlin("jvm"); kotlin("plugin.serialization"); `java-library` }
kotlin { jvmToolchain(21) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("io.ktor:ktor-server-core:3.5.1")
    api("io.ktor:ktor-server-cio:3.5.1")
    api("io.ktor:ktor-server-sse:3.5.1")
    api("io.ktor:ktor-client-core:3.5.1")
    api("io.ktor:ktor-client-cio:3.5.1")
    api("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    api("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    api("org.webjars:swagger-ui:5.32.14")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.19")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
