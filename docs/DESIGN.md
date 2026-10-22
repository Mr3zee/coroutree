# coroutree design

coroutree records the concurrency tree of Kotlin/JVM and mixed Kotlin and Java programs for a standalone desktop GUI.
It supports live and recorded traces. Static code analysis is out of scope.

M1 (runtime tracing), M1.1 (the graph) and M1.2 (execution control) are implemented.
The standalone desktop GUI is in progress. M2 to M6 aren't started (§9).
Items marked **[assumed]** lack explicit confirmation. The implementation follows those assumptions where applicable.

## 1. Decisions at a glance

| Topic | Decision |
|---|---|
| Capture | Java agent, attached by Gradle, with load-time instrumentation |
| GUI | Compose Multiplatform Desktop in a separate process |
| Graph | Top-down node-link diagram, with a deterministic layout engine and orthogonal router (§6) |
| Transport | Append-only trace file and loopback socket with the same frames |
| Tree edge | Structural parent. The creator is a cross-link |
| Node | Execution unit with a lifetime and ordered events |
| Overhead | Development tool. A 2 to 10x slowdown in coroutine-heavy code is acceptable |
| Execution control | Independent pacing of sequences, pause and step, globally or per subtree (§3.1) |
| Libraries | Capture supported primitives, tag origin by package, hide library-internal subtrees by default |
| Diagnostics | Capture diagnostics. Runtime warning badges are future work. No build-failing check task |
| Sources | Open in IDE. No embedded source viewer |
| Trace bounds | Unbounded. The user manages trace size |
| Platforms | Latest Kotlin and GA JDK. Kotlin/JVM and KMP JVM targets. No Android |
| Multi-module | One trace per JVM, grouped by build invocation. Source indexes include project dependencies |
| Coordinates | Group `org.jetbrains.kotlinx`, artifacts `coroutree[-module]`, plugin `org.jetbrains.kotlinx.coroutree` |
| Privacy | Development use only. Traces contain stacks and context values verbatim |

## 2. Trace model

### 2.1 Nodes: execution units

| Kind | Constructs | Status |
|---|---|---|
| `Thread` | Platform and virtual threads | Implemented |
| `Coroutine` | Coroutine builders and jobless continuations | Implemented, with gaps in §12 |
| `Scope` | `coroutineScope`, `supervisorScope`, `withTimeout(OrNull)` | Implemented. `StructuredTaskScope` is M6 |
| `ContextChange` | `withContext`, `flowOn`, `runInterruptible` | Implemented. `ScopedValue` is M6 |
| `Task` | Executors, ForkJoinPool, CompletableFuture, `StructuredTaskScope.fork` | M3 and M6 |
| `Pool` **[assumed]** | Synthetic owner of worker threads | Implemented for dispatchers, ForkJoinPools and virtual-thread carriers |

