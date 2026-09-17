// Run one sample under the agent and look at it:
//   ./gradlew -p samples run -Psample=StructuredConcurrency -Pcoroutree
//   ./gradlew -p samples coroutreeView

plugins {
    kotlin("jvm") version "2.4.20"
    application
    id("org.jetbrains.kotlinx.coroutree")
}

kotlin {
    jvmToolchain(26)
}

application {
    mainClass = providers.gradleProperty("sample").orElse("StructuredConcurrency").map { "samples.${it}Kt" }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in` // the Interactive sample waits for Enter
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Without a classifier the dependency is substituted by the :coroutree-gui project of the included build.
    coroutreeGui("org.jetbrains.kotlinx:coroutree-gui")
}
