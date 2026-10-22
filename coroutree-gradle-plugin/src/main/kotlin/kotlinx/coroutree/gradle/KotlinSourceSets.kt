package kotlinx.coroutree.gradle

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer

/**
 * Load only after a Kotlin plugin is applied. Other classes must not reference Kotlin Gradle plugin types, even in signatures.
 */
internal object KotlinSourceSets {
    fun forEachJvmSourceSet(project: Project, action: (name: String, directories: FileCollection) -> Unit) {
        val kotlin = project.extensions.getByName("kotlin")
        if (kotlin is KotlinTargetsContainer) {
            // Multiplatform: only what is compiled for a JVM target, which includes the common source sets.
            kotlin.targets.all { target ->
                if (target.platformType != KotlinPlatformType.jvm) return@all
                target.compilations.all { compilation ->
                    // Read after configuration because dependsOn edges can add source sets.
                    val directories = project.provider { compilation.allKotlinSourceSets.map { it.kotlin.sourceDirectories } }
                    action(compilation.name, project.files(directories))
                }
            }
        } else if (kotlin is KotlinSourceSetContainer) {
            kotlin.sourceSets.all { action(it.name, it.kotlin.sourceDirectories) }
        }
    }
}
