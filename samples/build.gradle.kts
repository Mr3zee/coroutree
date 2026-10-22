// Run one sample under the agent and look at it:
//   ./gradlew -p samples run -Psample=StructuredConcurrency -Pcoroutree
//   ./gradlew -p samples coroutreeView

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(libs.coroutines.core)

    // Without a classifier the dependency is substituted by the :coroutree-gui project of the included build.
    coroutreeGui("org.jetbrains.kotlinx:coroutree-gui")
}
