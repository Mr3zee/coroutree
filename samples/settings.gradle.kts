// A standalone build that uses coroutree the way a user's build does, except that the plugin, the agent and the GUI
// come from the checkout around it instead of a repository. The build is included twice on purpose:
// once for its plugin, once for dependency substitution of the agent and the GUI.

pluginManagement {
    includeBuild("..")
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

includeBuild("..")

rootProject.name = "coroutree-samples"
