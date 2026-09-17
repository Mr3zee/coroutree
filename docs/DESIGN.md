# coroutree — design

A Gradle plugin that instruments Kotlin/JVM (and mixed Kotlin+Java) programs and visualises their
**concurrency tree** in a standalone desktop GUI. Two modes: **static** (what may happen, without running)
and **dynamic** (what did happen, in historical order, on a live or recorded run).

Status: **M1 (dynamic vertical slice) is implemented**, M2–M5 are not started. §12 records what M1 built, where it
refined or departed from the text below, and what it left open. Items marked **[assumed]** were decided by the author
of this document without an explicit answer and need confirmation; M1 implemented the ones it touched as assumed.

## 1. Decisions at a glance

| Topic | Decision |
|---|---|
| Dynamic capture | Java agent attached by the Gradle plugin; load-time bytecode instrumentation |
| Static analysis | Source-level: Kotlin Analysis API (K2, standalone) + Java PSI, lowered to one common IR |
| Handle resolution | Full interprocedural points-to (custom, source-level) for Job/scope/Thread/Future/executor handles |
| GUI | Compose Multiplatform Desktop, separate process, resolved and launched by the plugin |
| Transport | Append-only trace file **and** live stream over a loopback socket (same frames) |
| Tree edge | Structural parent (Job hierarchy / starting thread / STS owner); "launched from" is a cross-link |
| Node model | Nodes = execution units with a lifetime; events are an ordered list on the node |
| Overhead budget | Dev/debug tool, accuracy first; 2–10x slowdown in coroutine-heavy code is acceptable |
| History UX | Tree + time scrubber + synchronized event log |
| Static × dynamic | Overlay via shared source-site IDs |
| Libraries | Opaque by default; built-in models for known primitives; include/exclude packages in DSL |
| Diagnostics | Visualiser + lightweight warning badges; no build-failing check task |
| Source navigation | Open in IDE only (no embedded source viewer) |
| Trace bounds | Unbounded; user's responsibility |
| Platforms | Latest Kotlin, latest GA JDK, JVM targets only (kotlin-jvm and KMP `jvm`); no Android |
| Multi-module | One aggregated static model per build; one trace per JVM, grouped per build invocation |
| Coordinates | Group `org.jetbrains.kotlinx`, artifacts `coroutree[-module]`, plugin id `org.jetbrains.kotlinx.coroutree` (same convention as kover / atomicfu) |
| Privacy | Non-goal. Dev-only tool, not intended for production runs; traces may contain stacks and context values verbatim |
| First milestone | Dynamic vertical slice |

## 2. Model (shared by both modes)

### 2.1 Nodes — execution units

| Kind | Created by |
|---|---|
| `Thread` (platform / virtual) | `Thread.start`, `Thread.ofVirtual()…`, `Executors.newVirtualThreadPerTaskExecutor` |
| `Coroutine` | `launch`, `async`, `runBlocking`, `produce`, `actor`, `future`, `launchIn`, `shareIn`/`stateIn`, `channelFlow`/`callbackFlow` |
| `Scope` | `coroutineScope`, `supervisorScope`, `withTimeout(OrNull)`, `StructuredTaskScope` |
| `ContextChange` | `withContext`, `flowOn`, `runInterruptible`, `ScopedValue.where(..).run/call` — labelled with the context **diff** |
| `Task` | `Executor.execute`, `ExecutorService.submit/invokeAll`, `ForkJoinPool`, `CompletableFuture.*Async`, `StructuredTaskScope.fork` |
| `Pool` **[assumed]** | Synthetic grouping node for dispatcher/executor worker threads, so pool threads don't hang off whichever thread happened to trigger pool growth |

Every node has: id, kind, name, **source site**, structural parent, optional creator cross-link,
context snapshot (Job, dispatcher, `CoroutineName`, `ThreadContextElement`s, custom elements, ScopedValue bindings),
origin tag (project / library), warnings.

Dynamic node state: `active | suspended | blocked | cancelling | completed | failed | cancelled`.

### 2.2 Events (ordered list on a node)

