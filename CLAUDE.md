# coroutree

The [README](README.md) describes the product and setup.
[DESIGN](docs/DESIGN.md) defines behavior, milestones (§9) and known gaps (§12).
Before you change behavior, read the design. Record new decisions there.

The concurrency tree is a top-down node-link graph, with no indented outline (§6.1).
Execution control must preserve independent sequences, including coroutines on one thread (§3.1).

## Commands

**Gradle runs only through the `gradle` MCP server** (`.mcp.json`, skills in `.claude/skills/`, start with
`using-gradle`). Never run `./gradlew` in a shell. If the server's tools are missing, ask for the server to be enabled.
Each line below is a `commandLine`. `projectRoot` is always the repository root (`samples/` has no wrapper, so it takes
`-p`).

```text
["build"]                                                          everything, all tests
[":coroutree-integration-tests:test"]                              the agent end to end (forks a JVM for each sample)
[":coroutree-integration-tests:test", "-PupdateGoldens"]           rewrite samples/golden/dynamic, then REVIEW the diff
[":coroutree-gradle-plugin:check"]                                 unit + TestKit functional tests + validatePlugins
[":coroutree-gui:test"] (+ "-PscreenshotTrace=/x.ctrace")          also renders coroutree-gui/build/screenshots/*.png: look at them
[":coroutree-gui:run", "--args=--demo"]            background      GUI on a synthetic trace (--demo=live streams it, with execution control)
[":coroutree-gui:hotRun", "--auto"]                background      the GUI with Compose Hot Reload (JetBrains Runtime, downloaded by foojay)
["-p", "samples", "run", "-Psample=<Name>", "-Pcoroutree"]         a sample through the real plugin + agent
      + "-Pcoroutree.pace.startPaused", "-Pcoroutree.pace.eventsPerSecond=2", "-Pcoroutree.pace=false", "-Pcoroutree.live=false"
["-p", "samples", "coroutreeView"]                 background      the GUI, which lists the JVMs under the agent (~/.coroutree/sessions) and recent runs
["publishAllPublicationsToLocalRepository"]                        build/repo: consume it like a user would (~200 MB of GUI jars)
```

JDK 26 toolchain, Gradle 9.7.1 wrapper, Kotlin 2.4.20, configuration cache on. `cc` on the PATH builds the native probe.

## Map

| Module | What | Notes |
|---|---|---|
| `coroutree-model` | Wire model (`Trace.kt`, kotlinx.serialization protobuf), `TraceReader/Writer`, `tree/TraceStore` (fold events → tree, snapshots), `tree/Labels.kt` (all wording), `tree/TreeRenderer` (golden text) | JVM 17 bytecode, `explicitApi()` |
| `coroutree-agent` | `src/runtime`: bootstrap-class-path runtime (`Hooks`, `Tracer`, `TraceEncoder`, writer thread, `LiveServer`). `src/main`: `Premain`, ASM tree-API patches, **`HookTable`** (the one place that names JDK / kotlinx.coroutines internals). `src/native`: JVMTI monitor probe (C). `src/test/kotlin`: the unit tests | plain Java, release 21. Only ASM is shaded. The tests are Kotlin; the fixtures that stand in for JDK, kotlinx or application classes (`src/test/java`) stay Java |
| `coroutree-gradle-plugin` | DSL, `-javaagent`/`-agentpath` arguments for `JavaExec`/`Test`, source index, `coroutreeView` | depends on the Gradle API only. Kotlin api/language 2.2, so it runs in Gradle 9.0+ |
| `coroutree-gui` | Compose Desktop, a standalone app: `source/` (feeds, the session registry `~/.coroutree/sessions` that every agent writes to and the GUI polls), `view/` (testable logic: `GraphBuilder` snapshot → graph, `GraphViewState` viewport and glide, `TraceViewModel`), `view/graph/` (pure geometry: `LayoutEngine` = tidy forest + orthogonal router, `InvariantChecker`), `ui/` (composables), `demo/` | uber jars for each OS, published under classifiers |
| `coroutree-integration-tests` | Runs `samples/` under the real agent jar in forked JVMs. Also lays out every sample's trace and checks the drawing invariant (`GraphInvariantCorpusTest`) | its main source set *is* `samples/src` |
| `samples/` | Standalone build that uses the plugin through `includeBuild("..")`. One program for each construct. `golden/dynamic/*.txt` | the test corpus |
| `spikes/`, `docs/spikes/` | Experiments behind decisions (JFR or JVMTI) | not part of the build |

Data flow: instrumented code → `Hooks.*` (on the thread where it happens, takes the global `seq`) → lock-free queue →
writer thread → `.ctrace` file. Live clients are threads that tail that file over a loopback socket. GUI and tests:
`TraceReader` → `TraceStore.accept` → immutable `TraceSnapshot`.

## Rules that are not obvious from the code

**The agent runtime (`src/runtime`) runs inside `Thread.start`, `LockSupport.park`, `Object.wait` and JVMTI callbacks.**

