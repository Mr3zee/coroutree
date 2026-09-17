# coroutree

A Gradle plugin + Java agent + desktop GUI that shows the **concurrency tree** of a Kotlin/JVM program: threads,
coroutines, scopes and context changes as nodes, with everything that happened to them in historical order, live or
recorded. `docs/DESIGN.md` is the agreed design (5 milestones); **M1, the dynamic vertical slice, is done** (and, since, its two
worst gaps: coroutines without a Job, source maps of inlined code), M2–M5
(Java extras/Flow/scrubber, static analysis, points-to, overlay) are not started. **Next is M1.1, a correction:** the GUI
must draw the concurrency tree as a **graph** (top-down node-link diagram, cross-links as real edges; DESIGN §6.1), and M1
built an indented outline instead (`TreePane`, `TreeRows`), which M1.1 deletes. The model stays a tree; "tree" never
means a directory-like view in this project. `docs/DESIGN.md` §12 records what M1
built, every place it refined the design, and the known gaps — read it before changing behaviour, and add to it when
you decide something the design did not.

## Commands

```sh
./gradlew build                                               # everything, all tests
./gradlew :coroutree-integration-tests:test                   # the agent end to end (forks a JVM per sample)
./gradlew :coroutree-integration-tests:test -PupdateGoldens   # rewrite samples/golden/dynamic, then REVIEW the diff
./gradlew :coroutree-gradle-plugin:check                      # unit + TestKit functional tests + validatePlugins
./gradlew :coroutree-gui:test [-PscreenshotTrace=/x.ctrace]   # also renders coroutree-gui/build/screenshots/*.png — look at them
./gradlew :coroutree-gui:run --args="--demo"                  # GUI on a synthetic trace (--demo=live streams it)
./gradlew -p samples run -Psample=<Name> -Pcoroutree          # a sample through the real plugin + agent
./gradlew -p samples coroutreeView                            # GUI on the latest trace / live session
./gradlew publishAllPublicationsToLocalRepository             # build/repo: consume it like a user would (~200 MB of GUI jars)
```

JDK 26 toolchain, Gradle 9.7.1 wrapper, Kotlin 2.4.20, configuration cache on. `cc` on the PATH builds the native probe.

## Map

| Module | What | Notes |
|---|---|---|
| `coroutree-model` | Wire model (`Trace.kt`, kotlinx.serialization protobuf), `TraceReader/Writer`, `tree/TraceStore` (fold events → tree, snapshots), `tree/Labels.kt` (all wording), `tree/TreeRenderer` (golden text) | JVM 17 bytecode, `explicitApi()` |
| `coroutree-agent` | `src/runtime`: bootstrap-class-path runtime (`Hooks`, `Tracer`, `TraceEncoder`, writer thread, `LiveServer`). `src/main`: `Premain`, ASM tree-API patches, **`HookTable`** (the one place that names JDK / kotlinx.coroutines internals). `src/native`: JVMTI monitor probe (C) | plain Java, release 21; only ASM is shaded |
| `coroutree-gradle-plugin` | DSL, `-javaagent`/`-agentpath` wiring for `JavaExec`/`Test`, source index, `coroutreeView` | depends on Gradle API only; Kotlin api/language 2.2 so it runs in Gradle 9.0+ |
| `coroutree-gui` | Compose Desktop: `source/` (feeds, sessions), `view/` (testable logic; from M1.1 the graph layout engine, edge router and invariant checker), `ui/` (composables), `demo/` | per-OS uber jars published under classifiers |
| `coroutree-integration-tests` | Runs `samples/` under the real agent jar in forked JVMs | its main source set *is* `samples/src` |
| `samples/` | Standalone build using the plugin via `includeBuild("..")`; one program per construct; `golden/dynamic/*.txt` | the test corpus |
| `spikes/`, `docs/spikes/` | Experiments behind decisions (JFR vs JVMTI) | not part of the build |

Data flow: instrumented code → `Hooks.*` (on the thread where it happens; takes the global `seq`) → lock-free queue →
writer thread → `.ctrace` file. Live clients are threads that tail that file over a loopback socket. GUI/tests:
`TraceReader` → `TraceStore.accept` → immutable `TraceSnapshot`.

## Rules that are not obvious from the code

**The agent runtime (`src/runtime`) runs inside `Thread.start`, `LockSupport.park`, `Object.wait`, JVMTI callbacks.**
- No dependencies, no Kotlin. No lambdas, no `invokedynamic` on hook paths (it is compiled with
  `-XDstringConcat=inline`; use anonymous classes; no pattern-`switch`, no records on hook paths).
- Every hook: `if (!Tracer.active) return;` → `ThreadState.inHook` guard → `try { … } catch (Throwable) { report }`.
  A hook never throws into the application, never blocks on application locks, and enter/exit pairs
  (`blockEnter`/`blockExit`) must stay balanced even when a hook is skipped.
- Hook arguments are `Object`: the runtime cannot name Kotlin types; it reaches them through `KotlinAccess` (method handles).
- Do not hold application objects (Jobs, Threads, Throwables, context elements) longer than the job does.
- A node may be referenced before it is defined (a job is cancelled inside its own constructor; a thread is interrupted
  before `start`). Nodes are created on demand, *defined* by the constructor/start hook (`defined` flag).
- Application code that the runtime calls (`toString`, `hashCode`) goes through `Describe` and is never trusted.

