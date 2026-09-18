# Spike: execution control (M1.2, DESIGN §3.1)

Status: **done; the gate was built on it.** This file records what was decided while auditing the hooks for
`Pace.await`, before and during the spike, and the evidence. Decisions that refine or depart from DESIGN §3.1 are marked
**[departs]**; DESIGN §12 "M1.2 as built" has them in their final form.

## 1. The wait is `Thread.sleep`, not `LockSupport.parkNanos` [departs]

§3.1 says a held thread loops on volatile reads and its own `parkNanos` tick. `parkNanos` **consumes the thread's park
permit**. `LockSupport.park` is hooked (`blockEnter(PARK)` at its start), so this sequence hangs the program:

1. thread B calls `unpark(A)` — A now has a permit;
2. A calls `park()`, the hook holds A at the gate, the first `parkNanos` tick returns at once and eats the permit;
3. A is released, the real `park()` blocks, and nobody will ever unpark A again.

That is a state disturbance of the worst kind (a hang we caused), so the tick is `Thread.sleep(millis, nanos)`:

- platform thread: sleep waits on the thread's own sleep event and does not touch the park permit;
- virtual thread: `VirtualThread.sleepNanos` parks internally and then **sets the permit unconditionally**
  ("may have been unparked while sleeping"): the worst case is one spurious return from a later `park()`, which
  `LockSupport`'s contract allows and every correct caller loops over. Never a lost wakeup.

Interrupts as designed, adapted: `Thread.interrupted()` clears and remembers the flag before each sleep, an
`InterruptedException` out of the sleep is remembered as well, and the flag is set again before the hook returns
(`Thread.interrupt()` is hooked; `inHook` is set, so the restore is not a `THREAD_INTERRUPTED`). The recursive hooks of
`Thread.sleep` / `parkNanos` return at the `inHook` check, before `blockDepth` is touched, enter and exit alike.

Still visible and accepted: held thread is `TIMED_WAITING`, agent frames in a dump, JFR sees `jdk.ThreadSleep`. New,
also accepted: the JDK's timer thread for virtual threads may be started by *our* sleep (on JDK 24+ from the carrier, in
`afterYield`, outside any hook, so it cannot be suppressed). It is library infrastructure: hidden in the GUI by default,
never in a golden.

## 2. What `await` is given, and what it claims

`Pace.await(ts, unit, node, other)`:

- `unit` — the execution unit the step happens **in** (`ts.currentUnit()`, else the thread's node). For *resumed* and
  *suspended* it is the coroutine itself: at that moment it is not (or no longer) on the thread's unit stack, and if the
  thread stood in for it, the ten children of a single-threaded `runBlocking` would be spaced against each other through
  the flow rule — exactly what "concurrent counts as parallel" forbids. For *completing* / *completed* it is taken
  before the unit is popped.
- `node` — the node the step happens **to**; `other` — a second node the same hook call emits on (today only
  `completed`: *propagated to the caller*), else `null`.
- slots claimed: `unit.flow()`, `node`, `other` (distinct ones); governing scopes: innermost scope of `unit`, of `node`,
  of `other`. Stricter wins: the interval is the maximum over the scopes that are not paused; every paused scope must
  give a permit. Scopes are asked in a fixed order (global first, then by node id) so two steps never hold one permit
  each and wait for the other's.
- all slots are claimed **with one timestamp** (CAS each from the value the release time was computed from; roll back on
  a lost race and look again). The first event of the step takes that timestamp as its `timeNanos` ("taken at release",
  §3.1), later events of the step the real time. So any two steps that share a slot are at least the interval apart
  **exactly**, by construction, with no tolerance — whatever order they reach the writer in. The alternative (stamp at
  emit, advance the slot afterwards) is off by the previous hook's own run time, which is unbounded (first stack capture
  loads classes).
- **No "busy" marker** on a slot between claim and emit, although it would be simpler: it would make a waiter depend on
  the claimer's progress, and the claimer runs application code (`toString` via `Describe`) that can block on a monitor
  the waiter owns. A waiter must depend on time only.
- a hook that claimed and then emitted nothing gives back slots (CAS back) and permits (to the counter object it took
  them from; pause / resume replace the object, so a late give-back cannot reopen a closed gate). Done in
  `ThreadState.exitHook()`, which replaces `ts.inHook = false` in every hook. `heldNanos` of a hold that led to no event
  is carried to the thread's next event: the sum per thread stays true (M6 needs that).
- cheap peeks before the await avoid holding a thread for nothing (probe of a frame of a coroutine already running
  here, nested blocking call, suspend already reported late, job already finished); the decision proper is made again
  after the hold. Only arguments and thread-confined state are read before the await, plus those peeks.

## 3. Rules every hook follows (the audit)

1. **Find everybody first.** `threadNode(ts)`, `nodeOf(...)`, `poolOf(...)` may emit a `DISCOVERED` / pool `LAUNCHED`:
   a step of their own with their own `await` (before their lock / `defined` decision). They must all run *before* the
   hook's own `await`, or the second `await` would settle the first claim before its events exist.
2. `await`, then read state, decide, lock, capture, emit. Parent links (`paceParent`, `caller`) are set before the
   `await` so that a new child of a paused subtree is held at its own *launched*.
