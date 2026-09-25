plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21) }
dependencies { implementation(project(":common")) }
application {
    mainClass.set("advent.day20.agent.MainKt")
    applicationDefaultJvmArgs = listOf("-Xms32m", "-Xmx192m")
}
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
