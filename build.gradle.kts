plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.shadow) apply false
}

// Every published module also goes to a build-local Maven repository, build/repo: the way to try the plugin, the agent
// and the GUI from another build exactly as a user would get them (publishAllPublicationsToLocalRepository).
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "local"
                    url = uri(rootProject.layout.buildDirectory.dir("repo"))
                }
            }
        }
    }
}
