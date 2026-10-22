pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Downloads the JetBrains Runtime that Compose Hot Reload runs the GUI on (:coroutree-gui:hotRun).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "coroutree"

include(
    ":coroutree-model",
    ":coroutree-agent",
    ":coroutree-gradle-plugin",
    ":coroutree-gui",
    ":coroutree-integration-tests",
)
