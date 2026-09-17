package kotlinx.coroutree.gradle

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer

/**
 * The only place that touches Kotlin Gradle plugin types. It is compiled against them but must not be loaded
 * unless a Kotlin plugin is applied, so nothing else may refer to those types, not even in a signature.
 */
internal object KotlinSourceSets {
    fun forEachJvmSourceSet(project: Project, action: (name: String, directories: FileCollection) -> Unit) {
        val kotlin = project.extensions.getByName("kotlin")
        if (kotlin is KotlinTargetsContainer) {
            // Multiplatform: only what is compiled for a JVM target, which includes the common source sets.
            kotlin.targets.all { target ->
                if (target.platformType != KotlinPlatformType.jvm) return@all
                target.compilations.all { compilation ->
                    // The set keeps growing while the build script adds dependsOn edges, so it is read late.
                    val directories = project.provider { compilation.allKotlinSourceSets.map { it.kotlin.sourceDirectories } }
                    action(compilation.name, project.files(directories))
                }
            }
        } else if (kotlin is KotlinSourceSetContainer) {
            kotlin.sourceSets.all { action(it.name, it.kotlin.sourceDirectories) }
        }
    }
}