Pool workers belong to their pool, independently of the thread that caused it to grow.
Nodes carry a source site, structural parent, creator, context and project or library origin.
Node states are `active`, `suspended`, `blocked`, `cancelling`, `completed`, `failed` and `cancelled`.
The [trace schema](TRACE_FORMAT.md#schema) defines the fields and events.

### 2.2 Events (ordered list on a node)

Events have a global sequence number, a `nanoTime` timestamp, a thread id and a stack.
Implemented events cover lifecycle, context and dispatcher changes, suspension, exceptions, cancellation, blocking and interrupts.

Future events include:

- Channel `send`, `receive` and `select` (M2).
- Virtual-thread mount, unmount and pinning (M6).

### 2.3 Source site

The source index maps `(package, source file name)` to a module and path.
Kotlin SMAP data and compiler markers recover logical frames for inlined code.
[Inline-frame semantics](TRACE_FORMAT.md#semantics) define the source-site rules and available detail.
Several constructs on one line share a location. Locations have no column or ordinal.

### 2.4 Cross-links (non-tree edges)

| Link | Status |
|---|---|
| `launchedFrom`, `cancels`, `interrupts`, `runsOn` | Implemented |
| `channel` | M2 |
| Runtime `awaits` / `joins` | Future work without a milestone |

## 3. Dynamic mode

The agent instruments coroutine probes and lifecycle internals, thread lifecycle, blocking calls and project catch blocks.
`HookTable` holds the JDK and kotlinx.coroutines targets. Future Java concurrency hooks are in §9.
The [code map](../CLAUDE.md#map) lists the entry points.

The agent is plain Java. Only ASM is shaded. Its bootstrap runtime has no dependencies and encodes protobuf by hand.
Branch-free bytecode patches don't recompute frames, so transformation never loads a class.

Hooks enqueue events and never wait for the writer or a reader.
The writer appends to the file. Each live client independently reads that file from its first byte and follows new frames.
A late or slow client can't stall capture. On shutdown, clients have up to three seconds to receive the end.
[TRACE_FORMAT.md](TRACE_FORMAT.md) specifies ordering, framing, discovery and the live protocol.

coroutree must be the only agent that changes coroutine and thread internals.
Coexistence with kotlinx-coroutines-debug, the IDE debugger's coroutine agent or `DebugProbes.install()` is unsupported.
The agent has no detection or chaining logic.

### 3.1 Execution control (M1.2)

The agent paces itself. The GUI changes settings over the live socket and doesn't acknowledge individual events.
Capture remains independent of GUI speed.

**Sequences and steps**

- Steps share a sequence when the same flow makes them or they happen to the same node.
  A flow includes its synchronous scopes, context changes and `runBlocking` calls, followed through `rethrowsTo`.
  A thread outside a coroutine is also a flow.
- Two coroutines are separate sequences even on one thread. The gate has no global queue or order of turns.
- A step is a hook call that emits at least one event. Its events stay together.
  For example, launch and context changes form one step.
- The release time is `max(last step of flow, last step of node, last step of other node) + interval`.
  Time spent in the program's own blocking or suspension counts toward the interval.
- Pause holds steps. `step(n)` releases n steps to whichever eligible sequences arrive first.
  On a running scope, it first pauses the scope. On a paused scope, permits accumulate.
  Pause and resume clear permits.

**Subtree settings**

The innermost setting along a node's structural ancestry governs it. Otherwise, the global setting applies.
A node's first command copies its governing setting, then changes that copy independently until `inherit` removes it.
Thus, global pause followed by `resume 42` runs that subtree alone.

A step uses the settings of its execution unit and every node it emits on.
It takes the largest interval among unpaused settings and needs a permit from every paused setting.
An outsider that cancels into a paused subtree waits too.
The gate takes permits in a fixed order, global first and then by node id.
It caps intervals at one day to prevent overflow.

**Hook ordering**

Each emitting hook follows this order:

1. Find every node involved and set parent links. Discovery can emit its own step with its own await.
2. Call `Pace.await(ts, unit, node, other)`.
3. Read state, decide, take locks, capture and emit.
4. Call `ThreadState.exitHook()` in `finally`.

`unit` is the execution unit, `node` the event target, and `other` a second target or `null`.
Resume and suspend use the coroutine itself as `unit`, regardless of the thread's current unit stack.
Completion takes the unit before it pops it. The [hook audit](spikes/execution-control.md#hook-audit) lists the await points.

Never wait in `Tracer.emit`: callers can hold agent locks there.
`Tracer.emit` records an internal ERROR when an emitting hook hasn't passed the gate.
Cheap pre-await checks can avoid unnecessary holds, but hooks must check decisions again after release.

**Wait and accounting**

The waiting thread reads volatile settings and sleeps for at most 10 ms, then checks again.
It claims sequence slots with one timestamp through CAS operations and retries on a lost race.
That timestamp becomes the step's first event time, so spacing is exact regardless of hook duration or writer order.
An open gate, with no subtree settings, costs one volatile read.

The wait uses `Thread.sleep`. `LockSupport.parkNanos` can consume a permit needed by the application's own park.
[The spike](spikes/execution-control.md#wait-safety) records the failing sequence and the virtual-thread behavior.
The wait remembers interrupts and restores the flag before return. It never throws into the application.

If a hook emits nothing, it returns its slots and permits to their original counter objects.
Pause and resume replace those objects, so a late return can't reopen a paused scope.
Hold time carries to the thread's next event, preserving exact per-thread totals.

**Safety requirements**

- A held thread depends only on time and settings. It takes no lock, calls no application code and prints nothing.
  A slot can't remain busy until its claimant emits: application `toString()` can block on a monitor owned by another held thread.
- Hold and command paths must be pre-linked. A control reader can't wait for class initialization on a held thread.
  The agent warms up the parser, wait path and socket path at startup. The parser uses no regex.
- Commands use a lock shared only by agent threads and node-finish handling. Nothing blocks under it.
- Holds run with `inHook` set. They cause no blocking, interrupt, unmount or node-state events and don't change blocking depth.
- JVM root-thread-group threads pass unheld. A held Signal Dispatcher inside `Thread.start` can prevent SIGTERM handling and thread dumps.
- Hooks added where a thread can't wait, such as future mount and unmount hooks, must pass unheld.

**Release on failure and shutdown**

- Tracing failure or shutdown releases held threads at the next tick.
- The gate opens when `ApplicationShutdownHooks.runHooks` starts, before shutdown hooks start their threads.
  Opening only in the agent's shutdown hook can deadlock `System.exit`.
- When the last controller disconnects, the gate removes subtree settings and the global setting returns to the configured interval, unpaused.
  A configured paused start remains paused until a control command releases it or fail-open applies.
- A finished node loses its setting. The finish/command race uses volatile publication checks on both sides.
  Finished thread nodes retain ancestry for children that outlive them. Job nodes release their parent.

**Observable limits**

- Pause stops a thread at its next traced event. Computation without events continues.
- A held coroutine occupies its thread, so other coroutines on that thread also wait.
  A subtree with its own threads can run independently.
- Wall-clock time continues. Timeouts can fire on release, and pacing changes interleaving and can hide or expose races.
  Virtual time is M4.
- Another thread can observe a cleared interrupt flag during a hold.
- Thread dumps show agent frames and `TIMED_WAITING`. A held virtual thread unmounts unless pinned.
  Agent sleep can start the JDK's virtual-thread timer thread, which the GUI hides as library infrastructure.

Holds themselves aren't trace events. `heldNanos`, `sameStep` and `PaceDef` record durations, step boundaries and setting changes.
[Execution-control semantics](TRACE_FORMAT.md#semantics) define those fields. Setting markers have no `seq` and approximate placement among events.

**Availability**

The gate exists only when `pace` is enabled and either live capture or a finite configured pace is enabled.
With `pace=false`, `Pace.GATE` is null: no control readers, runtime parent links or execution-control trace fields.
The GUI shows no controls. Pace defaults to enabled, independently of live capture.

Without a live socket, a configured pace still applies. The agent reports a WARNING and ignores `startPaused` because nothing can resume it.
A failed live socket also releases a paused start.
The [live command protocol](TRACE_FORMAT.md#commands) defines command syntax and controller detection.

## 4. Virtual time (M4)

Program time equals wall time minus hold duration. `heldNanos` and `PaceDef` give the recorded inputs.
Virtual time must cover:

- `System.nanoTime` and `currentTimeMillis`, through rewritten project and library call sites. JDK call sites can't be rewritten.
- `Thread.sleep`, `LockSupport.parkNanos/Until` and `Object.wait(timeout)`.
- `ScheduledThreadPoolExecutor` and other `DelayQueue` users.
- Selector and socket timeouts.
- kotlinx.coroutines `delay`, `withTimeout`, event loops and `DefaultExecutor`, through a version-bound `AbstractTimeSource` hook.

Timed waits already in progress must extend by the hold duration.
External time, such as file timestamps and clocks in other processes, can't be virtualized.

## 5. Future work without a milestone

Runtime warning badges will identify:

- Unstructured roots (`GlobalScope`, ad-hoc `Job()`).
- Blocking on `Default` or `Main`.
- Pinned virtual threads.
- A caught `CancellationException` that isn't rethrown.
- Exceptions in never-awaited `Deferred` values.
- Cleared and ignored interrupts.

Badges will appear in a filterable list and won't fail the build.
Runtime `awaits` / `joins` links and the gaps in §12 also have no assigned milestone.

## 6. GUI (Compose Multiplatform Desktop)

The GUI opens recorded traces and finds live JVMs through the session registry.
The graph, details pane and event log share selection.
When an event refers to two nodes, the GUI highlights both nodes and their connecting edges.
Captured stacks and source sites open in the IDE through IntelliJ's localhost endpoint or a configured command template.

Live, paceable sessions have pause, resume, step and a logarithmic speed slider, from one event in ten seconds to unlimited.
Space toggles pause, and → steps. Subtree controls appear in the context menu and details pane.
Controls show the settings read back through `PaceDef`.
A node marker identifies its setting. The trace has no list of held threads.
Recorded traces show setting changes in the event log.

The time scrubber, search and filters are M5. A filter removes nodes from the graph.
The scrubber will inspect history independently of execution controls, including while the program is paused.

### 6.1 The graph

- Roots appear side by side, with children below, in creation order. Position encodes hierarchy. The graph has no indented outline.
- Boxes show a kind pictogram, title and state. Color encodes state. Context and source details appear in the hover card and details pane.
- Structural edges are solid. Children of one parent can share a trunk.
- Cross-link types have distinct colors, dash patterns and legend switches.
  `runsOn` appears only for the selection, from either endpoint.
- Pools and library-origin subtrees with no project code below are hidden by default.
  Selecting a hidden node through the log or details enables the *library / pools* toggle.
- The graph supports no per-node collapse or same-site aggregation. Use pan, zoom, Fit, Selection and the minimap for navigation.
  The minimap appears when the graph exceeds the pane. Zoom ranges from fit to 3×.
- Only visible geometry is drawn. Below 35% zoom or above 700 visible boxes, the canvas replaces box composables.
- Changed layouts animate for 320 ms and anchor the selected node, or the node nearest the viewport center.
  Until manual navigation, the viewport fits the whole graph. Fit restores that mode.
  Above 3000 boxes, changes appear immediately.

The engine uses a tidy-tree layout and orthogonal routing. Geometry is deterministic and has no Compose dependencies.
§12 records the constraints required to preserve separation.

### 6.2 Drawing invariant (strict)

Every settled layout must satisfy these rules:

1. Node boxes don't overlap or touch and have a minimum gap.
2. Lines don't share segments, touch or approach closer than the minimum gap, except for one parent's shared structural trunk.
3. Lines cross only at a single point, at 90°, away from bends. The router minimizes crossings heuristically.
4. Lines touch only their endpoint boxes and maintain clearance from other boxes.
5. Edge labels cover no box, other label or line except their own. Truncated text remains available in the tooltip.

During animation, edges can fade or be rerouted per frame.
The independent geometry checker enforces the settled invariant on corpus, demo and seeded random graphs.
A violation is an engine bug. Don't relax gaps or special-case traces to pass the checker.

## 7. Gradle plugin

The [README configuration](../README.md#using-it-in-a-build) defines the DSL and defaults.
When enabled, the plugin attaches the agent to `JavaExec` and `Test` tasks.
`coroutreeView` resolves a GUI artifact with a host-OS classifier and starts it detached.
Traced JVMs register in the user's session registry unless `sessionsDirectory` points elsewhere (§12).

Configuration precedence, highest first:

1. Inline options on a manually supplied `-javaagent` argument override its properties file.
2. `-Pcoroutree` and `-Pcoroutree.*` override the DSL.
3. DSL values override defaults.

Resolve properties at argument generation through provider chains. The DSL overrides extension conventions, so conventions can’t enforce CLI precedence.
Combined-setting checks use resolved values and must survive configuration-cache reuse.
Invalid values fail the build with the property name, except that `-Pcoroutree` treats anything other than `false` as enabled.
Properties also follow Gradle's precedence for `gradle.properties` and environment sources.

The agent keys are `live`, `pace`, `pace.paused` and `pace.events.per.second`.
GUI commands change runtime pace, never whether the socket or gate exists.

The source index is text, keyed by package and file name, and aggregates project dependencies.
The plugin depends only on Gradle. Its contracts with the agent must change on both sides and pass `AgentConfigTest`.

## 8. Modules

The [code map](../CLAUDE.md#map) lists modules, entry points and build constraints.
The [repository guide](onboarding/index.html#code-map) has a task-based source browser.

## 9. Milestones

| Milestone | Status | Scope |
|---|---|---|
| M1 | Done | Runtime tracing, source navigation, live and recorded input, jobless coroutines and inline source maps |
| M1.1 | Done | Graph, layout invariant, navigation and linked selection |
| M1.2 | Done | Global and subtree execution control |
| Standalone desktop GUI | In progress | Standalone GUI workflow |

Remaining milestones, in priority order:

| Milestone | Scope |
|---|---|
| M2 | Flow operators, including `flowOn`, `shareIn`, `stateIn` and `*Latest` cancellation. Channel `send`, `receive`, `select` and links. Bridges and custom context elements |
| M3 | Task lifetimes and pool ownership for executors, ForkJoinPool and CompletableFuture async stages |
| M4 | Virtual clocks and timed waits (§4) |
| M5 | Time scrubber, search by name, file and exception type. Filters by node kind, event kind, thread, dispatcher, origin and time range |
| M6 | StructuredTaskScope lifecycle and ownership, ScopedValue bindings, virtual-thread mount, unmount and pinning |

## 10. Testing **[assumed]**

The suite covers golden trees from real-agent traces, plugin TestKit contracts, and model and GUI behavior.
`CorpusTest` requires every event kind to occur and checks version-sensitive samples on kotlinx.coroutines 1.9, 1.10 and 1.11.

Execution-control tests must cover:

| Boundary | Required evidence |
|---|---|
| Invisible holds | Paced and stepped corpus matches unpaced goldens, with no extra blocking, interrupts or nodes |
| Exact steps | A paused trace doesn't grow. `step 3` adds three steps. Sequence spacing meets the interval without tolerance |
| Parallelism | Parallel threads and coroutines on one thread aren't serialized by the gate |
| Subtrees | Paused targets receive no events. Independent siblings continue. Inbound cancellation waits. `inherit` and node completion remove overrides |
| State | Interrupt flags and park permits survive holds on platform and virtual threads. Blocking depth returns to zero |
| Failure | Controller loss, writer failure, SIGTERM and `System.exit` release paused threads |
| Configuration | CLI overrides DSL in both directions, including cache reuse. Inline agent options override the file |
| No live socket | Paused start warns and runs. A configured finite pace still applies |
| Disabled gate | No gate instance, control thread, `PaceDef`, `heldNanos` or `sameStep` data |

`PacedCorpusTest.WALL_CLOCK_SENSITIVE` names samples whose goldens depend on time margins and gives the reason.
These use short pacing intervals and pre-granted step permits. Other samples step one round trip at a time.
New hooks must identify their await point and pass the corpus checks.

Graph tests check §6.2 on real traces, the demo and seeded random forests, including deep chains, wide fan-outs and dense links.
The checker has independent legal and illegal geometry cases. Screenshots require human review for readability.

## 11. Risks and open questions

1. License headers and a publishing pipeline remain open.
2. Preview APIs such as `StructuredTaskScope` require `--enable-preview`. Pin the JDK toolchain. Accepted.
3. Other coroutine agents can conflict (§3). Accepted.
4. Several constructs on one line share a source site. Compiler SMAP and marker conventions can change.
5. kotlinx.coroutines internals require version-specific hooks. Missing required targets cause ERROR diagnostics.
   Versions outside the tested range cause WARNING diagnostics.
6. Virtual time needs every supported clock and wait path. An uninstrumented path advances during a hold.
7. Monitor contention requires native support. The [monitor spike](spikes/monitor-contention.md) selected JVMTI for exact sequence ordering.
   Gradle loads it with `-agentpath:`. Manual attachment uses `System.load`, which warns on JDK 24 and later.
8. Source navigation requires the checkout on the viewer's machine. Accepted.
9. Wide fan-outs and dense links make large graphs. Accepted. Layout reruns on structural graph changes and must support 10⁴ nodes.
10. New hook locations must support the gate's safety requirements (§3.1). Locations where a thread can't wait must pass unheld.
11. Pacing changes timing and interleaving. Shared-thread coroutines can't be paused independently. Accepted. Virtual clocks are M4.

## 12. M1 as built

### Runtime semantics

| Case | Rule |
|---|---|
| Parentless `runBlocking` | Child of the execution unit that called it. Other parentless jobs are roots with a creator link |
| Names | Only an explicitly assigned `CoroutineName` titles a node. Inherited names remain in context |
| Exceptions | Scopes and `runBlocking` propagate to the caller. Don't report the caller's rethrow as another throw |
| Child-to-parent failure | Report `ExceptionPropagated` for this transition |
| Catches | Typed catch blocks are instrumented only in project classes |
| Blocking | Report only the outermost blocking call per execution unit. Suppress library idle-worker and event-loop parks |
| I/O | Socket read, accept and connect, `Process.waitFor`, standard input. File I/O isn't traced |
| Jobless coroutines | Key by root frame. One completion object can serve several coroutines. `suspend fun main` belongs to its thread |
| Jobless failure | A terminating `CancellationException` means failure. Restricted generators aren't traced |
| Shared drivers | A driver that runs a chain of interceptors on one continuation (Ktor's `SuspendFunctionGun`) is no node. Its suspensions belong to the coroutine whose context it reports. Sample: `SharedDriver` |
| Source maps | Every stack passes through `SourceMaps`. Project markers add names and intermediate inline frames beyond SMAP data |
| Monitor callbacks | Nesting requires a depth counter and a matching exit even with a pending exception |

### M1.1 as built: the graph

The constraints below preserve the drawing invariant. Implementation entry points are in the [code map](../CLAUDE.md#map).

- Box centers use multiples of `COLUMN = 18`. Minimum line spacing is `UNIT = 6`.
  Cross-link ports and gap tracks use offsets +6 on even layers and +12 on odd layers.
  New vertical segments must use these coordinate classes, and box movement must use whole columns.
- Each horizontal segment gets its y coordinate from the channel's `TrackProfile`.
  Segments can share a track only when their horizontal ranges have sufficient separation.
- Gap choices depend on forest order, which must remain stable when the router widens gaps and layout runs again.
  Boxes grow when their links need more ports.
- The checker uses shapes independently of router data. Clearances are 24 between boxes, 6 between lines,
  8 from lines to boxes and 2 around labels. Crossings stay 6 from bends and endpoints.
- Only structural changes to `GraphModel` trigger layout. Keep per-event state outside `GraphNode`.
  The engine isn't incremental. The performance test allows three seconds for 10⁴ nodes and 2500 cross-links.

Labels use the longest horizontal segment with room. Below 28 units, the label is omitted and the legend identifies the link.
The labels toggle defaults to on. `TreeRenderer` remains the golden text format.

### M1.2 as built: execution control

§3.1 contains the implemented rules. The [execution-control spike](spikes/execution-control.md) records the wait-safety evidence and hook audit.
`PaceControl` drives the real-JVM control tests. `--demo=live` uses `DemoGate` to exercise the GUI without a traced JVM.

### The standalone GUI as built: sessions

- The session registry is `~/.coroutree/sessions`, one descriptor per traced JVM, the same directory for every build
  of the user. It replaced `build/coroutree/sessions/<buildId>`: the GUI is standalone and lists what runs anywhere.
  The agent's `sessions.dir` option and the DSL's `sessionsDirectory` move it; empty, the agent writes no descriptor.
  Test JVMs always set it.
- A descriptor is written once the live server listens, also without one (`live=false`, or `liveFailed` when the
  socket could not be opened), and rewritten with `ended` on orderly shutdown.
- The GUI polls the directory. A descriptor without `ended` is believed only as far as the JVM exists (pid, and its
  start time within a minute of the agent's) and its socket answers: `RUNNING`; `UNREACHABLE` while the socket doesn't
  answer (shutdown closes it before the descriptor is rewritten); `CRASHED` when the process is gone. Ended, crashed
  and unreachable runs are offered as recorded traces while their file exists.
- The GUI prunes on entering the session list: runs whose trace is gone, and all but the newest 50 of the rest.
  A JVM that exists is never pruned.
- A session that isn't over and can't be connected to is read from its file and shown as `partial`.
- Disconnect hangs up and keeps what was seen; to the agent it is one controller fewer. Reconnect replays the trace
  from its first byte into a new feed; the view keeps selection and viewport, and shows the new feed only once it has
  caught up with what was on screen.

### Future work without a milestone

- Jobless coroutines started with `startCoroutineUninterceptedOrReturn` and their own completion can remain unseen.
  The function is inline, so it has no single hook point.
- A jobless coroutine sees the suspensions inside a shared driver only when the driver is a `CoroutineStackFrame` whose
  `callerFrame` leads out of the chain. `joblessNode` stops at the first match. Ktor's driver returns one saved caller
  for each read, so the walk ends half done and the next read gets `null`. A driver that returns the top of its stack
  sends the walk in a circle. The sample's driver returns the outermost caller.
- Intermediate inline frames and function names require compiler marker conventions in project classes.
  Classes without local variable tables have only the two SMAP frames and no function names.
- The native probe builds for the host only, with a universal binary on macOS. Linux needs `cc`. Windows has no build script.
  Releases must combine platforms with `-Pcoroutree.prebuiltNatives=<dir>`. Only macOS arm64 has run end to end.
  A missing or unloadable binary causes a diagnostic, and capture continues without monitor contention.
- Only the first copy of kotlinx.coroutines in a JVM is traced. Copies in other class loaders are ignored.
- Recorded files are read once. Follow a running JVM through the live socket.
- The plugin reads the root project's build directory from each project, which prevents project isolation.
- Dependency source indexes use conventional runtime-classpath names. A custom KMP target such as `jvm("desktop")` lacks dependency sources.
- Windows source paths, launcher quoting and `idea.cmd` are untested.
- Nothing is published publicly. `publishAllPublicationsToLocalRepository` writes artifacts to `build/repo`.
- Subtree pace has no exact numeric input. Its slider has two significant digits.
- Subtree step controls need an explanation that cross-subtree events require permits from every governing paused setting.
- Runtime warning badges remain in §5. An incremental layout engine is unassigned if support for 10⁵ nodes becomes necessary.