3. `Tracer.emit` checks that an `await` happened in this hook call; if not it records an internal ERROR, which fails
   every golden test. That is how the audit stays enforced for later hooks.
4. Events after the first of one step carry `sameStep` (new, written only when the gate exists): a step is visible in
   the trace, `step 3` can be tested as *exactly three steps*, and the GUI can tell what one press of → did.

Await points: `createJobNode` (after parent / thread lookup, before definition), `discover`, `continuationCreated`
(after the stack capture — the construct decides the parent link; a stack does not change during a hold),
`continuationCompleted`, `coroutineResumed`, `coroutineSuspended`, `cancelRequested`, `parentCancelled`, `cancelling`,
`completing`, `completed`, `childCancelled`, `childCancelledResult`, `exceptionReachedHandler`, `exceptionCaught`,
`threadStart` (inside `Thread.start`, the starter may own the `Thread`'s monitor), `threadExit`, `threadInterrupt`,
`threadUncaught`, `threadNode` discovery, `poolOf`, `blockEnter` (also from the JVMTI `MonitorContendedEnter` callback),
`blockExit` (also `MonitorContendedEntered`: held while owning the monitor just acquired).

## 4. Settings

- A scope is `{intervalNanos, paused, permits}`; the global one always exists, a node gets one with its first command
  and it starts as a **copy of what governs the node at that moment**, then independent until `inherit`. So "pause
  everything, then `resume 42`" runs one subtree alone, and `PaceDef` always carries a complete setting.
- `step n` on a scope that is not paused **pauses it and grants n** (a → press in a running session means "stop after
  one more"); on a paused scope permits add up (five quick presses are five steps). `pause` / `resume` zero the permits.
- Intervals are clamped to one day: `last + interval` must not overflow.
- A client becomes a *controller* with its first well-formed command (unknown node included). When the last one leaves:
  node settings dropped, global back to the configured interval, unpaused, `PaceDef` reason fail-open each.
- A node that finishes loses its setting (`PaceDef` dropped, reason **node-finished** — a fifth reason, added). Set /
  finish race closed Dekker-style: the hook sets `finished` then reads `scope`; the command publishes `scope` then
  reads `finished`; both volatile.
- Commands are applied under a lock that only agent threads and `nodeFinished` take, never a held thread, and nothing
  blocks under it. Held threads read volatiles only.
- `open` (one volatile read) = global unpaused and unlimited **and** no node carries a setting.
- Gate exists iff `pace` and (`live` or a configured pace). `pace.paused` without live: WARNING, runs. Live requested
  but the server socket failed: same.
- Agent threads must not be blockable by a held thread either (a control reader waiting for a class whose initializer
  is parked at the gate could never deliver `resume`): no regex, no first-time class loading on the command path —
  the parser and the socket path are warmed up at start (a throw-away loopback connection), as is the hold path.

## 5. Wire additions

`TraceHeader.paceable = 11`; `Event.held_nanos = 15`, `Event.same_step = 16`; `Frame.pace = 5`:
`PaceDef { time_nanos=1; after_seq=2; scope_node_id=3; interval_nanos=4; paused=5; steps=6; reason=7; dropped=8 }`,
reasons CONFIG=1, CONTROLLER=2, FAIL_OPEN=3, SHUTDOWN=4, NODE_FINISHED=5. `after_seq` is the latest `seq` handed out
when the setting changed: a `PaceDef` takes no `seq`, this places its marker in the event log. Session descriptor:
`"paceable"`. With `pace=false` none of it is written.

## Evidence

First run of the spike (`Pace` + the audited `await` in every hook, nothing else built), the whole corpus twice over —
paced at 5 ms per sequence, and started paused and stepped to the end by a test controller — against the unpaced goldens:
**30 of 34 runs identical, no hang**, among them `VirtualThreads`, `MonitorContention` (held inside the JVMTI callbacks),
`BlockingInCoroutine` and `DispatcherThreads`, and no event that had not passed the gate. The distance check (any two
steps of one flow or on one node at least the interval apart) held with no tolerance.

The four that differed were not the gate's doing but the design's accepted "time is not held": `Cancellation` paced, and
`Cancellation`, `ContextAndDispatchers`, `ScopeEdgeCases` stepped. In each the program is held for longer than a margin
it relies on (`delay(10)`, `delay(20)`), most instructively inside `delay` itself: the first `delay` outside an event loop
starts the library's timer thread, that `Thread.start` is a step, and held there for 47 ms the coroutine found itself
resumed before it had suspended — so it never suspended, truthfully. §10 foresaw it; such samples are listed by name and
paced far below their margins (`PacedCorpusTest.WALL_CLOCK_SENSITIVE`). Later reruns singled out more of the same kind
(`DispatcherThreads`' `delay(5)`, `VirtualThreads`' `sleep(50)`), after which the corpus was classified as a whole rather
than flake by flake.

What the tests built afterwards found in the gate itself, all fixed, all in DESIGN §12: the Signal Dispatcher held at a
paused gate (no Ctrl-C), `System.exit` from a paused program, and — not the gate's — the nesting bug of the native
monitor probe.
