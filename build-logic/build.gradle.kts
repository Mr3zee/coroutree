plugins {
    `kotlin-dsl`
}

dependencies {
    // What the precompiled scripts configure. The modules apply the other plugins themselves, from the catalog.
    implementation(plugin(libs.plugins.kotlin.jvm))
}

// The plugin marker artifact of a catalog entry: what `id("...") version "..."` resolves.
fun plugin(dependency: Provider<PluginDependency>): Provider<String> =
    dependency.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }
