import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.getByType

/** The `conventions {}` block of a module: what `coroutree.kotlin-jvm` lets a module change. */
abstract class Conventions {
    /** The JVM the bytecode runs on: `--release` for javac, `jvmTarget` for kotlinc. 17 unless the module says otherwise. */
    abstract val jvmTarget: Property<Int>
}

/** `gradle/libs.versions.toml`; precompiled script plugins get no typed `libs` accessor. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
