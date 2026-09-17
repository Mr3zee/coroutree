import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
    `maven-publish`
}

/*
 * One self-contained jar, two class loaders:
 *  - `runtime` source set: what instrumented code calls into. It is appended to the bootstrap class path (instrumented
 *    JDK classes must see it), so it is plain Java with no dependencies at all. Packed as a jar nested in the agent jar.
 *  - `main` source set: premain and the ASM transformers, loaded by the system class loader, ASM relocated.
 */
val runtime: SourceSet by sourceSets.creating

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.add("-Xlint:all,-serial")
}

// Hooks run inside instrumented JDK methods. String concatenation through invokedynamic would bootstrap itself in there
// on first use, recursing into the very classes being observed; plain StringBuilder code has no such surprises.
tasks.named<JavaCompile>(runtime.compileJavaTaskName) {
    options.compilerArgs.add("-XDstringConcat=inline")
}

val shade: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    compileOnly(libs.asm)
    compileOnly(libs.asm.tree)
    compileOnly(runtime.output)
    shade(libs.asm)
    shade(libs.asm.tree)
}

val generateBuildInfo by tasks.registering {
    val version = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/buildInfo")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("kotlinx/coroutree/runtime/BuildInfo.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package kotlinx.coroutree.runtime;

            final class BuildInfo {
                static final String VERSION = "$version";

                private BuildInfo() {}
            }
            """.trimIndent() + "\n"
        )
    }
}
runtime.java.srcDir(generateBuildInfo)

val runtimeJar by tasks.registering(Jar::class) {
    from(runtime.output)
    archiveBaseName = "coroutree-runtime"
    archiveVersion = ""
    destinationDirectory = layout.buildDirectory.dir("runtime-jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    enabled = false
}

// The native monitor probe (src/native), one C file against jvmti.h. A build produces the binary of the platform it
// runs on — on macOS a universal one, for both architectures — and a release collects the others: build on each
// platform (CI matrix), gather the contents of every build/native in one directory and pass it as
// -Pcoroutree.prebuiltNatives=<dir>. An agent jar without the binary for some platform works there all the same,
// minus the MONITOR blocking reason. Platform directories and file names are a contract with MonitorProbeLoader and
// with the Gradle plugin.
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase().let { if (it == "amd64" || it == "x86_64") "x64" else if (it == "aarch64") "arm64" else it }
val nativePlatform: String? = when {
    "mac" in hostOs -> "macos"
    "linux" in hostOs && hostArch in setOf("x64", "arm64") -> "linux-$hostArch"
    else -> null // Windows: build with a C compiler of your choice, see the command line below, and pass it as prebuilt
}
val cCompiler: File? = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "cc") }.firstOrNull { it.canExecute() }

val compileMonitorProbe by tasks.registering(Exec::class) {
    val source = layout.projectDirectory.file("src/native/coroutree_monitor.c")
    val jdkHome = javaToolchains.compilerFor(java.toolchain).map { it.metadata.installationPath.asFile }
    val outputDir = layout.buildDirectory.dir("native")
    val platform = nativePlatform
    val compiler = cCompiler
    onlyIf("needs a supported host platform and a C compiler (cc) on the PATH") { platform != null && compiler != null }
    inputs.file(source)
    inputs.property("platform", platform ?: "none")
    outputs.dir(outputDir)
    if (platform != null && compiler != null) {
        val macos = platform == "macos"
        val library = outputDir.map { it.file(if (macos) "macos/libcoroutree-monitor.dylib" else "$platform/libcoroutree-monitor.so").asFile }
        doFirst { library.get().parentFile.mkdirs() }
        executable = compiler.path
        argumentProviders.add(CommandLineArgumentProvider {
            val include = File(jdkHome.get(), "include")
            val shared = if (macos) listOf("-dynamiclib", "-arch", "arm64", "-arch", "x86_64", "-mmacosx-version-min=11.0") else listOf("-shared", "-fPIC")
            listOf("-O2", "-Wall") + shared +
                listOf("-I${include.path}", "-I${File(include, if (macos) "darwin" else "linux").path}", "-o", library.get().path, source.asFile.path)
        })
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    configurations = listOf(shade)
    from(runtimeJar) {
        into("kotlinx/coroutree/agent")
    }
    from(compileMonitorProbe) {
        into("kotlinx/coroutree/agent/native")
    }
    providers.gradleProperty("coroutree.prebuiltNatives").orNull?.let { prebuilt ->
        from(prebuilt) {
            into("kotlinx/coroutree/agent/native")
        }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE // the binary built here wins over a prebuilt one for the same platform
    relocate("org.objectweb.asm", "kotlinx.coroutree.agent.shaded.asm")
    exclude("module-info.class", "META-INF/versions/**", "META-INF/LICENSE*", "META-INF/NOTICE*")
    manifest {
        attributes(
            "Premain-Class" to "kotlinx.coroutree.agent.Premain",
            "Can-Retransform-Classes" to "true",
            "Implementation-Title" to "coroutree-agent",
            "Implementation-Version" to project.version,
        )
    }
}

// The shaded jar is the module's one and only artifact, with no dependencies: that is what `-javaagent:` needs,
// and what a composite build gets when it substitutes `org.jetbrains.kotlinx:coroutree-agent` with this project.
listOf(configurations.apiElements, configurations.runtimeElements).forEach { elements ->
    elements {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.named("shadowJar"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.named("shadowJar"))
        }
    }
}
