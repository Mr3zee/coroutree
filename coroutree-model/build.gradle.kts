plugins {
    id("coroutree.kotlin-jvm")
    alias(libs.plugins.kotlin.serialization)
    id("coroutree.publishing")
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

java {
    withSourcesJar()
}

dependencies {
    api(libs.serialization.protobuf)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