**Bytecode is rewritten without recomputing frames** (`COMPUTE_MAXS` only, frames expanded on read), so transforming
never loads classes. Inserted code must be branch-free; the single hand-written frame is in `MethodPatch.around`.
JDK classes are *retransformed*: no shape changes there (the `Tagged` field on `JobSupport` works because kotlinx
classes are patched at load). New hook = one entry in `HookTable` + one method in `Hooks`. Whatever the transformer
runs must have run once in `Agent.warmUp` (no first-time class loading or lambda bootstrap inside a class load).

**The trace format has two implementations that share nothing**: `TraceEncoder`/`Wire` (Java, by hand) and `Trace.kt`
(Kotlin). Change both plus `docs/TRACE_FORMAT.md`; proto3 conventions (zero defaults omitted, enums start at
`UNSPECIFIED = 0`, unknown fields skipped). `seq` is dense and readers rely on it to restore exact order.

**The plugin shares no code with anything.** Its contracts with the agent are duplicated on purpose and documented on
both sides: `agent.properties` keys (`AgentArgumentProvider` ↔ `AgentConfig`), the text source index
(`SourceIndexFormat` ↔ `SourceIndexFile`), native probe paths (`HostPlatform` ↔ `MonitorProbeLoader` ↔ the agent's
`build.gradle.kts`). `AgentConfigTest` pins the first two. Everything the plugin holds must survive the configuration
cache (no `Project`/`Configuration` captured in task state; build id comes from a `BuildService` typed loosely because
projects may load the plugin in different class loaders).

**Semantics worth remembering** (all in DESIGN §12 / TRACE_FORMAT): an event belongs to the node it happens to,
`otherNodeId` is where it came from; scopes, `withContext` and `runBlocking` rethrow to their *caller*
(`EXCEPTION_PROPAGATED` to the caller, no second `EXCEPTION_THROWN`); only the outermost blocking call of an execution
unit is reported; a node is named only by a `CoroutineName` it was given itself; library bookkeeping context elements
(`UndispatchedMarker`, `CoroutineId`) are hidden; origin = project/library by the source-site frame's package;
a coroutine without a Job is keyed by its root frame, `suspend fun main` hangs off its thread, generators are not
traced; **stacks are logical**: `SourceMaps` splits a JVM frame of inlined code into the inline functions' bodies
(`inlined`, innermost first) and the call site, for every kind of stack and every class outside the JDK, so never
build a `StackFrameRef` for a stack any other way, and skip `inlined` frames when asking what *method* a stack is in.

**The graph's drawing invariant is strict** (DESIGN §6.2, the owner's hard requirement): node boxes never overlap; no two
lines share a segment, touch or run closer than the minimum gap, so each is recognisable as a separate line (sole
exception: the shared trunk of one parent's structural edges); lines cross only at a point, at 90°, never at a bend; a
line never passes through a box that is not its endpoint; edge labels cover nothing. Layout and routing are our own
engine (tidy tree + orthogonal router, one track per edge per channel), pure geometry in `view/` without Compose types,
deterministic. The invariant checker runs over the corpus, the demo trace and seeded random forests; a violation is an
engine bug — never special-case a trace, relax the checker or the gaps, or draw curves over the tree to get past it.
No per-node collapse and no same-site aggregation: both were removed from the design on purpose.

## Samples and goldens

- A golden is the tree, states, context diffs and each node's own events in order — everything that is identical in
  every run. **Samples must be deterministic per node**: prefer `runBlocking`'s single thread; where real threads are
  involved, leave wide timing margins (tens of ms) and never let the order of two events on one node depend on a race.
  Monitor contention is kept out of goldens except in `MonitorContention` (the JVM's class-loading locks make it random).
- New agent behaviour ⇒ a sample that shows it ⇒ add it to `GoldenTreeTest.SAMPLES` ⇒ `-PupdateGoldens` ⇒ read the
  diff line by line (it is the review of what the agent now claims happened) ⇒ rerun the suite a few times.
  `CorpusTest` fails when any event kind stops occurring in the corpus, and reruns the version-sensitive samples on
  kotlinx.coroutines 1.10/1.9 and in debug mode (`-ea`) against the same goldens.
- Supported kotlinx.coroutines range is `HookTable.TESTED_COROUTINES`; extend it only together with the versions in
  `coroutree-integration-tests/build.gradle.kts`.

## Conventions

- Comments say why, not what; KDoc only where it tells something the signature does not. Match the surrounding style.
- **Kotlin block comments nest.** A `/*` inside a KDoc or a `/* */` comment (a path glob like `native/*`, a Java
  comment quoted in prose) silently comments out the rest of the file — in `.kt` and in `.gradle.kts`. It has bitten
  this repo twice; use `//` comments when you need to spell one.
- In build scripts use `tasks.named<JavaExec>("run")`, not `tasks.run` (that is Kotlin's `run`).
- Wording of events and nodes lives in `model/tree/Labels.kt` only; the GUI and the goldens both use it.
- GUI logic goes into `view/` or `source/` with plain unit tests; composables stay thin. After UI changes render the
  screenshots and actually look at them — for the graph, specifically for overlapping boxes, lines and labels (the
  checker is what enforces the invariant; the look is for what it cannot judge, such as readability).
- Verify end to end before calling agent/plugin work done: integration tests, then `-p samples run … -Pcoroutree`.
- Platform reality: only macOS arm64 has been run. Windows is untested throughout; the native probe is built for the
  host platform only (releases merge others with `-Pcoroutree.prebuiltNatives=<dir>`).
