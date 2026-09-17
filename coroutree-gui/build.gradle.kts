import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    `maven-publish`
}

val mainClassName = "kotlinx.coroutree.gui.MainKt"

// Compose ships its native part (Skiko) in one artifact per platform.
fun desktopRuntime(target: String) = "org.jetbrains.compose.desktop:desktop-jvm-$target:${libs.versions.compose.get()}"

val hostTarget: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        "mac" in os -> "macos"
        "win" in os -> "windows"
        else -> "linux"
    }
    "$osPart-${if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"}"
}

kotlin {
    jvmToolchain(26)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

// The host's native Compose runtime lives in its own bucket: `run`, tests and the plain `runtimeElements` variant
// (which a composite build launches the GUI from) need it, the per-OS uber jars must not inherit it.
val hostRuntime = configurations.dependencyScope("hostRuntime")
configurations.runtimeOnly { extendsFrom(hostRuntime.get()) }

dependencies {
    implementation(project(":coroutree-model"))
    implementation(libs.compose.desktop)
    implementation(libs.compose.material3)
    implementation(libs.compose.splitpane)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    hostRuntime(desktopRuntime(hostTarget))

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

compose.desktop {
    application {
        mainClass = mainClassName
    }
}

tasks.test {
    useJUnitPlatform()
    // Offscreen renders of the demo trace; ScreenshotTest writes them, a human (or an agent) looks at them.
    val screenshots = layout.buildDirectory.dir("screenshots")
    outputs.dir(screenshots)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dcoroutree.screenshots=${screenshots.get().asFile.path}") })
    // -PscreenshotTrace=<file.ctrace> additionally renders a trace of one's own, see ScreenshotTest.recordedTrace.
    val recordedTrace = providers.gradleProperty("screenshotTrace")
    inputs.property("screenshotTrace", recordedTrace).optional(true)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOfNotNull(recordedTrace.orNull?.let { "-Dcoroutree.screenshots.trace=$it" }) })
    systemProperty("java.awt.headless", "true")
}

// One self-contained jar per desktop platform, published under a classifier. The Gradle plugin resolves the one
// matching the host and runs it; it also works on its own: java -jar coroutree-gui-<version>-<classifier>.jar.
val desktopTargets = listOf("macos-arm64", "macos-x64", "linux-x64", "linux-arm64", "windows-x64")

val uberJars = desktopTargets.map { target ->
    val suffix = target.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    val native = configurations.dependencyScope("uberNative$suffix")
    dependencies.add(native.name, desktopRuntime(target))
    val runtime = configurations.resolvable("uberRuntime$suffix") {
        extendsFrom(configurations.implementation.get(), native.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
    }

    tasks.register<ShadowJar>("uberJar$suffix") {
        group = "build"
        description = "Self-contained GUI jar for $target."
        archiveClassifier = target
        from(sourceSets.main.map { it.output })
        configurations = listOf(runtime.get())
        mergeServiceFiles()
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
        manifest {
            attributes("Main-Class" to mainClassName, "Implementation-Version" to project.version)
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("gui") {
            // Only the classified uber jars: no component, so no dependencies in the POM and no module metadata.
            uberJars.forEach { artifact(it) }
        }
    }
}
