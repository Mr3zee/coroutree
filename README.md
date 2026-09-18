# coroutree

Shows the **concurrency tree** of a Kotlin/JVM (or mixed Kotlin + Java) program: threads, coroutines, scopes and
context changes as nodes, and what happened to each of them — launches, suspensions, dispatcher switches, exceptions
and where they went, cancellation and who asked for it, blocked threads — in historical order, live or recorded.

A Gradle plugin attaches a Java agent to the JVMs your build forks; a desktop GUI shows what the agent saw.

Design: [docs/DESIGN.md](docs/DESIGN.md). Trace format: [docs/TRACE_FORMAT.md](docs/TRACE_FORMAT.md).
**Status: milestone 1 of 5** — the dynamic vertical slice, with the GUI drawing the tree as a graph (M1.1 in the
design): a top-down node-link diagram, boxes coloured by state, cross-links (*launches*, *cancels*, *interrupts*, and
*runs on* for the selected node) as edges of their own — and with **execution control** (M1.2): a running program can be
slowed down, paused and stepped from the GUI. There is no static mode yet.

In the graph: drag or scroll to pan, Ctrl/⌘ + scroll to zoom, *Fit* and *Selection* in the toolbar, the minimap once the
graph is bigger than the pane. The legend switches each kind of cross-link on and off; *library / pools* brings in the
dispatcher pools and library-internal coroutines that are left out by default. Rest on a box for its source line and
context, right-click it to open its source in the IDE. Whatever the trace, boxes never overlap and no two lines run on
or along each other: that is a tested invariant of the layout engine (DESIGN §6.2), not a matter of luck.

**Slowing down, pausing, stepping.** While the GUI follows a running program, the bar under the title has *Pause* /
*Resume* (Space), *Step* (→) and a speed slider, from one event in ten seconds to no limit. The same for one subtree is
in a node's context menu and in the details pane; a node that carries a setting of its own has a small mark on its box.
A few things worth knowing:

- A pace is **per sequence**: at "one event per second" every coroutine and every thread shows at most one event a second,
  and ten of them running in parallel show ten. Nothing is serialised; two coroutines that share a thread are two
  sequences. Time the program spends blocked or suspended of its own accord counts, so a coroutine that resumes after
  `delay(5000)` is not held at all.
- What is stopped is **events, not instructions**: a thread is held when it reaches its next event (a launch, a
  suspension, a blocking call, …). A loop that computes without ever reaching one is not stopped by *Pause*.
- **We hold threads, not coroutines.** A held coroutine keeps the thread it is on, and whatever else would run there
  waits with it; pausing one child of a single-threaded `runBlocking` stops that event loop.
- **Time is not held.** Pause for ten seconds inside `withTimeout(1000)` and it fires on release; slowing a program down
  changes how its parallel parts interleave, which can hide or provoke a race.
- The hold is never in the trace (no *blocked*, no event, no state): the trace says what the program did. What it does
  say is how long each thread was held, and every change of the settings, which the event log shows.
- If the GUI goes away, the program runs on at its configured pace; Ctrl-C ends a paused program as ever.

## Trying it

```sh
./gradlew -p samples run -Psample=ExceptionPropagation -Pcoroutree    # run a sample program under the agent
./gradlew -p samples coroutreeView                                    # open the GUI on the latest trace

./gradlew -p samples run -Psample=Interactive -Pcoroutree             # a program that runs until you press Enter…
./gradlew -p samples coroutreeView                                    # …watched live from a second terminal

./gradlew -p samples run -Psample=PaceControl -Pcoroutree -Pcoroutree.pace.startPaused   # held at its first event…
./gradlew -p samples coroutreeView                                    # …until you resume or step it here ("stop" + Enter ends it)
./gradlew :coroutree-gui:run --args=--demo=live                       # the controls on a synthetic session, no JVM needed
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
    pace {                                  // execution control; controlling it from the GUI needs live
        enabled = true                      // false: the JVM has no gate at all, and the GUI shows no controls
        startPaused = false                 // true: held at the first event until a GUI resumes or steps
        eventsPerSecond = 0.2               // the pace the run starts with, per sequence; unset = unlimited
    }
    stackDepth = 32
    ideCommand = "idea --line {line} {path}" // how the GUI opens a source location; default: IntelliJ's built-in server
}
```

What one wants to change for a single run is a Gradle property, and **the command line wins over the build script**, in
both directions: `-Pcoroutree` / `-Pcoroutree=false`, `-Pcoroutree.live=false`, `-Pcoroutree.pace=false`,
`-Pcoroutree.pace.startPaused`, `-Pcoroutree.pace.eventsPerSecond=2` (or `unlimited`). A value that does not parse fails
the build. Started by hand, the agent takes `live`, `pace`, `pace.paused` and `pace.events.per.second` like its other options.

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
| `coroutree-gui` | Compose Multiplatform Desktop app: the concurrency tree as a graph (own layout engine and edge router, with a checker for the drawing invariant), event log, details, open-in-IDE; recorded and live. |
| `coroutree-integration-tests` | Runs the sample corpus under the real agent in forked JVMs: golden trees, the live stream, the plugin ↔ agent contract, three kotlinx.coroutines versions, an unsupported one, and the graph's drawing invariant on every sample's trace. |
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
