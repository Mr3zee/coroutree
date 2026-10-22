// A published module. Besides any repository of its own it goes to build/repo, a build-local Maven repository: the way
// to try the plugin, the agent and the GUI from another build exactly as a user would get them
// (publishAllPublicationsToLocalRepository).

plugins {
    `maven-publish`
}

publishing {
    repositories {
        maven {
            name = "local"
            url = uri(layout.settingsDirectory.dir("build/repo"))
        }
    }
}