- No dependencies, no Kotlin. No lambdas and no `invokedynamic` on hook paths (the runtime is compiled with
  `-XDstringConcat=inline`). Use anonymous classes. No pattern `switch` and no records on hook paths.
- Every hook: `if (!Tracer.active) return;`, then the `ThreadState.inHook` guard, then
  `try { … } catch (Throwable) { report }`. A hook never throws into the application and never blocks on application
  locks. Enter and exit pairs (`blockEnter`/`blockExit`) must stay balanced even when a hook is skipped.
- Hook arguments are `Object`: the runtime can't name Kotlin types. It reaches them through `KotlinAccess` (method
  handles).
- Don't hold application objects (Jobs, Threads, Throwables, context elements) longer than the job does.
- A node can be referenced before it is defined (a job is cancelled inside its own constructor, a thread is
  interrupted before `start`). Nodes are created on demand and *defined* by the constructor or start hook (`defined`
  flag).
- Application code that the runtime calls (`toString`, `hashCode`) goes through `Describe` and is never trusted.

**A hook waits in exactly one place: `Pace.await(ts, unit, node, other)`** (DESIGN §3.1, §12 "M1.2 as built").

- The arguments: the unit the step happens in, the node it happens to, a second node it emits on or `null`.
- The call comes after the hook has found everybody it is about. Finding (`threadNode`, `nodeOf`, `poolOf`) can emit a
  `DISCOVERED`, which is a step of its own with its own `await`, so all finding comes first. The call comes before the
  hook reads what it reports, decides or locks anything. Never wait in `Tracer.emit` (events are emitted under
  `synchronized (node)` and `DISCOVERY_LOCK`), and never for the writer or a reader.
- Stopping the program is ours to get right, the owner's top priority. The hold is **never recorded**: no
  `THREAD_BLOCKED`, interrupt, unmount, node state or "held" marker of our making. The trace records `heldNanos` and
  `PaceDef`. The hold **never disturbs state**: the interrupt flag is cleared for the wait and restored, bookkeeping
  stays balanced, nothing is thrown, and the park permit is untouched.
- **The wait is `Thread.sleep`, never `LockSupport.park*`.** A thread held inside the hook of its own `park()`
  loses an `unpark` that came before and hangs.
- The gate has no queue, no owner thread and no lock. A held thread loops on volatile reads and its own sleep tick, so
  nothing can strand it. The gate fails open when tracing is off, when the JVM's shutdown sequence *starts*, and when
  the last controller is gone.
- Threads of the JVM itself (the root thread group) are never held: the Signal Dispatcher starts the SIGTERM handler
  with `Thread.start`.
- Whatever an agent thread runs to deliver a command must not be blockable by a held thread: no regex, no first-time
  class loading. Those paths are warmed up at start.
- Never introduce a global order of turns. A pace is a minimum interval for each sequence, measured between events (the
  program's own blocking counts). Parallel stays parallel, two coroutines on one thread included. The events of one
  hook call are one step and are never spread out. A hook that emits on a node of another sequence awaits that node
  too.
- With `pace=false` the gate doesn't exist (`Pace.GATE == null`). It doesn't merely stay open.
- The paced and the stepped corpus must match the unpaced goldens. Every new hook names its `await` point. A hook that
  runs where a thread can't wait has none.
- `Tracer.emit` records an ERROR for an event that didn't pass the gate. With `-Dcoroutree.debug` (every test JVM) the
  agent checks at each thread's end that its blocking calls are balanced. Both fail the goldens.
- Time isn't held. A sample whose tree rests on wall-clock margins is listed, with the reason, in
  `PacedCorpusTest.WALL_CLOCK_SENSITIVE`. A new sample with `sleep`-ordered threads or a `delay` outside an event loop
  goes there.
- The `PaceControl` sample is the program that the end-to-end tests of execution control drive.

**Bytecode is rewritten without frame recomputation** (`COMPUTE_MAXS` only, frames expanded on read), so a transform
never loads a class. Inserted code must be branch-free. The single hand-written frame is in `MethodPatch.around`. JDK
classes are *retransformed*: no shape changes there. The `Tagged` field on `JobSupport` works because kotlinx classes
are patched at load. A new hook is one entry in `HookTable` and one method in `Hooks`. Whatever the transformer runs
must have run once in `Agent.warmUp`: no first-time class loading or lambda bootstrap inside a class load.

**The trace format has two implementations that share nothing**: `TraceEncoder`/`Wire` (Java, by hand) and `Trace.kt`
(Kotlin). Change both and `docs/TRACE_FORMAT.md`. Proto3 conventions: zero defaults are omitted, enums start at
`UNSPECIFIED = 0`, unknown fields are skipped. `seq` is dense, and readers rely on it to restore the exact order.

**The plugin shares no code with anything.** Its contracts with the agent are duplicated and documented on both sides:

- `agent.properties` keys: `AgentArgumentProvider` and `AgentConfig`.
- The text source index: `SourceIndexFormat` and `SourceIndexFile`.
- Native probe paths: `HostPlatform`, `MonitorProbeLoader` and the agent's `build.gradle.kts`.

`AgentConfigTest` pins the first two. Everything the plugin holds must survive the configuration cache: no `Project` or
`Configuration` in task state. The build id comes from a `BuildService` typed loosely, because projects can load the
plugin in different class loaders.

**Semantics** (DESIGN §12, TRACE_FORMAT.md):

- An event belongs to the node it happens to. `otherNodeId` is where it came from.
- Scopes, `withContext` and `runBlocking` rethrow to their *caller*: `EXCEPTION_PROPAGATED` to the caller, no second
  `EXCEPTION_THROWN`.
- Only the outermost blocking call of an execution unit is reported.
- A node is named only by a `CoroutineName` it was given itself.
- Library bookkeeping context elements (`UndispatchedMarker`, `CoroutineId`) are hidden.
- Origin is project or library by the package of the source-site frame.
- A coroutine without a Job is keyed by its root frame. `suspend fun main` is a child of its thread. Generators aren't
  traced.
- **Stacks are logical.** `SourceMaps` splits a JVM frame of inlined code into the inline functions' bodies (`inlined`,
  innermost first) and the call site, for every kind of stack and every class outside the JDK. Never build a
  `StackFrameRef` for a stack any other way. Skip `inlined` frames when you ask what *method* a stack is in.

