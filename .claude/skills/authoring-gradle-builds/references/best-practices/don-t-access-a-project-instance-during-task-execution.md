# Don't access a `Project` instance during Task Execution
Do not access a `Project` instance inside a task action or task property.  

## Explanation
Accessing the `Project` instance during task execution is not compatible with the [Configuration Cache (Use `gradle_docs(path="userguide/configuration_cache.md")`.) and should be avoided. Alternatives exist for almost every use case involving data or operations from the `Project` object:  
* Specify the data you were previously retrieving from the `Project` instance as an explicit task input.

* Use similar functionality already exposed through the `Task` object (e.g., `Task.getLogger()` instead of `Project.getLogger()`).

* Capture `Project` values in a local variable during task configuration, which can then be referenced during execution.

|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|   | Accessing the `Project` instance during *task configuration* is expected and safe --- this is how you wire task input properties to project properties. The problem arises when `Project` is accessed during *task execution* (e.g., inside `@TaskAction` or `doLast`). |

## Example
### Don't Do This
build.gradle.kts  

```kotlin
abstract class VersionTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val outputFile = outputDirectory.file("build_version.txt")
        outputFile.get().asFile.writeText(project.version.toString()) (1)
    }
}

tasks.register<VersionTask>("generateVersionFile") {
    outputDirectory.set(project.layout.buildDirectory)
}
```

build.gradle  

```groovy
abstract class VersionTask extends DefaultTask {

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void run() {
        def outputFile = outputDirectory.file("build_version.txt")
        outputFile.get().asFile.text = project.version.toString() (1)
    }
}

tasks.register("generateVersionFile", VersionTask) {
    outputDirectory = project.layout.buildDirectory
}
```

|-------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **Reading `project.version` in a task action** : Inside the task's action, the `Project` instance is accessed to read the `version` property. |

There are two main problems with this setup:  
1. Accessing the `Project` instance during task execution will cause Configuration Cache failures.

2. Because the version is not declared as a task input, Gradle cannot track it. This leads to incorrect up-to-date results if the project version changes.

### Do This Instead
To ensure compatibility, avoid accessing the `Project` instance during task execution. Instead, tasks should explicitly declare all required inputs.  
build.gradle.kts  

```kotlin
abstract class VersionTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String> (1)

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        outputDirectory.file("build_version.txt").get().asFile.writeText(version.get())
    }
}

tasks.register<VersionTask>("generateVersionFile") {
    version.set(project.version.toString()) (2)
    outputDirectory.set(project.layout.buildDirectory.dir("build-info")) (3)
}
```

build.gradle  

```groovy
abstract class VersionTask extends DefaultTask {
    @Input
    abstract Property<String> getVersion() (1)

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void run() {
        outputDirectory.file("build_version.txt").get().asFile.text = version.get()
    }
}

tasks.register("generateVersionFile", VersionTask) {
    version = project.version (2)
    outputDirectory = project.layout.buildDirectory.dir("build-info") (3)
}
```

|-------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **Declare the version as an input property**: This allows Gradle to track the version string for up-to-date checks.                             |
| **2** | **Assign the version during configuration** : It is safe to read `project.version` during the configuration phase to assign it to a task input. |
| **3** | **Set the output location** : Use `project.layout` during configuration to define the task's output directory.                                  |

Now, when running the task, there are no "hidden inputs" that Gradle cannot track. This ensures that up-to-date checks accurately determine when a task needs to run again. Additionally, because the `Project` object is not accessed after configuration, the Configuration Cache will function correctly.  

## References
* [Configuration Cache Requirements (Use `gradle_docs(path="userguide/configuration_cache_requirements.md")`.)

---

For the most up-to-date guidance, use `gradle_docs` with `tag:best-practices`.
