# Spike: detecting monitor contention (DESIGN.md §11.7)

Bytecode instrumentation cannot see a thread blocking on `synchronized`. The design asked for both candidate
backends to be prototyped in M1 and judged on evidence. They were; the code is in `spikes/monitor-contention/`
(`./run.sh` builds and runs everything), the numbers below are from an Apple M4 Max, macOS 26, Zulu JDK 26.0.2.

**Decision: JVMTI** (taken after this spike, and implemented: `coroutree-agent/src/native/coroutree_monitor.c`), with no
backend at all on platforms there is no binary for. Reasons are in the table.

One correction the implementation brought: the spike showed that `System.load` from a running JVM *works*, and
recommended it over `-agentpath`. It does work, but JDK 24+ prints a native-access warning for it and announces that it
will be blocked by default in a future release. So the Gradle plugin unpacks the library and passes `-agentpath:` —
the probe then waits for the Java agent's `MonitorProbe` class and registers its native method on it — and
`System.load` is only the fallback for an agent that is attached by hand.

## What was measured

`Workload`: 4 threads fight over one monitor — 50 × 2 ms holds each (every waiter parks), then 5000 × 20 µs holds each
(contention that lasts microseconds), then 50 rounds of `wait`/`notifyAll`. The program records the truth about each
acquisition the way `Hooks` would: `System.nanoTime()` and a global sequence number right before asking for the
monitor and right after getting it. A probe's report of a contention is *correctly ordered* if it falls inside that
bracket.

- **(a) JFR** — `RecordingStream` in the same JVM, `jdk.JavaMonitorEnter` and `jdk.JavaMonitorWait` with threshold 0
  and stack traces. Timestamps are mapped to the `nanoTime` clock with an offset sampled before the run.
- **(b) JVMTI** — 100 lines of C: `MonitorContendedEnter/Entered`, `MonitorWait/Waited` callbacks, each calling a
  static Java method through JNI on the contending thread (where the real agent would call
  `Hooks.blockEnter(MONITOR)` / `blockExit()`), which takes the next global sequence number.

## Results

| Question (§11.7) | (a) JFR streaming | (b) JVMTI |
|---|---|---|
| **Latency** | Events reach the consumer in batches: median 0.6–0.7 s, max 1.03 s after the contention ended; the last ones 0.45 s after the workload finished. JFR flushes once a second and `RecordingStream` has no public knob for it. | None: the callback runs on the contending thread at the moment of contention. |
| **Ordering against the global sequence** | By timestamp only. 99.7 % of events (3079 of 3087 over three runs) lie inside the program's own bracket; the rest overshoot by up to 134 µs. Clock offset drift over a 1 s run: 2–4 µs. So an event can be misplaced relative to events of *other* threads that are closer than ~0.1 ms. | Exact: 100 % of callbacks (15 226 over nine runs) took a sequence number inside the bracket. It is the same mechanism as every other hook. |
| **What late, timestamp-ordered events cost the rest of the system** | The trace is a dense sequence that readers restore exactly (`TraceStore`). Events that show up a second late with no sequence number mean either the writer holds *everything* back for > 1 s to merge them (the live view lags by that much), or the format and the GUI learn about events inserted into the past. | Nothing. |
| **Completeness for short contentions** | Complete with threshold 0: one event per acquisition that the program saw waiting ≥ 10 µs (1346 vs 1346 in the run quoted below); shortest reported contention < 1 µs. Both backends are fed from the same slow path in the VM, after spinning has failed — contention that is resolved by spinning is invisible to both, and is not blocking. | Same set of contentions. |
| **Stacks** | Yes, recorded by JFR (100 % of events). | Yes: `StackWalker` works inside the callback and sees the frame with the `synchronized` block. |
| **Overhead** | Not measurable at ~1300 contentions/s (1057 ms baseline vs 1057–1064 ms). | Not measurable (1057–1069 ms), with or without stack capture. |
| **Wait/notify** | `jdk.JavaMonitorWait`, same latency. Already covered by bytecode (`Object.wait`). | `MonitorWait/Waited`. Already covered by bytecode. |
| **Virtual thread pinning** | `jdk.VirtualThreadPinned`. | No such event. Not needed either way: the pinned path goes through `VirtualThread.parkOnCarrierThread`, which bytecode instrumentation can hook (M2). Since JDK 24 `synchronized` does not pin. |
| **Shipping** | Nothing to ship. Needs the `jdk.jfr` module in the runtime image. | A ~17 KB native library per platform: macOS/Linux × x64/arm64, Windows x64 — five binaries, cross-compiled in CI (e.g. `zig cc`), bundled in the agent jar, unpacked next to the runtime jar. No `-agentpath`: **`System.load` from a running JVM works** — `can_generate_monitor_events` can still be acquired in the live phase (verified, same results as `-agentpath`). Not verified: loading a copy that came through Maven on macOS (the locally linked library carries the linker's ad-hoc signature; files fetched by Gradle get no quarantine attribute, so it should). |
| **Hazards** | In-process JFR consumer threads are more agent threads to keep out of the picture. Interferes little with a user's own JFR use (recordings stack). | The callbacks fire for every monitor in the JVM, including the JDK's own, on threads in the middle of anything. Java code called from them must be pre-linked, must not block on a monitor and must guard against re-entrancy. The first version of the spike's sink used a lambda; its bootstrap contended on a JDK monitor, re-entered the sink and failed. `Hooks` is already written to exactly this discipline (no lambdas, no indy string concatenation, `inHook` guard), because it runs inside `LockSupport.park` and `Object.wait`. |

Representative output (`SPIKE_ARGS="4 50 5000 50"`):

```
mode=baseline elapsed=1057.6 ms acquisitions=20200
mode=jfr elapsed=1056.6 ms acquisitions=20200
JavaMonitorEnter events=1350, JavaMonitorWait events=110 (wait rounds=50)
with stack trace: 1350 of 1350
delivery latency ms: min=22.0 median=661.0 p99=1027.9 max=1032.9; last event arrived 463.2 ms after the workload ended
events whose interval lies inside the program's own [asked, got] bracket: 1346 of 1350
overshoot outside the bracket, ns: median=0 p90=0 p99=0 max=70020
acquisitions that waited >= 10 µs: 1346

mode=jvmti(System.load)+stacks elapsed=1058.3 ms acquisitions=20200
on the workload's monitor: MonitorContendedEnter=1229 MonitorContendedEntered=1229; on other monitors (JDK internals): 6 events
sample top frame: spike.Workload.lambda$contend$0:58
callbacks whose sequence number lies inside the program's own (before, after) bracket: 2458 of 2458
acquisitions that waited >= 10 µs: 1228
```

## Reading of the evidence

JFR's data is good — complete, cheap, with stacks — but it arrives a second late and outside the sequence, and the
whole trace pipeline (writer, format, live stream, `TraceStore`, GUI) is built on a dense historical order. Making
room for JFR would change all of them. JVMTI gives the same contentions through the front door: a `THREAD_BLOCKED`
with reason `MONITOR` that is indistinguishable, downstream, from a `park` or a `sleep`. The `ThreadBlocked` event
shape stays backend-agnostic as the design requires; nothing in the model or the GUI changes.

The price is a native build. It is a small one (one C file with no dependencies beyond `jvmti.h`), and failure is
soft: where the library is missing or fails to load, the agent reports a diagnostic and everything except the
`MONITOR` reason works, which is the state of M1 today.

Not tried: contention rates far above the ~10³/s measured here, or contention spread over many monitors; Windows and
Linux builds; GraalVM native images (no JVMTI, no JFR
streaming — out of scope, the agent needs a JVM anyway).
