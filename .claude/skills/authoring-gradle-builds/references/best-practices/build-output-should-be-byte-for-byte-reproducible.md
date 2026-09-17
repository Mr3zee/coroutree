# Build Output Should Be Byte-for-Byte Reproducible
A build is reproducible when the same source code produces byte-for-byte identical output files, on any machine, at any time.  

## Explanation
Reproducibility matters far beyond the publishing flow. If binaries remain identical, then downstream consumers can verify that they match the source. Build systems typically verify artifact checksums; non-determinism in artifact bytes defeats caches and deduplication logic without providing any benefit.  
With this strong form of reproducibility, the question "Did my change actually change anything?" is easy to answer: compare the output hashes. Gradle's own up-to-date checks and [remote build cache (Use `gradle_docs(path="userguide/build_cache.md")`.) also rely on stable inputs producing stable outputs.  
Some common sources of non-determinism in builds are file timestamps inside archives, the order of entries inside archives, and the SDK used to compile classes. For JVM builds, keeping wall-clock timestamps on `.class` files inside a `.jar`, or letting archive entries follow the filesystem's directory-iteration order, means that two clean builds of the same source produce slightly different artifacts with different checksums even though the output is functionally identical.  

|---|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|   | Since Gradle 9.0.0, archive artifacts are set to be [reproducible by default (Use `gradle_docs(path="9.0.0/release-notes.md")`.). |

This is list of examples is **not exhaustive**. There are other ways to introduce non-reproducibility in your project. One good test to confirm reproducibility is to try re-building an older version on a different machine, and comparing the output to the published binary.  

## Example
### Don't Do This
build.gradle.kts  

```kotlin
plugins {
    `java-library` (1)
}

tasks.named<Jar>("jar") {
    isPreserveFileTimestamps = true   (2)
    isReproducibleFileOrder = false   (3)
}
```

build.gradle  

```groovy
plugins {
    id 'java-library' (1)
}

tasks.named('jar', Jar) {
    preserveFileTimestamps = true   (2)
    reproducibleFileOrder = false   (3)
}
```

|-------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | No `java { toolchain { ...​ } }` block is declared. Gradle has no default toolchain, so the JDK used for compilation falls back to whichever `JAVA_HOME` is active. Different machines (and even different JDK patch releases on the same machine) emit different bytecode. |
| **2** | Sets `preserveFileTimestamps` to `true`, overriding the Gradle 9.0+ default of `false`. Every entry inside the jar is stamped with the build's wall-clock time, so the same source produces a different jar on every build.                                                 |
| **3** | Sets `reproducibleFileOrder` to `false`, overriding the Gradle 9.0+ default of `true`. Archive entries are ordered by the filesystem's directory-iteration order, which varies by OS and filesystem.                                                                        |

### Do This Instead
build.gradle.kts  

```kotlin
plugins {
    `java-library`
}

java {
    toolchain {
        // Choose your project's required version
        languageVersion = JavaLanguageVersion.of(21) (1)
    }
}
(2)
```

build.gradle  

```groovy
plugins {
    id 'java-library'
}

java {
    toolchain {
        // Choose your project's required version
        languageVersion = JavaLanguageVersion.of(21) (1)
    }
}
(2)
```

|-------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1** | Pin the JDK used to compile so the build is decoupled from each developer's local `JAVA_HOME` and from whichever JDK happens to be on the CI image; Gradle auto-provisions a matching JDK if one isn't found.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| **2** | Leave archive defaults alone --- Gradle 9.0+ sets `preserveFileTimestamps = false` and `reproducibleFileOrder = true` on every [AbstractArchiveTask (Use `gradle_docs(path="javadoc/org/gradle/api/tasks/bundling/AbstractArchiveTask.md")`.) (`Jar`, `War`, `Ear`, `Zip`, `Tar`). The performance cost of these defaults is negligible: `reproducibleFileOrder` adds a sort over directory listings that's dominated by I/O and compression work, and dropping timestamps actually makes archives marginally smaller and avoids unnecessary downstream cache invalidation. If you maintain a build that still supports an older Gradle version, set both flags explicitly with `tasks.withType<AbstractArchiveTask>().configureEach { ...​ }`. |

## References
* [Toolchains for JVM projects (Use `gradle_docs(path="userguide/toolchains.md")`.)

* [AbstractArchiveTask (Use `gradle_docs(path="javadoc/org/gradle/api/tasks/bundling/AbstractArchiveTask.md")`.)

* [reproducible-builds.org](https://reproducible-builds.org/)

---

For the most up-to-date guidance, use `gradle_docs` with `tag:best-practices`.
