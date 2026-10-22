# Spike: execution control (M1.2, DESIGN §3.1)

The implemented [execution-control rules](../DESIGN.md#31-execution-control-m12) include the results below.
This spike records the wait-safety evidence and hook audit.

## Wait safety

`LockSupport.parkNanos` consumes the thread's park permit. Inside a hook for the application's own `park()`, this can hang:

1. B calls `unpark(A)`, which gives A a permit.
2. A enters its `park()` hook and waits at the gate. The first `parkNanos` consumes the permit.
3. The gate releases A. The real `park()` blocks with no remaining caller to unpark A.

So the gate uses `Thread.sleep`:

- Platform sleep doesn't consume the park permit.
- Virtual-thread sleep parks internally, then sets the permit unconditionally. A later park can return spuriously, which `LockSupport` permits.

The wait clears and remembers interrupts, then restores the flag before return.
Recursive sleep and interrupt hooks return at `inHook`, before they change blocking depth.
Thread dumps and JFR can still show the sleep. The sleep can also start the JDK's virtual-thread timer thread.

## Hook audit

Every emitting hook must find all nodes first, then await, then read state, decide, lock, capture and emit.
Discovery can emit its own step, so it must finish before the enclosing hook's await.
Parent links must exist before await so a new child inherits a paused subtree's setting.

| Await point | Additional constraint |
|---|---|
| `createJobNode`, `discover`, `threadNode`, `poolOf` | Await before definition or discovery locks |
| `continuationCreated` | Capture the stack first because the construct determines the parent link |
| `continuationCompleted` | Use the jobless coroutine's execution unit. Emits on the caller too, as `other` |
| `coroutineResumed`, `coroutineSuspended` | Use the coroutine itself, regardless of the thread's unit stack |
| `cancelRequested`, `parentCancelled`, `cancelling` | Resolve every target first |
| `completing`, `completed` | Capture the unit before it is popped. Completion can emit on the caller too |
| `childCancelled`, `childCancelledResult`, `exceptionReachedHandler`, `exceptionCaught` | Await before state decisions |
| `threadStart`, `threadExit`, `threadInterrupt`, `threadUncaught` | `Thread.start` can run while the starter owns the target's monitor |
| `blockEnter`, `blockExit` | Include JVMTI callbacks. `MonitorContendedEntered` owns the acquired monitor |

`Tracer.emit` reports an internal ERROR if an emitting hook hasn't passed the gate.
`shutdownBegins` emits nothing and opens the gate immediately.

## Evidence

The corpus ran against the unpaced goldens in two modes: a 5 ms interval per sequence, and paused with a test controller.
**30 of 34 runs matched, with no hang.** The runs included:

- `VirtualThreads`.
- `MonitorContention`, with holds inside JVMTI callbacks.
- `BlockingInCoroutine`.
- `DispatcherThreads`.

Sequence spacing met the interval with no tolerance. Every event passed the gate.

Four runs differed because holds exceeded the samples' timing margins:

- Paced: `Cancellation`.
- Stepped: `Cancellation`, `ContextAndDispatchers`, `ScopeEdgeCases`.

One hold occurred inside `delay` when it started the library timer thread. The timer resumed the coroutine before it suspended.
`PacedCorpusTest.WALL_CLOCK_SENSITIVE` records affected samples and reasons, including short delays and sleep-ordered threads.
These samples use shorter pacing intervals and pre-granted step permits.

Later tests checked shutdown release, JVM-thread exemptions and nested monitor callbacks.
The [test requirements](../DESIGN.md#10-testing-assumed) cover these cases.
