# Spike: monitor contention detection (DESIGN.md §11.7)

**Decision: JVMTI.** Bytecode instrumentation can't observe a thread blocked on `synchronized`.
JVMTI callbacks run on the contending thread and take a global sequence number at the contention.
JFR events arrive too late for that order. Support would require live-view delays or trace-pipeline changes.

The [spike code](../../spikes/monitor-contention/) includes `run.sh` to reproduce the measurement.
Measurements used an Apple M4 Max, macOS 26 and Zulu JDK 26.0.2.

## Method

Four threads contend for one monitor. Each does:

1. 50 holds of 2 ms.
2. 5000 holds of 20 µs.
3. 50 rounds of `wait`/`notifyAll`.

The workload records timestamps and sequence numbers immediately before each monitor request and after each acquisition.
A probe report is correctly ordered if it falls inside that bracket.

| Backend | Configuration |
|---|---|
| JFR | In-process `RecordingStream`, `JavaMonitorEnter` and `JavaMonitorWait`, threshold 0, stacks enabled. Timestamps mapped to `nanoTime` with a sampled offset |
| JVMTI | `MonitorContendedEnter/Entered` and `MonitorWait/Waited` callbacks into Java through JNI, on the contending thread |

## Results

| Measure | JFR | JVMTI |
|---|---|---|
| Delivery latency | Median 0.6 to 0.7 s, maximum 1.03 s after contention ended. No public flush-interval setting | Synchronous callback |
| Ordering | 3079 of 3087 reports inside the bracket over three runs. Overshoot up to 134 µs. Clock drift 2 to 4 µs per second | 15,226 of 15,226 callbacks inside the bracket over nine runs |
| Short contentions | Threshold 0 captured every acquisition that waited at least 10 µs in the representative run. Shortest report under 1 µs | Same contention set |
| Stacks | Present on every event | `StackWalker` reaches the `synchronized` frame |
| Runtime at about 1300 contentions/s | 1057 to 1064 ms, baseline 1057 ms | 1057 to 1069 ms, with or without stacks |
| Distribution | Requires `jdk.jfr` | About 17 KB per native library |

Both backends observe the VM's slow path after a failed spin. Neither observes contention resolved through spins.
Wait/notify events add no coverage because bytecode hooks already capture `Object.wait`.
Virtual-thread pinning remains separate M6 work. From JDK 24, `synchronized` doesn't pin.

## Integration constraints

JVMTI reports contention through the existing `THREAD_BLOCKED` event with reason `MONITOR`.
The model and GUI need no backend-specific behavior.

- Callbacks can run on any JVM monitor, including during class loading. Java callback paths must be pre-linked and reentrancy-safe.
- A lambda bootstrap in the spike reentered the callback through a contended JDK monitor. Hook paths prohibit lambdas and `invokedynamic`.
- The plugin extracts the bundled probe and passes `-agentpath:`. Manual attachment falls back to `System.load`, which warns on JDK 24 and later.
- Without a usable binary, the agent reports a diagnostic and continues without monitor-contention events.

The [platform gaps](../DESIGN.md#future-work-without-a-milestone) describe native release coverage.
This spike didn't test higher contention rates, many monitors, Windows or Linux, or a library downloaded through Maven on macOS.
GraalVM native images are out of scope because coroutree requires a JVM.
