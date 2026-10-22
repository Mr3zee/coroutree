# coroutree

coroutree shows the **concurrency tree** of a Kotlin/JVM or mixed Kotlin and Java program, live or from a recorded trace.
A Gradle plugin attaches a Java agent to your program. A desktop GUI shows threads, coroutines, scopes and context changes,
with their launch, suspension, exception, cancellation and blocking history.

Runtime tracing, the graph and execution control are implemented. The standalone desktop GUI is in progress.
[Planned milestones](docs/DESIGN.md#9-milestones) and [known gaps](docs/DESIGN.md#12-m1-as-built) are in the design.
Static code analysis is out of scope.

## Graph and execution control

Boxes show node state. Cross-links show launches, cancellation, interruption and the selected node's thread.

| Control | Action |
|---|---|
| Drag or scroll | Pan |
| Ctrl/⌘ + scroll | Zoom |
| *Fit* / *Selection* | Fit the graph or selected node in the pane |
| Minimap | Navigate a graph larger than the pane |
| Legend | Toggle cross-link types |
| *library / pools* | Show library-internal nodes and pools, hidden by default |
| Hover a box | Show its source and context |
| Click a box | Select it and show its details |
| Double-click a box | Open its source in the IDE |
| Right-click a box | Open in IDE, zoom to node, subtree controls |
| Click a frame or a source site | Open it in the IDE |

Sources open in a running JetBrains IDE. The GUI finds the IDE through its process and hands the file to it through
the IDE's launcher. With several IDEs running, the one that has the project open wins. A frame outside the project
sources opens as a class, which needs the traced project open in that IDE. With no IDE running, the GUI reports it.
`--ide <command>` or `ideCommand` replaces the launcher. The command opens project files only.

For a live session, *Pause* / *Resume* (Space), *Step* (→) and the speed slider control the program.
The slider ranges from one event in ten seconds to unlimited. Subtree controls are in the node's context menu and details pane.
A mark on a box identifies a subtree setting.

- Pace limits each sequence independently. Two coroutines remain separate sequences even on one thread.
  Time spent blocked or suspended counts toward the interval.
- Pause holds a thread at its next traced event. A loop without events continues.
- A held coroutine occupies its thread. Pausing one child of a single-threaded `runBlocking` stops that event loop.
- Wall-clock time continues. Timeouts can fire on release, and pacing can hide or cause races.
- The trace records hold durations and setting changes. Holds create no blocking events or node states.
- When the last controller disconnects, the program resumes at its configured pace and removes subtree settings.
  Ctrl-C ends a paused program.

## Trying it

```sh
bin/coroutree-gui
./gradlew -p samples run -Psample=ExceptionPropagation -Pcoroutree
```

Run them in separate terminals. The GUI lists traced JVMs and recent runs. Select the sample to open its trace.
`bin/coroutree-gui` rebuilds the GUI jar and runs it on the JDK in `JAVA_HOME` or on the path.
`./gradlew :coroutree-gui:launch` rebuilds the jar and starts the GUI from an IDE's Gradle tool window.
It takes the same arguments as the jar (`--trace`, `--session`, `--ide`, `--demo`). A project that applies the plugin
can also start the GUI with `./gradlew coroutreeView`.
For a longer live run, use `-Psample=Interactive` and press Enter in its terminal to end it.
For a paused start, use `-Psample=PaceControl -Pcoroutree.pace.startPaused`, then resume or step from the GUI.
Type `stop` and Enter in the program's terminal to end it.

## Using it in a build

Nothing is published yet. `./gradlew publishAllPublicationsToLocalRepository` fills `build/repo`, a Maven repository
with all the artifacts.
Add it to your plugin and dependency repositories, or use the [sample composite build](samples/settings.gradle.kts).

```kotlin
plugins {
    id("org.jetbrains.kotlinx.coroutree") version "0.1.0-SNAPSHOT"
}

coroutree {
    enabled = providers.gradleProperty("coroutree").isPresent
    includePackages("com.acme")             // Default: the project's source packages
    excludePackages("com.acme.generated")
    live { enabled = true }                 // Default: true
    pace {                                  // GUI control requires live
        enabled = true                      // Default: true. false removes the gate and the controls
        startPaused = false                 // Default: false. true holds at the first event
        eventsPerSecond = 0.2               // Initial pace of each sequence. Default: unlimited
    }
    stackDepth = 32                         // Default: 32
    ideCommand = "idea --line {line} {path}" // Default: the launcher of a running JetBrains IDE
    sessionsDirectory = layout.buildDirectory.dir("coroutree/sessions") // Default: ~/.coroutree/sessions
}
```

All options are optional. By default, `-Pcoroutree` attaches the agent to `JavaExec` and `Test` tasks.
Gradle properties override the build script:

| Property | Overrides |
|---|---|
| `-Pcoroutree` / `-Pcoroutree=false` | `enabled` |
| `-Pcoroutree.live=false` | `live.enabled` |
| `-Pcoroutree.pace=false` | `pace.enabled` |
| `-Pcoroutree.pace.startPaused` | `pace.startPaused` |
| `-Pcoroutree.pace.eventsPerSecond=2`, or `unlimited` | `pace.eventsPerSecond` |

Invalid values fail the build, except that `-Pcoroutree` treats any value other than `false` as enabled.
For a manually attached agent, the options are `live`, `pace`, `pace.paused` and `pace.events.per.second`.

Apply the plugin in every module whose sources must be navigable. Traces go to the root project's `build/coroutree/`,
grouped by build invocation. The GUI finds running JVMs and recent traces in `~/.coroutree/sessions/`.
`sessionsDirectory` in the DSL, or the agent option `sessions.dir`, changes that directory for a build, on CI for
example. An empty value writes no descriptor.

Without Gradle:

```sh
java -javaagent:coroutree-agent.jar=trace.file=out.ctrace,include=com.acme -jar app.jar
java -jar coroutree-gui-<version>-<os>.jar --trace out.ctrace
```

The native probe reports `synchronized` contention. Gradle loads it with `-agentpath:`.
A manually attached agent loads it itself, which causes a native-access warning on JDK 24 and later.
To avoid the warning, unpack `kotlinx/coroutree/agent/native/<platform>/` from the agent jar and add `-agentpath:<file>`.
The option `monitor=false` disables the probe.

The agent is a development tool:

- It records stacks and context values verbatim.
- Coroutine-heavy code runs several times slower.
- It must be the only agent that changes coroutine internals. Don't run it with kotlinx-coroutines-debug or with the
  IDE debugger's coroutine agent.

Supported: JDK 21 and later, kotlinx.coroutines 1.9 to 1.11. With another version of kotlinx.coroutines the agent
reports a warning. If an internal that it hooks is missing, the agent reports an error.

## Working on it

```sh
./gradlew build                                              # everything, including all tests
./gradlew :coroutree-integration-tests:test -PupdateGoldens  # rewrite samples/golden after an intended change, then review the diff
./gradlew :coroutree-gui:launch                             # build coroutree-gui/build/coroutree-gui.jar and start the GUI in the background
./gradlew :coroutree-gui:run --args="--demo"                 # the GUI on a synthetic trace (--demo=live streams it)
./gradlew :coroutree-gui:hotRun --auto                       # Compose Hot Reload
./gradlew :coroutree-gui:test -PscreenshotTrace=/path/x.ctrace   # offscreen renders in coroutree-gui/build/screenshots
```

The build needs JDK 26 as its toolchain. The monitor probe needs a C compiler as `cc` on the PATH. Without one the
build passes, and the agent has no probe. Add `-Dcoroutree.debug=true` to a traced JVM to get the agent's own stack
traces when it reports an internal error.

Contributor references: [code map](CLAUDE.md#map), [design](docs/DESIGN.md), [trace format](docs/TRACE_FORMAT.md)
and [repository guide](docs/onboarding/index.html).
The sample corpus is in `samples/src/main/kotlin/samples`, with expected trees in `samples/golden/dynamic`.
