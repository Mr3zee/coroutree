# Wire lazy task outputs using `map` and `flatMap`
Use `flatMap` to extract a `Provider`-typed output from a task, and `map` to transform the resulting value. Together, they preserve the task dependency chain so Gradle knows which tasks must run first.  

## Explanation
When wiring task outputs to inputs, `flatMap` extracts a `Provider`-typed output from a task while preserving the task dependency that the output `Provider` carries. Then `map` transforms that value. For example, reading a file's contents or extracting a path, without breaking the chain. The typical pattern looks like:  

```kotlin
consumer.inputContent.set(
    producer.flatMap { it.outputFile }       // extract the Provider, keeping the task dependency
        .map { it.asFile.readText() }        // transform the value lazily at execution time
)
```

```groovy
consumer.inputContent.set(
    producer.flatMap { it.outputFile }       // extract the Provider, keeping the task dependency
        .map { it.asFile.text }              // transform the value lazily at execution time
)
```

For dependency tracking to work, task outputs should be directly annotated abstract getters:  

```kotlin
@get:OutputFile
abstract val outputFile: RegularFileProperty
```

```groovy
@OutputFile
abstract RegularFileProperty getOutputFile()
```

With this pattern, `flatMap` reliably carries the task dependency and `map` safely transforms the result.  
A few specific patterns break this chain:  
1. **Do not call `.get()` inside `map` or `flatMap`** --- the file doesn't exist yet at configuration time, breaking lazy configuration and task dependency tracking

2. **Do not use standalone `provider {}` to wrap task outputs** --- `provider {}` produces a disconnected provider with no task dependency information

3. **Use directly annotated abstract getters for task outputs** --- `flatMap` can only track dependencies through directly annotated abstract getters, not task outputs that exist only to define transforms of other properties. Getters that return `Provider` instead of `Property` also break tracking, even when annotated.

4. **Use `map` for plain values, `flatMap` for `Provider`-typed outputs** --- `flatMap` requires its closure to return a `Provider`; use `map` to extract `File` or `String` values

### 1. Do not call `.get()` inside `map` or `flatMap`
Calling `.get()` inside `map` to read file content fails because the file does not exist yet at configuration time --- the producer task has not run. This pattern also breaks the Configuration Cache.  
Both examples below use the same `generatorTask` producer; only the consumer differs.  

##### Don't Do This
build.gradle.kts  

```kotlin
val generatorTask = tasks.register<GeneratorTask>("generator") {
    outputFile.set(layout.buildDirectory.file("eager-output.txt"))
}

tasks.register<ConsumerTask>("consumeEager") {
    inputFile.set(generatorTask.flatMap { it.outputFile })
    inputContent.set(generatorTask.map {
        it.outputFile.get().asFile.readText() (1)
    })
}
```

build.gradle  

```groovy
def generatorTask = tasks.register('generator', GeneratorTask) {
    outputFile = layout.buildDirectory.file('eager-output.txt')
}

tasks.register('consumeEager', ConsumerTask) {
    inputFile = generatorTask.flatMap { it.outputFile }
    inputContent = generatorTask.map {
        it.outputFile.get().asFile.text (1)
    }
}
```

|-------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **File read at configuration time** : `.get()` realizes the file before the producer task has run; reading it here fails because the file doesn't exist yet. |

##### Do This Instead
Use the `flatMap`/`map` chain instead. The `map` on a `flatMap` result is evaluated lazily at execution time, when the file exists:  
build.gradle.kts  

```kotlin
val generatorTask = tasks.register<GeneratorTask>("generator") {
    outputFile.set(layout.buildDirectory.file("output.txt"))
}

tasks.register<ConsumerTask>("consumeLazy") {
    inputFile.set(generatorTask.flatMap { it.outputFile }) (1)
    inputContent.set(
        generatorTask.flatMap { it.outputFile }
            .map { it.asFile.readText() } (2)
    )
}
```

build.gradle  

```groovy
def generatorTask = tasks.register('generator', GeneratorTask) {
    outputFile = layout.buildDirectory.file('output.txt')
}

tasks.register('consumeLazy', ConsumerTask) {
    inputFile = generatorTask.flatMap { it.outputFile } (1)
    inputContent = generatorTask.flatMap { it.outputFile }
        .map { it.asFile.text } (2)
}
```

|-------|--------------------------------------------------------------------------------------------------------------------|
| **1** | **Lazy wiring via `flatMap`** : extracts the `outputFile` Provider while preserving the dependency on `generator`. |
| **2** | **Read content lazily** : chaining `map` on the `flatMap` result defers reading until execution time.              |

This preserves the task dependency because the provider chain remains connected to the original task.  