**The graph's drawing invariant is strict** (DESIGN §6.2, the owner's hard requirement):

- Node boxes never overlap.
- No two lines share a segment, touch or run closer than the minimum gap, so each is recognizable as a separate line.
  Sole exception: the shared trunk of one parent's structural edges.
- Lines cross only at a point, at 90°, never at a bend.
- A line never passes through a box that isn't its endpoint.
- Edge labels cover nothing.

Layout and routing are our own engine (tidy tree and orthogonal router, one track for each edge in each channel): pure
geometry in `view/graph/` without Compose types, deterministic. The invariant checker runs over the corpus, the demo
trace and seeded random forests. A violation is an engine bug. Never special-case a trace, relax the checker or the
gaps, or draw curves over the tree to pass it. No per-node collapse and no same-site aggregation: both were removed
from the design.

How the engine keeps the invariant (DESIGN §12, "M1.1 as built"): every vertical piece of a line has an x of its own.
Box centers are multiples of `GraphMetrics.COLUMN`. A layer's cross-link ports and gap tracks are in the residue class
`GraphMetrics.linkClass(depth)`. So anything you add that runs vertically must take its x from one of those classes and
move boxes by whole columns only. Every horizontal piece gets its y from the channel's `TrackProfile`. The checker uses
none of this and must not: it judges shapes. The graph is rebuilt only when `GraphModel` changes structurally, so keep
anything that changes with each event (state, runs-on of unselected nodes) out of `GraphNode`.

## Samples and goldens

- A golden is the tree, the states, the context diffs and each node's own events in order: everything that is
  identical in every run. **Samples must be deterministic for each node.** Prefer `runBlocking`'s single thread. Where
  real threads are involved, leave wide timing margins (tens of ms), and never let the order of two events on one node
  depend on a race. Monitor contention stays out of goldens except in `MonitorContention`: the JVM's class-loading
  locks make it random.
- For new agent behavior:
  1. Write a sample that shows it.
  2. Add the sample to `GoldenTreeTest.SAMPLES`.
  3. Run the integration tests with `-PupdateGoldens`.
  4. Read the diff line by line. It is the review of what the agent now reports.
  5. Rerun the suite a few times.
- `CorpusTest` fails when any event kind stops occurring in the corpus. It reruns the version-sensitive samples on
  kotlinx.coroutines 1.10 and 1.9 and in debug mode (`-ea`) against the same goldens.
- The supported kotlinx.coroutines range is `HookTable.TESTED_COROUTINES`. Extend it only together with the versions in
  `coroutree-integration-tests/build.gradle.kts`.

## Conventions

- Comments give the reason, not the action. KDoc only where it gives something the signature doesn't. Match the
  surrounding style.
- **Kotlin block comments nest.** A `/*` inside a KDoc or a `/* */` comment (a path glob like `native/*`, a Java
  comment quoted in prose) comments out the rest of the file without an error, in `.kt` and in `.gradle.kts`. When you
  must write one, use a `//` comment.
- In build scripts use `tasks.named<JavaExec>("run")`, not `tasks.run` (that is Kotlin's `run`).
- The wording of events and nodes is in `model/tree/Labels.kt` only. The GUI and the goldens both use it.
- GUI logic goes into `view/` or `source/` with plain unit tests. Composables stay thin. After UI changes, render the
  screenshots and look at them. For the graph, look for overlapping boxes, lines and labels. The checker enforces the
  invariant. The look is for what the checker can't judge, such as readability.
- Before you call agent or plugin work done, check it end to end: integration tests, then `-p samples run … -Pcoroutree`.
- Only macOS arm64 has been run. Windows is untested throughout. The native probe is built for the host platform only.
  Releases merge the others with `-Pcoroutree.prebuiltNatives=<dir>`.
