import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("coroutree.kotlin-jvm")
    `java-gradle-plugin`
    id("coroutree.publishing")
}

kotlin {
    explicitApi()
    compilerOptions {
        // The plugin runs on the Kotlin stdlib embedded into Gradle, whatever this build compiles with.
        // 2.2 is what Gradle 9.0, the oldest supported version, ships.
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
        // Gradle can neither track the implementation of invokedynamic lambdas nor store them in the configuration cache.
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class")
    }
}

gradlePlugin {
    plugins {
        create("coroutree") {
            id = "org.jetbrains.kotlinx.coroutree"
            implementationClass = "kotlinx.coroutree.gradle.CoroutreePlugin"
            displayName = "coroutree"
            description = "Records and visualises the concurrency tree of Kotlin/JVM programs"
        }
    }
}

// What the plugin has to know about the build it came from: the version of the agent and GUI artifacts to resolve.
val generatePluginProperties = tasks.register<WriteProperties>("generatePluginProperties") {
    destinationFile = layout.buildDirectory.file("generated/pluginProperties/kotlinx/coroutree/gradle/plugin.properties")
    property("version", project.version.toString())
    property("group", project.group.toString())
}

sourceSets.main {
    resources.srcDir(generatePluginProperties.map { layout.buildDirectory.dir("generated/pluginProperties").get() })
}

// A stand-in for the real agent: functional tests must not depend on coroutree-agent being buildable.
val fakeAgent = sourceSets.create("fakeAgent")

val fakeAgentJar = tasks.register<Jar>("fakeAgentJar") {
    archiveBaseName = "fake-agent"
    destinationDirectory = layout.buildDirectory.dir("fakeAgent")
    from(fakeAgent.output)
    manifest {
        attributes("Premain-Class" to "kotlinx.coroutree.gradle.fakeagent.FakeAgent")
    }
}

val functionalTest = sourceSets.create("functionalTest")
gradlePlugin.testSourceSets(functionalTest)

// TestKit injects the plugin under test into a class loader that cannot see plugins resolved by the test build,
// so the Kotlin Gradle plugin the tests apply has to come in the same way.
val functionalTestPluginClasspath = configurations.create("functionalTestPluginClasspath") {
    isCanBeConsumed = false
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalTestPluginClasspath)
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)

    testImplementation(gradleApi())

    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"(kotlin("test-junit5")) // kotlin("test") picks the framework only for the standard test task
    "functionalTestImplementation"(platform(libs.junit.bom))
    "functionalTestImplementation"(libs.junit.jupiter)
    "functionalTestRuntimeOnly"(libs.junit.platform.launcher)

    functionalTestPluginClasspath(libs.kotlin.gradle.plugin)
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the plugin against real builds with Gradle TestKit."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    val fakeAgentFile = fakeAgentJar.flatMap { it.archiveFile }
    inputs.file(fakeAgentFile).withPropertyName("fakeAgentJar").withNormalizer(ClasspathNormalizer::class)
    val junitVersion = libs.versions.junit.get()
    inputs.property("junitVersion", junitVersion)
    doFirst {
        systemProperty("coroutree.test.fakeAgent", fakeAgentFile.get().asFile.absolutePath)
        systemProperty("coroutree.test.junitVersion", junitVersion)
    }
}

tasks.check {
    dependsOn(functionalTestTask)
}