### 2. Do not use standalone `provider {}` to wrap task outputs
A standalone `provider {}` creates a provider with no connection to any task. Gradle cannot determine that it depends on the producer task, so the producer will not be added to the task graph.  
Both examples below use the same `generatorTask` producer; only the consumer differs.  

##### Don't Do This
build.gradle.kts  

```kotlin
val generatorTask = tasks.register<GeneratorTask>("generator") {
    outputFile.set(layout.buildDirectory.file("output.txt"))
}

tasks.register<ConsumerTask>("consumeNaked") {
    inputFile.set(generatorTask.flatMap { it.outputFile })
    inputContent.set(provider { (1)
        generatorTask.get().outputFile.get().asFile.readText()
    })
}
```

build.gradle  

```groovy
def generatorTask = tasks.register('generator', GeneratorTask) {
    outputFile = layout.buildDirectory.file('output.txt')
}

tasks.register('consumeNaked', ConsumerTask) {
    inputFile = generatorTask.flatMap { it.outputFile }
    inputContent = providers.provider { (1)
        generatorTask.get().outputFile.get().asFile.text
    }
}
```

|-------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **Disconnected `provider {}` block** : has no link to the generator task; Gradle cannot infer that `consumeNaked` depends on `generator` from this provider alone. |

##### Do This Instead
Use `flatMap` to extract the `Provider`-typed output, then chain `map` to transform the value:  
build.gradle.kts  

```kotlin
val generatorTask = tasks.register<GeneratorTask>("generator") {
    outputFile.set(layout.buildDirectory.file("output.txt"))
}

tasks.register<ConsumerTask>("consumeLazy") {
    inputFile.set(generatorTask.flatMap { it.outputFile }) (1)
    inputContent.set(
        generatorTask.flatMap { it.outputFile }
            .map { it.asFile.readText() } (2)
    )
}
```

build.gradle  

```groovy
def generatorTask = tasks.register('generator', GeneratorTask) {
    outputFile = layout.buildDirectory.file('output.txt')
}

tasks.register('consumeLazy', ConsumerTask) {
    inputFile = generatorTask.flatMap { it.outputFile } (1)
    inputContent = generatorTask.flatMap { it.outputFile }
        .map { it.asFile.text } (2)
}
```

|-------|--------------------------------------------------------------------------------------------------------------------|
| **1** | **Lazy wiring via `flatMap`** : extracts the `outputFile` Provider while preserving the dependency on `generator`. |
| **2** | **Read content lazily** : chaining `map` on the `flatMap` result defers reading until execution time.              |

### 3. Use directly annotated abstract getters for task outputs
When a task's output is computed via `.map {}` rather than directly annotated with `@OutputFile`, `flatMap` may extract the mapped provider without preserving the task dependency.  

##### Don't Do This
build.gradle.kts  

```kotlin
abstract class ProducerTask @Inject constructor(
    objectFactory: ObjectFactory
) : DefaultTask() {
    @get:Internal
    val someDirectory = objectFactory.directoryProperty()

    // This property is DERIVED via map - not directly annotated
    @get:OutputFile
    val outputFile = someDirectory.map { it.file("output.txt") }

    @TaskAction
    fun execute() {
        outputFile.get().asFile.writeText("content")
    }
}

val derivedProducer = tasks.register<ProducerTask>("produceDerived") {
    someDirectory.set(layout.buildDirectory.dir("output"))
}

tasks.register<Sync>("consumeDerived") {
    from(derivedProducer.flatMap { it.outputFile }) (1)
    into(layout.buildDirectory.dir("sync"))
}
```

build.gradle  

```groovy
abstract class ProducerTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getSomeDirectory()

    // This property is DERIVED via map - not directly annotated
    @OutputFile
    Provider<RegularFile> getOutputFile() {
        return someDirectory.map { it.file('output.txt') }
    }

    @TaskAction
    void execute() {
        outputFile.get().asFile.text = 'content'
    }
}

def derivedProducer = tasks.register('produceDerived', ProducerTask) {
    someDirectory = layout.buildDirectory.dir('output')
}

tasks.register('consumeDerived', Sync) {
    from(derivedProducer.flatMap { it.outputFile }) (1)
    into(layout.buildDirectory.dir('sync'))
}
```

|-------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **Derived output property** : when `outputFile` is built via `.map { ...​ }` rather than annotated directly, `flatMap` may lose the task dependency. |

##### Do This Instead
As a workaround, use `.map { it.property.get() }` to preserve the dependency:  
build.gradle.kts  

```kotlin
abstract class ProducerTask @Inject constructor(
    objectFactory: ObjectFactory
) : DefaultTask() {
    @get:Internal
    val someDirectory = objectFactory.directoryProperty()

    @get:OutputFile
    val outputFile = someDirectory.map { it.file("output.txt") }

    @TaskAction
    fun execute() {
        outputFile.get().asFile.writeText("content")
    }
}

val derivedProducer = tasks.register<ProducerTask>("produceDerived") {
    someDirectory.set(layout.buildDirectory.dir("output"))
}

tasks.register<Sync>("consumeDerivedSync") {
    from(derivedProducer.map { it.outputFile.get() }) (1)
    into(layout.buildDirectory.dir("sync"))
}
```

