# Don't hardcode Task names unless they are documented as Public API
Hardcoding task names can make a build fragile when upgrading Gradle or third-party plugins.  

## Explanation
Some task names in Gradle are part of the [Gradle public API (Use `gradle_docs(path="userguide/public_apis.md")`.) and are stable. Most other task names in Gradle and third-party plugins are internal implementation details. They may be renamed or removed in future versions and should not be relied upon. Task types are also not guaranteed to remain stable; plugin authors may refactor them.  
In practice, configuring tasks by type is usually more robust than depending on specific names, especially in ecosystems like Android or Kotlin where many tasks are generated dynamically.  
Prefer, in order:  
1. **Plugin DSL** - Configure tasks via extension blocks provided by a plugin that are automatically wired to the tasks that plugin creates. This is the most future-proof and stable option.

2. **Task types** - More robust than names in many cases, but can still change across plugin versions, depending on the plugin's backwards compatibility policies.

3. **Task names** - Use only when they are explicitly documented as [public API (Use `gradle_docs(path="userguide/public_apis.md")`.).

As a plugin author, provide a DSL extension for users to configure behavior declaratively. Internally wire the configuration into your tasks. Avoid exposing task names as part of your public API unless you are prepared to maintain them with deprecation cycles.  

## Example
### Don't Do This
build.gradle.kts  

```kotlin
plugins {
    id("java-library")
    id("maven-publish")
}

tasks.named<JavaCompile>("compileJava").configure { (1)
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication").configure { (2)
    pom.url = "sample.gradle.org"
}
```

build.gradle  

```groovy
plugins {
    id("java-library")
    id("maven-publish")
}

tasks.named("compileJava") { (1)
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

publishing {
    publications {
        maven(MavenPublication) {
            from(components.java)
        }
    }
}

tasks.named("generatePomFileForMavenPublication") { (2)
    pom.url = "sample.gradle.org"
}
```

|-------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | Looking up `compileJava` by name: This relies on the hardcoded `"compileJava"` string.                                                                                                             |
| **2** | Using `tasks.named` to get the `generatePomFileForMavenPublication` task: This unnecessarily relies on this task's name, even though it's the only `GenerateMavenPom` task that needs configuring. |

### Do This Instead
A better option for the `compileJava` configuration is to use a public constant --- this task name is part of [public API (Use `gradle_docs(path="userguide/public_apis.md")`.), so referencing it via the constant is safe:  
build.gradle.kts  

```kotlin
tasks.named<JavaCompile>(JavaPlugin.COMPILE_JAVA_TASK_NAME) { (1)
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
```

build.gradle  

```groovy
tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME) { (1)
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
```

|-------|---------------------------------------------------------------------------------------------------------------------|
| **1** | Using `tasks.named` with `JavaPlugin.COMPILE_JAVA_TASK_NAME`: Replaces the hardcoded string with a public constant. |

The best option is to use the plugin DSL where possible --- this avoids referring to tasks by name or type at all:  
build.gradle.kts  

```kotlin
java { (1)
    setSourceCompatibility(JavaVersion.VERSION_17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom.url = "sample.gradle.org" (2)
        }
    }
}
```

build.gradle  

```groovy
java { (1)
    setSourceCompatibility(JavaVersion.VERSION_17)
}

publishing {
    publications {
        maven(MavenPublication) {
            from(components.java)
            pom.url = "sample.gradle.org" (2)
        }
    }
}
```

|-------|---------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | Using Java DSL: Entirely avoids implementation details of `java-library` plugin and sets `sourceCompatibility` for all `JavaCompile` tasks. |
| **2** | Using publishing DSL: Avoids hardcoding task names and ensures that the generated POM has the expected URL.                                 |

---

For the most up-to-date guidance, use `gradle_docs` with `tag:best-practices`.
