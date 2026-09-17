# coroutree

Shows the **concurrency tree** of a Kotlin/JVM (or mixed Kotlin + Java) program: threads, coroutines, scopes and
context changes as nodes, and what happened to each of them — launches, suspensions, dispatcher switches, exceptions
and where they went, cancellation and who asked for it, blocked threads — in historical order, live or recorded.

A Gradle plugin attaches a Java agent to the JVMs your build forks; a desktop GUI shows what the agent saw.

Design: [docs/DESIGN.md](docs/DESIGN.md). Trace format: [docs/TRACE_FORMAT.md](docs/TRACE_FORMAT.md).
**Status: milestone 1 of 5** — the dynamic vertical slice. There is no static mode yet.

## Trying it

```sh
./gradlew -p samples run -Psample=ExceptionPropagation -Pcoroutree    # run a sample program under the agent
./gradlew -p samples coroutreeView                                    # open the GUI on the latest trace

./gradlew -p samples run -Psample=Interactive -Pcoroutree             # a program that runs until you press Enter…
./gradlew -p samples coroutreeView                                    # …watched live from a second terminal
```

`samples/` is a standalone build that uses the plugin, the agent and the GUI straight from this checkout. Its programs
(`samples/src/main/kotlin/samples`, one per construct) are also the test corpus, with the trees they must produce in
`samples/golden/dynamic`.

## Using it in a build

Nothing is published yet; `./gradlew publishAllPublicationsToLocalRepository` fills `build/repo`, a Maven repository
with all the artifacts.

```kotlin
plugins {
    id("org.jetbrains.kotlinx.coroutree") version "0.1.0-SNAPSHOT"
}

coroutree {
    // All optional. The agent is attached to JavaExec and Test tasks when the build runs with -Pcoroutree.
    enabled = providers.gradleProperty("coroutree").isPresent
    includePackages("com.acme")             // what counts as project code; default: the packages of the project's sources
    excludePackages("com.acme.generated")
    live { enabled = true }                 // serve the trace to the GUI while the program runs
    stackDepth = 32
    ideCommand = "idea --line {line} {path}" // how the GUI opens a source location; default: IntelliJ's built-in server
}
```

Apply it in every module whose sources should be navigable. Traces and live-session descriptors land in the root
project's `build/coroutree/`, grouped by build invocation; `coroutreeView` opens the newest.

Without Gradle: `java -javaagent:coroutree-agent.jar=trace.file=out.ctrace,include=com.acme -jar app.jar`, then
`java -jar coroutree-gui-<version>-<os>.jar --trace out.ctrace`. Threads blocked on `synchronized` are reported by a small
native (JVMTI) probe inside the agent jar. The Gradle plugin passes it as `-agentpath:`; started by hand, the agent loads
it itself, which JDK 24+ comments on with a native-access warning — unpack
`kotlinx/coroutree/agent/native/<platform>/` from the jar and add `-agentpath:<file>` to avoid that, or `monitor=false`
to go without.

The agent is a development tool. It records stacks and context values verbatim, costs a multiple of the run time in
coroutine-heavy code, and expects to be the only agent touching coroutine internals (not together with
kotlinx-coroutines-debug or the IDE debugger's coroutine agent). Supported: JDK 21+, kotlinx.coroutines 1.9 – 1.11;
with another version it says so, and says loudly if the internals it hooks are not there.

## Modules

| | |
|---|---|
| `coroutree-model` | The trace format (kotlinx.serialization protobuf) and the fold from events to a tree. Shared by the GUI, the tests and, later, the static mode. |
| `coroutree-agent` | The Java agent. `main`: premain and ASM transformers. `runtime`: what instrumented code calls, on the bootstrap class path — plain Java without dependencies, including its own protobuf writer. `native`: the JVMTI monitor-contention probe, one C file. |
| `coroutree-gradle-plugin` | DSL, agent wiring for `JavaExec`/`Test`, the source index that lets the GUI map stack frames to files, `coroutreeView`. Depends on nothing but Gradle. |
| `coroutree-gui` | Compose Multiplatform Desktop app: tree, event log, details, open-in-IDE; recorded and live. |
| `coroutree-integration-tests` | Runs the sample corpus under the real agent in forked JVMs: golden trees, the live stream, the plugin ↔ agent contract, three kotlinx.coroutines versions, an unsupported one. |
| `samples/` | The corpus, as a standalone build that uses the plugin. |
| `spikes/` | Experiments behind design decisions; see [docs/spikes](docs/spikes). |

## Working on it

```sh
./gradlew build                                              # everything, including all tests
./gradlew :coroutree-integration-tests:test -PupdateGoldens  # rewrite samples/golden after a deliberate change; review the diff
./gradlew :coroutree-gui:run --args="--demo"                 # the GUI on a synthetic trace (--demo=live streams it)
./gradlew :coroutree-gui:test -PscreenshotTrace=/path/x.ctrace   # offscreen renders in coroutree-gui/build/screenshots
```

Needs JDK 26 (the toolchain the build asks for) and, for the monitor probe, a C compiler as `cc` on the PATH (without
one the build still passes, the agent just has no probe). Add `-Dcoroutree.debug=true` to a traced JVM to get the agent's own
stack traces when it reports an internal error.
