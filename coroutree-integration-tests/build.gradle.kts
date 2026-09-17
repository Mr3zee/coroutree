plugins {
    alias(libs.plugins.kotlin.jvm)
}

/*
 * End-to-end tests of the agent: every program of the sample corpus is run in a forked JVM with the real agent jar,
 * and the trace it leaves behind is folded with coroutree-model and compared with a golden tree.
 *
 * The corpus lives in the standalone `samples` build (which applies the Gradle plugin, something a project of this
 * build cannot do with a plugin this build produces). Its sources are compiled here a second time, as this module's
 * main source set, so that there is one corpus and the tests need nothing published.
 */

kotlin {
    jvmToolchain(26)
}

sourceSets {
    main {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("samples/src/main/kotlin"))
        java.srcDir(rootProject.layout.projectDirectory.dir("samples/src/main/java"))
    }
}

// A "kotlinx.coroutines" whose internals do not match the agent's hook table, to see the agent say so.
val fakeCoroutines: SourceSet by sourceSets.creating

val agentJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

// The corpus again, compiled against and run with the other kotlinx.coroutines versions the agent claims to support
// (HookTable.TESTED_COROUTINES). Compiled, not just run: builders are inline-thin wrappers whose targets change names.
val otherCoroutinesVersions = listOf("1.10.2", "1.9.0")
val samplesWithOtherCoroutines: Map<String, SourceSet> = otherCoroutinesVersions.associateWith { version ->
    sourceSets.create("samplesWithCoroutines" + version.replace(".", "")) {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("samples/src/main/kotlin"))
        java.srcDir(rootProject.layout.projectDirectory.dir("samples/src/main/java"))
    }
}

dependencies {
    implementation(libs.coroutines.core)
    agentJar(project(":coroutree-agent"))
    samplesWithOtherCoroutines.forEach { (version, sourceSet) ->
        add(sourceSet.implementationConfigurationName, "org.jetbrains.kotlinx:kotlinx-coroutines-core:$version")
    }

    testImplementation(project(":coroutree-model"))
    testImplementation(project(":coroutree-gui")) // its trace sources, not its UI: GuiFollowsLiveJvmTest
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    val java = javaToolchains.launcherFor(java.toolchain).map { it.executablePath.asFile.absolutePath }
    val agent: FileCollection = agentJar
    val samplesClasspath: FileCollection = sourceSets.main.get().runtimeClasspath
    val otherClasspaths: Map<String, FileCollection> = samplesWithOtherCoroutines.mapValues { it.value.runtimeClasspath }
    val fakeCoroutinesClasses: FileCollection = fakeCoroutines.output
    val goldenDir = rootProject.layout.projectDirectory.dir("samples/golden/dynamic").asFile
    val workDir = layout.buildDirectory.dir("agent-runs").map { it.asFile.absolutePath }
    val updateGoldens = providers.gradleProperty("updateGoldens").isPresent

    inputs.files(agent).withPropertyName("agentJar").withNormalizer(ClasspathNormalizer::class)
    inputs.files(samplesClasspath).withPropertyName("samplesClasspath").withNormalizer(ClasspathNormalizer::class)
    otherClasspaths.forEach { (version, files) ->
        inputs.files(files).withPropertyName("coroutines-$version").withNormalizer(ClasspathNormalizer::class)
    }
    inputs.files(fakeCoroutinesClasses).withPropertyName("fakeCoroutines").withNormalizer(ClasspathNormalizer::class)
    inputs.dir(goldenDir).withPropertyName("goldens").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("updateGoldens", updateGoldens)
    // Rewriting goldens is the point of such a run, not a cacheable result.
    outputs.upToDateWhen { !updateGoldens }

    jvmArgumentProviders.add(CommandLineArgumentProvider {
        buildList {
            add("-Dcoroutree.test.java=${java.get()}")
            add("-Dcoroutree.test.agentJar=${agent.singleFile.absolutePath}")
            add("-Dcoroutree.test.samplesClasspath=${samplesClasspath.asPath}")
            otherClasspaths.forEach { (version, files) -> add("-Dcoroutree.test.coroutines.$version=${files.asPath}") }
            add("-Dcoroutree.test.fakeCoroutinesClasses=${fakeCoroutinesClasses.asPath}")
            add("-Dcoroutree.test.goldenDir=${goldenDir.absolutePath}")
            add("-Dcoroutree.test.workDir=${workDir.get()}")
            add("-Dcoroutree.test.updateGoldens=$updateGoldens")
        }
    })
}
