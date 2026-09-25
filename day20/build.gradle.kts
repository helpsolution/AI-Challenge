plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
}
subprojects {
    group = "advent.day20"
    version = "1.0.0"
    repositories { mavenCentral() }
}
