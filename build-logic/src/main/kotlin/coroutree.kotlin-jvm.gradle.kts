import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A Kotlin/JVM module of coroutree: builds on JDK 26, targets the JVM of `conventions.jvmTarget`, tests on JUnit 5.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val conventions = extensions.create<Conventions>("conventions")
conventions.jvmTarget.convention(17)

kotlin {
    jvmToolchain(26)
    compilerOptions {
        jvmTarget = conventions.jvmTarget.map { JvmTarget.fromTarget(it.toString()) }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = conventions.jvmTarget
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
