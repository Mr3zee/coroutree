pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
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