- `Launched` (on child; mirrored as child edge on parent)
- `ContextChanged` — general element diff
- `DispatcherChanged` — separate case; dynamic also records the actual thread run on
- `Suspended` / `Resumed` (dynamic: with thread; static: plain "may suspend here", collapsed by default, **no cancel links to them**)
- `ExceptionThrown`, `ExceptionPropagated(from → to)`, `ExceptionHandled(by: catch | CoroutineExceptionHandler | supervisor | Deferred-held | uncaughtExceptionHandler)`
- `CancellationRequested(by site, target)`, `CancellationPropagated(parent → child | child → parent)`
- `ThreadBlocked(reason: monitor | wait | join | park | sleep | io | runBlocking)` / `ThreadUnblocked`
- `ThreadInterrupted(by)`
- `VirtualThreadMounted/Unmounted(carrier)`, `VirtualThreadPinned(reason)`
- Channel `send`/`receive` and `select` as suspension-type events; channel edges between nodes as cross-links

Dynamic events carry a global sequence number (atomic counter), nanoTime timestamp, thread id, and a captured stack.
Static events carry a **guard chain** instead.

### 2.3 Source site ID

Stable key used by both modes: `(module, file path, line, column?, enclosing declaration FQN, construct kind, ordinal of that kind on the line)`.
Static knows all fields. Dynamic knows class/method/line from the stack frame and maps to file path using a
class→source index the Gradle plugin embeds in the trace header; it matches static sites by
`(file, line, kind, ordinal)`. **Risk:** several same-kind constructs on one line, and inline functions shifting line numbers
(mitigated by reading Kotlin's SMAP debug info).

### 2.4 Cross-links (non-tree edges)

`launchedFrom`, `cancels`/`interrupts` (call site → target node), `awaits`/`joins`, `channel`, `runsOn` (coroutine/task → thread, dynamic).

## 3. Dynamic mode

**Agent** (`-javaagent`, attached by the plugin to `JavaExec`, `Test`, and `application`'s `run` when enabled):

- Coroutines: instrument `kotlin.coroutines.jvm.internal.DebugProbesKt` (created / suspended / resumed) the way
  kotlinx-coroutines-debug does, plus `JobSupport` internals for parent attachment, cancellation and exception propagation
  (`cancelImpl`, `childCancelled`, `notifyCancelling`, `handleJobException`), `withContext`/dispatch paths for context and dispatcher changes,
  `CoroutineExceptionHandler` invocation.
- Threads: `Thread.start`, `interrupt`, `Object.wait`, monitor contention (JVMTI-free: via `Thread.State` transitions around instrumented
  `LockSupport.park*`, `Thread.sleep`, `join`, and JDK blocking I/O entry points), `runBlocking`.
- Virtual threads: mount/unmount/pinned hooks in `java.lang.VirtualThread` (and/or JFR `jdk.VirtualThreadPinned` event stream).
- Java structured: `StructuredTaskScope` (fork/join/shutdown/close), `ScopedValue.Carrier.run/call`, executors,
  `ForkJoinPool`, `CompletableFuture` async stages. Version-adaptive; silently off if the API shape isn't present.
- Attribution: `StackWalker` on structural events; suspend/resume use continuation debug metadata (no stack walk).
- Library filtering: everything is recorded, nodes tagged project/library by package; GUI collapses library-internal nodes by default.

**[assumed]** Agent runtime that lives on the bootstrap class path is plain Java with zero dependencies (no kotlin-stdlib leakage into the app);
the rest of the agent is shaded. Events go through a lock-free queue to a writer thread (itself excluded from capture).

**Other agents: out of scope for now.** coroutree assumes it is the only agent touching `DebugProbesKt` and the coroutine/thread internals.
Running together with kotlinx-coroutines-debug, the IntelliJ debugger's coroutine agent, or `DebugProbes.install()` is unsupported and its
behaviour undefined; no detection, chaining or ordering logic is built. Coroutine nodes are keyed by the `Job` in the continuation's context
(jobless continuations by the root completion identity). If coexistence is wanted later, the known route is inject-instead-of-replace plus
registering the transformer as retransform-capable so it runs after theirs.

**Trace format [assumed]:** length-delimited protobuf frames (kotlinx.serialization), versioned header containing build id, JVM info,
module list, class→source index, project package prefixes. Live stream = identical frames over a loopback-only TCP socket,
random port + session token written to `build/coroutree/sessions/<buildInvocation>/<pid>.json`. Unbounded size.

## 4. Static mode

Pipeline:

1. **Frontends** — Kotlin via Analysis API standalone; Java via the PSI that ships with it (cross-language resolve comes for free).
   Input: all JVM source sets of all modules + their compile classpaths, provided by the Gradle plugin. Aggregated into one model.
2. **Lowering to a common IR** — functions, calls, allocations (= node-creating constructs), assignments, fields, params/returns,
   lambda captures, collections as one abstract element, control-flow regions (branch / loop / try-catch-finally / early exit).
3. **Primitive models** — hand-written summaries for kotlinx.coroutines (builders, scopes, Flow concurrency operators incl. hidden
   coroutines in `shareIn`/`stateIn` and hidden cancellation in `*Latest`, channels/`produce`/`actor`/`select`, bridges:
   `suspendCancellableCoroutine`, `future`/`await`, `asCoroutineDispatcher`, `runInterruptible`, `limitedParallelism`, `withTimeout`),
   `java.lang.Thread` (platform + virtual), `java.util.concurrent` (executors, FJP, CompletableFuture, locks/queues as blocking sites),
   `StructuredTaskScope`, `ScopedValue`. Other library calls are opaque leaves.
4. **Tree construction** — roots are entry points: `main` functions, test methods, and user-configured entry points (DSL list / annotation).
   Calls into project functions are followed and their subtree inlined at the call site; recursion and revisits collapse to a
   "↻ see node X" reference. Functions with concurrency constructs never reached from a root are listed as **unrooted**.
5. **Guard chain** on every node/event: stack of enclosing conditions between it and its parent — `if`/`when` branch with condition source text,
   loop ("0..n times"), try/catch/finally region, safe-call/elvis, preceding early return. No path-feasibility reasoning, no constant folding.
6. **Points-to** — Andersen-style, interprocedural over project code, flow- and context-insensitive to start; allocation sites are
   node-creating constructs. Resolves receivers of `cancel`, `cancelAndJoin`, `cancelChildren`, `interrupt`, `shutdown(Now)`, `Future.cancel`,
   `join`, `await` → `cancels`/`interrupts`/`awaits` cross-links to target **nodes**. Genuinely unresolvable receivers
   (reflection, values from opaque libraries) get an explicit "unresolved target" marker **[assumed fallback]** rather than a guessed link.
7. **Exception model** — sources: explicit `throw`/`error`/`check`/`require`; cancellation sources (`cancel`, `withTimeout`, `ensureActive`,
   `interrupt`, STS shutdown); declared throws of called APIs (Java checked, `@Throws`). Propagated up the call chain to a matching catch, then
   annotated with **propagation semantics**: cancels siblings under `coroutineScope`, stops at `supervisorScope`/`SupervisorJob`, reaches
   `CoroutineExceptionHandler`, held in `Deferred` until `await`, swallowed by `catch`/`runCatching`.

Output: static model file (same serialization family as the trace) in `build/coroutree/static/`.

## 5. Warning badges (both modes where applicable)

Unstructured root (`GlobalScope`, ad-hoc `Job()`); blocking call on `Default`/`Main` dispatcher; virtual thread pinned;
`CancellationException` swallowed; exception lost in never-awaited `Deferred`; interrupt flag cleared and ignored.
Filterable list in the GUI. No build failure.

## 6. GUI (Compose Multiplatform Desktop)

- Opens: static model, recorded trace, live session (process picker over the session index), or static + trace overlay.
- Tree view with node state, context-diff labels, guard badges (static), warning badges, cross-links on hover/selection.
- Time scrubber replaying the tree to any moment; live = pinned to "now". Synchronized flat event log; selection is two-way.
- Search (name / file / exception type) and filters (node kind, event kind, thread, dispatcher, project-vs-library, time range).
- Aggregation: nodes from the same site under the same parent collapse to `launch ×1000 Main.kt:12` with state counts, expandable.
- Overlay: which "may happen" static items happened, hit counts, dynamic nodes static didn't predict.
- Source attribution click → **open in IDE** (IntelliJ built-in localhost endpoint / `idea --line`; command template configurable).
  Dynamic events show the full captured stack, each frame clickable.
- Virtualized rendering for large trees.

## 7. Gradle plugin

```kotlin
plugins { id("org.jetbrains.kotlinx.coroutree") }

coroutree {
    enabled = providers.gradleProperty("coroutree").isPresent   // attach agent to JavaExec/Test/run; this is the default
    includePackages("com.acme")             // project-vs-library tagging; default = project source packages
    excludePackages("com.acme.generated")
    entryPoints { function("com.acme.Server.handle"); annotatedWith("org.springframework.web.bind.annotation.RestController") }  // static mode: arrives with M3
    live { enabled = true }
    stackDepth = 32
    ideCommand = "idea --line {line} {path}"   // optional; how the GUI opens a source location
}
```

Tasks: `coroutreeStatic` (aggregated analysis → model file), `coroutreeView` (resolves the GUI as a Maven artifact with per-OS classifier
and launches it on the latest model / trace / live session). Agent attachment is automatic when enabled.

## 8. Modules

```
coroutree-model          shared node/event/site-id model + serialization (KMP-free, JVM)
coroutree-agent          premain, ASM transformers, shaded; bootstrap runtime in plain Java
coroutree-static         AA + Java PSI frontends → IR → tree, guards, points-to, exception model
coroutree-gradle-plugin  DSL, tasks, agent wiring, source index, GUI launcher
coroutree-gui            Compose Desktop app
samples/                 corpus of small programs, one per construct/edge case
```

## 9. Milestones

- **M1** *(done, see §12)* Dynamic vertical slice: model + trace format; agent for coroutine launch/context/dispatcher/exception/cancellation/suspend and
  thread start/block/interrupt; Gradle wiring; GUI tree + event log + open-in-IDE on live and recorded traces.
- **M2** Java extras (executors, STS, ScopedValue, virtual thread mount/pin); Flow/channels/bridges/custom context elements; scrubber;
  search, filters, aggregation.
- **M3** Static: frontends, IR, primitive models, tree, guard chains, static suspension events.
- **M4** Points-to + cancel/interrupt/await links; exception sources and propagation semantics; warning badges.
- **M5** Static × dynamic overlay.

## 10. Testing **[assumed]**

Sample corpus with golden trees for both modes (same program → static golden + dynamic golden, which also tests the overlay);
Gradle TestKit for plugin wiring; agent integration tests on the supported JDK; points-to unit tests on the IR.

## 11. Risks and open questions

1. License header / publishing pipeline for `org.jetbrains.kotlinx` (coordinates themselves are decided).
2. "Latest JDK": `StructuredTaskScope` may still be a preview API — static understands it regardless; dynamic hooks need the app to run with
   `--enable-preview`. **Accepted.** Pin the concrete JDK via toolchain.
3. Coexistence with other agents (kotlinx-coroutines-debug, IDE debugger) is deliberately ignored for now (§3); running under the IntelliJ debugger with the coroutines panel may conflict.
4. Dynamic→static site matching on lines with several same-kind constructs and through inline functions (§2.3).
5. Instrumenting kotlinx.coroutines internals ties the agent to coroutine library versions; needs a per-version hook table and a loud
   failure mode when an unknown version is seen.
6. Points-to cost and precision on large codebases; flow/context sensitivity may be needed for scope-per-request patterns.
7. Monitor-contention (and pinning) detection: bytecode instrumentation alone can't see `synchronized` contention. **M1 spike, decide on evidence:**
   prototype both (a) JFR event streaming in-process (`jdk.JavaMonitorEnter`, `jdk.JavaMonitorWait`, `jdk.ThreadPark`, `jdk.VirtualThreadPinned`) and
   (b) a small JVMTI native agent (`MonitorContendedEnter/Entered`, `MonitorWait/Waited`). Evaluate on: event latency and ordering against our
   global sequence (JFR is buffered and thresholded — can it be merged into the historical order correctly?), completeness for short
   contentions, stack availability, overhead, and the cost of shipping native binaries for macOS/Linux/Windows × x64/arm64.
   The `ThreadBlocked` event shape is backend-agnostic so the choice doesn't leak into the model or GUI.
   **Spike done:** [spikes/monitor-contention.md](spikes/monitor-contention.md). Both see the same contentions at no measurable cost; JFR delivers
   them 0.6–1 s late and orderable by timestamp only, JVMTI delivers them in sequence and can be loaded by the Java agent itself
   (`System.load`, no `-agentpath`). **Decided: JVMTI**, and implemented (§12). One thing the spike missed: on JDK 24+ `System.load` from
   unnamed code draws a native-access warning and is announced to be blocked one day, so the Gradle plugin unpacks the probe and passes it as
   `-agentpath:` after all; `System.load` remains the fallback for an agent attached by hand.
8. Open-in-IDE only means traces viewed on a machine without the checkout have dead links (accepted).

## 12. M1 as built

What exists: `coroutree-model`, `coroutree-agent`, `coroutree-gradle-plugin`, `coroutree-gui`, the `samples` corpus with
golden trees, and `coroutree-integration-tests` running the corpus under the real agent. `./gradlew build` runs it all;
README.md says how to try it. The trace format is specified in [TRACE_FORMAT.md](TRACE_FORMAT.md).

### Decisions taken while building (refinements of the text above)

| Where | Decision |
|---|---|
| §3 agent packaging | The **whole agent is plain Java**, not just the bootstrap runtime; the only thing shaded is ASM. The runtime encodes protobuf by hand (`TraceEncoder`, ~200 lines) instead of linking kotlinx.serialization, so no Kotlin ever enters the application's JVM and nothing has to be relocated (relocating `kotlin.*` also rewrites the string constants a transformer names its targets with). The model decodes with kotlinx.serialization as designed; every integration test crosses that boundary. |
| §3 hooks | `JobSupport` is given a field through an injected interface (`Tagged`), so a job → node lookup is a field read, not a weak map. Node creation hooks the end of the `AbstractCoroutine` / `JobImpl` constructors; suspend/resume come from the `DebugProbesKt` probes; lifecycle from `notifyCancelling`, `parentCancelled`, `ChildHandleNode.childCancelled`, `makeCompletingOnce`, `completeStateFinalization`, `handleCoroutineException`. All of it is one table (`HookTable`). Bytecode is rewritten branch-free with the ASM tree API and **no frame recomputation**, so transforming never loads a class. |
| §3 "loud failure mode" | A required hook target that is missing is an ERROR diagnostic in the trace, on stderr and as a banner in the GUI; a kotlinx.coroutines version outside the tested range (1.9 – 1.11, all three run by the integration tests against the same goldens) is a WARNING. |
| §2.1 tree edges | `runBlocking` without a parent Job hangs off the execution unit that called it (the thread, or the coroutine that blocks in it). Other parentless jobs are roots, linked to their creator by *launched from*. `Pool` exists as assumed, for the coroutine scheduler, ForkJoinPools and the virtual-thread carriers. |
| §2.1 names | A node is titled with a `CoroutineName` only if it was given one itself; an inherited name stays in the context. |
| §2.2 events | Added `DISCOVERED` (a node that predates observation, e.g. the main thread), `CANCELLING` and `FINISHED(final state)`. `CancellationPropagated(child → parent)` is not emitted dynamically: at run time that moment *is* `ExceptionPropagated`. A scope or `runBlocking` that fails reports `ExceptionPropagated` to its **caller**, which is where the exception goes; the caller's rethrow is not reported a second time as thrown. `ExceptionHandled(catch)` comes from instrumenting typed catch blocks of project classes only. |
| §2.2 blocking | Only the outermost blocking call of an execution unit is reported (`join` → `wait`, `sleep` on a virtual thread → `park`), per unit: a coroutine that sleeps inside `runBlocking`'s loop is its own report. Parks made by kotlinx.coroutines itself (idle workers, event loops) are not blocking and are dropped. I/O in M1: sockets (`NioSocketImpl` read/accept/connect), `Process.waitFor`, reads of standard input; file I/O deliberately not. |
| §2.3 source index | Keyed by **(package, source file name)** rather than by class: that is what a stack frame carries, and both languages put a class in its file's package. The plugin writes it as text (so the plugin depends on nothing but Gradle, and survives Gradle's embedded Kotlin); the agent re-encodes it into the header. Aggregated across project dependencies the way `jacoco-report-aggregation` does. |
| §11.7 monitor contention | JVMTI, `coroutree-agent/src/native/coroutree_monitor.c`: `MonitorContendedEnter/Entered` call `Hooks.blockEnter(MONITOR)` / `blockExit()` on the contending thread, so a contended `synchronized` is a `THREAD_BLOCKED` like any other — same sequence, same nesting rules, same attribution to the coroutine running on the thread. The binaries travel in the agent jar (`kotlinx/coroutree/agent/native/<platform>/`); the plugin passes the host's as `-agentpath:`, a hand-attached agent loads it with `System.load`; `monitor=false` turns it off. Contention on the JVM's own monitors (class loading and initialization) is reported too: it is blocking, and the stack says what it is. |
| §3 live stream | The writer thread writes the file and nothing else. Each live client gets a thread that reads the trace file back from its first byte and follows it as it grows, so a late joiner and a slow reader are the same ordinary case and neither can stall the capture. On shutdown clients get up to 3 s to receive the end. |
| §3 ordering | `seq` is dense; the writer sorts each batch and readers restore the exact order by waiting for predecessors. Stack frames are interned (`StackFrameDef`). |
| §7 plugin | `enabled` defaults to "`-Pcoroutree` is present". The plugin also passes `-Xshare:off`: appending to the bootstrap class path disables application CDS anyway and the JVM warns about it on every start. `entryPoints {}` is not in the DSL until M3 gives it a meaning. |
| §7 GUI artifact | As designed (classifier per OS: `macos-arm64`, `macos-x64`, `linux-x64`, `linux-arm64`, `windows-x64`), launched detached with `--dir build/coroutree --open-latest`. A composite build may instead point `coroutreeGui` at the GUI project. |
| §10 testing | As assumed, for the dynamic half: golden trees per sample, TestKit for the plugin, forked-JVM agent tests on the toolchain JDK, plus: three kotlinx.coroutines versions, a fake unsupported one, the live stream, the GUI's feed against a live agent, and a corpus-coverage test that fails when any event kind stops occurring. The agent was also smoke-tested by hand on JDK 21 and 24. |

### Known gaps, candidates for M2

- **Jobless continuations** (`suspend fun main` before it enters a scope, `sequence {}`, raw `startCoroutine`) are ignored, contrary to §3
  ("keyed by the root completion identity"). Everything launched from them is traced.
- **SMAP is not read** (§2.3): a construct inside a user-declared `inline` function reports the inlined line number.
- **The monitor probe is built for the platform of the build only** (on macOS as a universal binary; Linux needs `cc`; Windows is not
  scripted). A release has to build it on each platform and merge with `-Pcoroutree.prebuiltNatives=<dir>`; only macOS arm64 has been run.
  Where the agent jar has no binary for the platform it says so once and everything but the `MONITOR` reason works.
- Executor threads are plain threads started as a side effect (origin *library*); `Task` nodes and executor pools are M2 as planned.
- One copy of kotlinx.coroutines per JVM is traced (the first seen); a second copy in another class loader is ignored.
- The GUI reads a recorded file once; it does not tail a file that is still being written (it follows live sessions through the socket).
- Project isolation: traces go to the *root* project's build directory, which the plugin reads from each project.
- Plugin: dependency source indexes are looked up on the conventionally named runtime classpaths, so a KMP target declared as
  `jvm("desktop")` gets its own sources indexed but not those of the projects it depends on. Windows is untested throughout
  (`coroutreeView` argument quoting, the `idea` launcher being `idea.cmd`, sources on another drive).
- Nothing is published; coordinates resolve from `build/repo` after `publishAllPublicationsToLocalRepository`.