build.gradle  

```groovy
abstract class ProducerTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getSomeDirectory()

    @OutputFile
    Provider<RegularFile> getOutputFile() {
        return someDirectory.map { it.file('output.txt') }
    }

    @TaskAction
    void execute() {
        outputFile.get().asFile.text = 'content'
    }
}

def derivedProducer = tasks.register('produceDerived', ProducerTask) {
    someDirectory = layout.buildDirectory.dir('output')
}

tasks.register('consumeDerivedSync', Sync) {
    from(derivedProducer.map { it.outputFile.get() }) (1)
    into(layout.buildDirectory.dir('sync'))
}
```

|-------|----------------------------------------------------------------------------------------------------------------------------|
| **1** | **Workaround with `.map { it.property.get() }`** : preserves the task dependency at the cost of an explicit `.get()` call. |

The preferred approach is to use directly annotated abstract getters instead of derived ones, so that `flatMap` works reliably:  
build.gradle.kts  

```kotlin
abstract class DirectProducerTask : DefaultTask() {
    // Directly annotated property - not derived
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun execute() {
        outputFile.get().asFile.writeText("content")
    }
}

val directProducer = tasks.register<DirectProducerTask>("directProducer") {
    outputFile.set(layout.buildDirectory.file("output/output.txt"))
}

tasks.register<Sync>("consumeDirect") {
    from(directProducer.flatMap { it.outputFile }) (1)
    into(layout.buildDirectory.dir("sync-direct"))
}
```

build.gradle  

```groovy
abstract class DirectProducerTask extends DefaultTask {
    // Directly annotated property - not derived
    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void execute() {
        outputFile.get().asFile.text = 'content'
    }
}

def directProducer = tasks.register('directProducer', DirectProducerTask) {
    outputFile = layout.buildDirectory.file('output/output.txt')
}

tasks.register('consumeDirect', Sync) {
    from(directProducer.flatMap { it.outputFile }) (1)
    into(layout.buildDirectory.dir('sync-direct'))
}
```

|-------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **Directly annotated abstract getter** : with `@OutputFile` on an abstract `RegularFileProperty`, `flatMap` carries the task dependency reliably. |

### 4. Use `map` for plain values, `flatMap` for `Provider`-typed outputs
When a task uses old-style getters (like `File`) instead of `Provider`-typed outputs, use `map` instead of `flatMap` to extract the value. The `flatMap` closure must return a `Provider`; wrapping a plain value in a standalone `provider {}` would compile, but --- as in item 2 --- it would drop the task dependency.  

##### Do This
build.gradle.kts  

```kotlin
abstract class LegacyTask : DefaultTask() {
    // Old-style eager property (before Provider API)
    @get:OutputDirectory
    lateinit var destinationDir: File

    @TaskAction
    fun execute() {
        destinationDir.mkdirs()
        File(destinationDir, "output.txt").writeText("content")
    }
}

val legacy = tasks.register<LegacyTask>("legacy") {
    destinationDir = layout.buildDirectory.dir("docs").get().asFile
}

tasks.register<ConsumerTask>("consumeLegacy") {
    inputFile.fileProvider(legacy.map { File(it.destinationDir, "output.txt") }) (1)
    inputContent.set(legacy.map { File(it.destinationDir, "output.txt").readText() })
}
```

build.gradle  

```groovy
abstract class LegacyTask extends DefaultTask {
    // Old-style eager property (before Provider API)
    @OutputDirectory
    File destinationDir

    @TaskAction
    void execute() {
        destinationDir.mkdirs()
        new File(destinationDir, 'output.txt').text = 'content'
    }
}

def legacy = tasks.register('legacy', LegacyTask) {
    destinationDir = layout.buildDirectory.dir('docs').get().asFile
}

tasks.register('consumeLegacy', ConsumerTask) {
    inputFile.fileProvider(legacy.map { new File(it.destinationDir, 'output.txt') }) (1)
    inputContent = legacy.map { new File(it.destinationDir, 'output.txt').text }
}
```

|-------|----------------------------------------------------------------------------------------------------------------------------------------|
| **1** | **Use `map` for non-Provider outputs** : when the producer exposes a plain `File` instead of a Provider, `map` is the right extractor. |

## References
* [Working with task inputs and outputs (Use `gradle_docs(path="userguide/lazy_configuration.md")`.)

---

For the most up-to-date guidance, use `gradle_docs` with `tag:best-practices`.
