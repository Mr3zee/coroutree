# Always Declare Attributes on Consumable and Resolvable Configurations
When creating custom configurations that are meant to be consumed or resolved, always declare at least one [attribute (Use `gradle_docs(path="userguide/variant_attributes.md")`.) on them.  

## Explanation
Gradle uses [variant-aware dependency resolution (Use `gradle_docs(path="userguide/variant_aware_resolution.md")`.) to select the appropriate variants of a project. Attributes serve as the primary matching mechanism between resolvable configurations (what a consumer needs) and consumable configurations (what a producer provides as variants).  
Even if a producer project has only one consumable configuration, omitting attributes will cause variant-aware resolution to fail with the following error:  

```text
Unable to find a matching variant of project :producer:
  - No variants exist.
```

While Gradle can resolve configurations by selecting them directly by name, this approach is outdated:  
* It was [deprecated for Maven repositories (Use `gradle_docs(path="userguide/upgrading_version_8.md")`.) in Gradle 8.10 and removed in Gradle 9.0.0. It is only necessary for use with Ivy repositories. This mechanism can still be used for local project dependencies, but should be avoided.

* Configuration names should be treated as internal implementation details. Relying on them for resolution forces the consumer to know too much about the producer's internal structure.

When adding attributes to a configuration, common attributes to use include:  
* `Category.CATEGORY_ATTRIBUTE` - Indicates the category of the component (e.g., `LIBRARY`, `DOCUMENTATION`)

* For JVM Projects:

  * `Usage.USAGE_ATTRIBUTE` - Indicates which classpath this variant represents (e.g., `JAVA_RUNTIME`, `JAVA_API`)

  * `LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE` - Indicates the packaging of the classes (e.g., `JAR`, `CLASSES`)

While attributes are not yet strictly required for all resolvable configurations, omitting them leads to fragile builds. Declaring sensible attributes ensures your build remains reliable as it scales and evolves.  

## Example
### Don't Do This
Avoid creating consumable and resolvable configurations without attributes:  
In `producer/build.gradle(.kts)`:  
producer/build.gradle.kts  

```kotlin
configurations.consumable("customElements") (1)

val generateFile = tasks.register("generateFile") {
    val outputFile = layout.buildDirectory.file("custom/output.txt")
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.writeText("Custom output from producer")
    }
}

artifacts {
    add("customElements", generateFile)
}
```

producer/build.gradle  

```groovy
configurations.consumable('customElements') (1)

def generateFile = tasks.register('generateFile') {
    def outputFile = layout.buildDirectory.file('custom/output.txt')
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.text = 'Custom output from producer'
    }
}

artifacts {
    customElements generateFile
}
```

|-------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **No attributes declared on the configuration in the producer build**: This consumable configuration lacks attributes to describe what it provides. |

In `consumer/build.gradle(.kts)`:  
consumer/build.gradle.kts  

```kotlin
val customElementsDependencies = configurations.dependencyScope("customElementsDependencies")

dependencies {
    customElementsDependencies(project(path = ":producer", configuration = "customElements"))
}

val customElements = configurations.resolvable("customElements") { (1)
    extendsFrom(customElementsDependencies.get())
}

tasks.register("resolveCustom") {
    inputs.files(customElements.get())
    doLast {
        inputs.files.forEach { file: File ->
            logger.lifecycle("Resolved: ${file.name}")
        }
    }
}
```

consumer/build.gradle  

```groovy
def customElementsDependenciesProvider = configurations.dependencyScope('customElementsDependencies')

dependencies {
    customElementsDependencies(project(path: ':producer', configuration: 'customElements'))
}

def customElements = configurations.resolvable('customElements') { (1)
    extendsFrom(customElementsDependenciesProvider.get())
}

tasks.register('resolveCustom') {
    inputs.files(customElements)
    doLast {
        inputs.files.each { file ->
            logger.lifecycle("Resolved: ${file.name}")
        }
    }
}
```

|-------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **No attributes declared on the configuration in the consumer build**: This resolvable configuration lacks attributes to describe what it needs. |

This approach works only because we explicitly name the configuration in the dependency declaration (`configuration = "customElements"`). Resolving configurations by name should be avoided.  

### Do This Instead
Always declare attributes on consumable and resolvable configurations:  
In `producer/build.gradle(.kts)`:  
producer/build.gradle.kts  

```kotlin
val CUSTOM_ATTRIBUTE = Attribute.of("custom", String::class.java) (1)
dependencies.attributesSchema.attribute(CUSTOM_ATTRIBUTE)

val generateFile = tasks.register("generateFile") {
    val outputFile = layout.buildDirectory.file("custom/output.txt")
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.writeText("Custom output from producer")
    }
}

configurations {
    consumable("customElements") {
        attributes { (2)
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(CUSTOM_ATTRIBUTE, "my-custom-value")
        }
        outgoing {
            artifact(generateFile)
        }
    }
}
```

producer/build.gradle  

```groovy
def CUSTOM_ATTRIBUTE = Attribute.of("custom", String) (1)
dependencies.attributesSchema.attribute(CUSTOM_ATTRIBUTE)

def generateFile = tasks.register('generateFile') {
    def outputFile = layout.buildDirectory.file('custom/output.txt')
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.text = 'Custom output from producer'
    }
}

configurations {
    consumable('customElements') {
        attributes { (2)
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.LIBRARY))
            attribute(CUSTOM_ATTRIBUTE, "my-custom-value")
        }
        outgoing {
            artifact(generateFile)
        }
    }
}
```

|-------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **New Attribute type defined in producer** : The consumable configuration defines a new `custom` attribute that contains project-specific variant identification information. The new Attribute is also added to the Gradle Attribute schema to ensure type-safety. |
| **2** | **Attributes declared in producer** : The consumable configuration adds `Category` = `LIBRARY`, and `custom` attribute = `my-custom-value`. These attributes will be used to identify this variant during resolution.                                               |

In `consumer/build.gradle(.kts)`:  
consumer/build.gradle.kts  

```kotlin
val customElementsDependencies = configurations.dependencyScope("customElementsDependencies")

dependencies {
    customElementsDependencies(project(":producer")) (1)
}

val CUSTOM_ATTRIBUTE = Attribute.of("custom", String::class.java) (2)
dependencies.attributesSchema.attribute(CUSTOM_ATTRIBUTE)

val customElements = configurations.resolvable("customElements") {
    extendsFrom(customElementsDependencies.get())
    attributes { (3)
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(CUSTOM_ATTRIBUTE, "my-custom-value")
    }
}

tasks.register("resolveCustom") {
    inputs.files(customElements.get())
    doLast {
        inputs.files.forEach { file: File ->
            logger.lifecycle("Resolved: ${file.name}")
        }
    }
}
```

consumer/build.gradle  

```groovy
def customElementsDependenciesProvider = configurations.dependencyScope('customElementsDependencies')

dependencies {
    customElementsDependencies(project(':producer')) (1)
}

def CUSTOM_ATTRIBUTE = Attribute.of("custom", String) (2)
dependencies.attributesSchema.attribute(CUSTOM_ATTRIBUTE)

def customElements = configurations.resolvable('customElements') {
    extendsFrom(customElementsDependenciesProvider.get())
    attributes { (3)
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.LIBRARY))
        attribute(CUSTOM_ATTRIBUTE, "my-custom-value")
    }
}

tasks.register('resolveCustom') {
    inputs.files(customElements)
    doLast {
        inputs.files.each { file ->
            logger.lifecycle("Resolved: ${file.name}")
        }
    }
}
```

|-------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **The project dependency does not use configuration name**: It is no longer necessary to specify the configuration name in the dependency declaration, as variant-aware dependency resolution will be used to select the correct variant. |
| **2** | **New Attribute type defined in consumer** : The resolvable configuration defines the same new `custom` attribute that contains project-specific variant identification information.                                                      |
| **3** | **Attributes declared in consumer** : The resolvable configuration is identified as `Category` = `LIBRARY`, and `custom` attribute = `my-custom-value`.                                                                                   |

By declaring compatible attributes, you decouple your projects from internal naming conventions, resulting in a more robust and maintainable build.  

## References
* [Understanding Variant Selection (Use `gradle_docs(path="userguide/variant_aware_resolution.md")`.)

* [Variant Attributes (Use `gradle_docs(path="userguide/variant_attributes.md")`.)

* [Resolvable and Consumable Configurations (Use `gradle_docs(path="userguide/declaring_configurations.md")`.)

* [Known Attributes](https://github.com/liutikas/gmm-wiki)

---

For the most up-to-date guidance, use `gradle_docs` with `tag:best-practices`.
