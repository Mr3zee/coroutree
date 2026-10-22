# coroutree trace format, version 1

Recorded files (`*.ctrace`) and live sockets carry the same bytes. Two independent implementations share the format:

- The writer in the agent's bootstrap runtime, Java without dependencies: `kotlinx.coroutree.runtime.TraceEncoder`
  and `Wire`.
- The model, Kotlin with kotlinx.serialization: `coroutree-model`, `Trace.kt` (`TraceReader`, `TraceWriter`).

Integration tests decode real-agent traces with the model. `CorpusTest` requires every event kind to occur.
Without an execution-control gate, the agent omits `Frame.pace`, `TraceHeader.paceable`, `Event.held_nanos` and `Event.same_step`.

## Layout

```
trace  := magic frame*
magic  := "COROTREE"                      8 bytes, ASCII
frame  := length message                  length: base-128 varint, number of bytes of message
message:= a protobuf-encoded Frame
```

Rules of the stream:

- The first frame holds the `header`.
- A reader treats an incomplete final frame as the end of the trace. This preserves readable data after a writer crash.
- Writers omit Proto3 zero defaults. `0` means "none" for node and frame ids and "unknown" for line numbers.
  Every enum starts with `UNSPECIFIED = 0`.
- A reader must skip unknown fields and read an unknown enum number as `UNSPECIFIED`, so the format can grow in one version.

## Schema

```proto
syntax = "proto3";

message Frame {                       // exactly one field is set
  TraceHeader   header      = 1;
  Event         event       = 2;
  StackFrameDef stack_frame = 3;
  Diagnostic    diagnostic  = 4;
  PaceDef       pace        = 5;   // a setting of the agent's gate, see "Execution control"
}

message TraceHeader {
  int32       format_version          = 1;   // 1
  string      agent_version           = 2;
  string      build_id                = 3;   // one for each Gradle invocation; groups the traces of its JVMs
  string      task_path               = 4;   // ":app:run"; empty when the agent was attached by hand
  JvmInfo     jvm                     = 5;
  SourceIndex source_index            = 6;
  repeated string include_packages    = 7;   // prefixes of project code; empty = everything that is not a runtime
  repeated string exclude_packages    = 8;
  int64       started_at_epoch_millis = 9;
  string      project_dir             = 10;  // root project directory, absolute
  bool        paceable                = 11;  // the JVM has a gate: the agent can slow it down, pause it and step it
}

message JvmInfo {
  int64  pid = 1;  string java_version = 2;  string vm_name = 3;  string vm_version = 4;
  string command = 5;                        // sun.java.command
}

// A stack frame has its class and the bare name of its source file. Kotlin and Java both put a class into the
// package that its file declares, so (package of the class, file name) finds the file.
message SourceIndex  { repeated SourceModule modules = 1; }
message SourceModule { string path = 1;  string root_dir = 2;  repeated SourceFile files = 3; }   // ":app", absolute dir
message SourceFile   { string package_name = 1;  string file_name = 2;  string path = 3; }        // path relative to root_dir

// Stack frames are interned. A definition precedes the first event that refers to it. Ids start at 1 and are dense.
message StackFrameDef {
  int32 id = 1;  string class_name = 2;  string method_name = 3;  string file_name = 4;  int32 line = 5;
  bool inlined = 6;                          // the body of an inline function, see "Inlined code"
}

message Diagnostic {                         // a message of the agent about the capture itself
  enum Severity { UNSPECIFIED = 0; INFO = 1; WARNING = 2; ERROR = 3; }
  Severity severity = 1;  string message = 2;
}

message Event {
  int64     seq           = 1;   // global order across all threads: starts at 1, dense, strictly increasing
  int64     time_nanos    = 2;   // since the trace started (System.nanoTime)
  int64     node_id       = 3;   // the node the event happens to
  EventKind kind          = 4;
  int64     thread_id     = 5;   // node id of the thread that the event was captured on
  repeated int32 stack    = 6 [packed = true];   // frame ids, innermost first
  NodeInfo  node          = 7;   // LAUNCHED, DISCOVERED: definition of node_id
  int64     other_node_id = 8;   // where it came from or who did it, see "Two-node events"
  ExceptionInfo exception = 9;
  repeated ContextChange context_diff = 10;      // CONTEXT_CHANGED, DISPATCHER_CHANGED
  BlockReason block_reason = 11;                 // THREAD_BLOCKED
  HandledBy   handled_by   = 12;                 // EXCEPTION_HANDLED
  PropagationDirection direction = 13;           // *_PROPAGATED
  NodeState   final_state  = 14;                 // FINISHED: COMPLETED, FAILED or CANCELLED
  int64       held_nanos   = 15;                 // how long the gate held thread_id since that thread's previous event
  bool        same_step    = 16;                 // not the first event of its step, see "Execution control"
}

// A setting of the agent's gate from this moment on. Not an event: the program did nothing, and it takes no seq.
message PaceDef {
  enum Reason { REASON_UNSPECIFIED = 0; CONFIG = 1; CONTROLLER = 2; FAIL_OPEN = 3; SHUTDOWN = 4; NODE_FINISHED = 5; }
  int64  time_nanos     = 1;   // since the trace started
  int64  after_seq      = 2;   // the latest seq given out when the setting changed: its place among the events
  int64  scope_node_id  = 3;   // 0 = the global setting; else the node whose structural subtree it holds for
  int64  interval_nanos = 4;   // minimum distance between two steps of one sequence; 0 = no limit
  bool   paused         = 5;
  int32  steps          = 6;   // steps that this change let through a paused gate (`step n`); 0 for any other change
  Reason reason         = 7;
  bool   dropped        = 8;   // the node's setting is gone and its parent's governs it again; never for the global one
}

enum EventKind {
  EVENT_KIND_UNSPECIFIED = 0;
  LAUNCHED = 1;                 // node created under observation
  DISCOVERED = 2;               // node existed before the agent first saw it (the main thread)
  CONTEXT_CHANGED = 3;          // context diff against the structural parent, without Job and dispatcher
  DISPATCHER_CHANGED = 4;       // the dispatcher part of the diff
  SUSPENDED = 5;                // stack = the coroutine's logical stack from continuation debug metadata
  RESUMED = 6;                  // thread_id = where
  EXCEPTION_THROWN = 7;         // exception.stack = where it was thrown
  EXCEPTION_PROPAGATED = 8;     // node_id received it from other_node_id
  EXCEPTION_HANDLED = 9;
  CANCELLATION_REQUESTED = 10;  // Job.cancel(); other_node_id = the requester, stack = the call site
  CANCELLATION_PROPAGATED = 11; // other_node_id cancelled node_id (its parent, dynamically)
  CANCELLING = 12;              // the job entered the cancelling state; exception = the cause
  THREAD_BLOCKED = 13;          // node_id = the thread; other_node_id = the coroutine that ran on it
  THREAD_UNBLOCKED = 14;
  THREAD_INTERRUPTED = 15;      // node_id = the target; other_node_id = the interrupter
  FINISHED = 16;
}

enum NodeKind   { NODE_KIND_UNSPECIFIED = 0; THREAD = 1; COROUTINE = 2; SCOPE = 3; CONTEXT_CHANGE = 4; TASK = 5; POOL = 6; }
enum NodeState  { NODE_STATE_UNSPECIFIED = 0; ACTIVE = 1; SUSPENDED_STATE = 2; BLOCKED = 3; CANCELLING_STATE = 4;
                  COMPLETED = 5; FAILED = 6; CANCELLED = 7; }
enum Origin     { ORIGIN_UNSPECIFIED = 0; PROJECT = 1; LIBRARY = 2; }
enum BlockReason { BLOCK_REASON_UNSPECIFIED = 0; MONITOR = 1; WAIT = 2; JOIN = 3; PARK = 4; SLEEP = 5; IO = 6; RUN_BLOCKING = 7; }
enum HandledBy  { HANDLED_BY_UNSPECIFIED = 0; CATCH = 1; COROUTINE_EXCEPTION_HANDLER = 2; SUPERVISOR = 3;
                  DEFERRED_HELD = 4; UNCAUGHT_EXCEPTION_HANDLER = 5; }
enum PropagationDirection { DIRECTION_UNSPECIFIED = 0; PARENT_TO_CHILD = 1; CHILD_TO_PARENT = 2; }

message NodeInfo {
  int64    id         = 1;    // unique in the trace, never reused, starts at 1
  NodeKind kind       = 2;
  string   construct  = 3;    // launch, withContext, coroutineScope, Job(), suspend fun main, startCoroutine, Thread.start, worker, …
  string   name       = 4;    // the CoroutineName that the node was given itself (not an inherited one), or the thread name
  int64    parent_id  = 5;    // structural parent: parent Job, starting thread or owning pool; 0 = root
  int64    creator_id = 6;    // execution unit whose code created the node; a cross-link, not a tree edge
  int32    site_frame = 7;    // frame id of the source site
  Origin   origin     = 8;
  repeated ContextElement context = 9;
  string   impl_class = 10;   // kotlinx.coroutines.StandaloneCoroutine; without a Job, the class of what the coroutine completes into
  ThreadInfo thread   = 11;   // threads only
}

message ThreadInfo { int64 tid = 1;  bool virtual = 2;  bool daemon = 3; }

enum ContextElementKind { CONTEXT_ELEMENT_KIND_UNSPECIFIED = 0; JOB = 1; DISPATCHER = 2; NAME = 3; EXCEPTION_HANDLER = 4; OTHER = 5; }

message ContextElement {
  ContextElementKind kind = 1;
  string key   = 2;           // Job, Dispatcher, CoroutineName, CoroutineExceptionHandler, or the element's class name
  string value = 3;           // toString() of the element, identity hashes removed; empty for the Job
  bool   thread_context_element = 4;
}

message ContextChange {
  ContextElementKind kind = 1;  string key = 2;  string old_value = 3;  string new_value = 4;
  bool added = 5;  bool removed = 6;
}

message ExceptionInfo {
  string class_name = 1;  string message = 2;
  repeated int32 stack = 3 [packed = true];   // the throwable's own stack trace; only on EXCEPTION_THROWN and CATCH
  int32  identity = 4;                        // identityHashCode: follows one instance across events
  bool   cancellation = 5;                    // is a CancellationException
}
```

The code spells `SUSPENDED_STATE` and `CANCELLING_STATE` as `SUSPENDED` and `CANCELLING`. Proto enums share one
namespace, and only the numbers count.

## Semantics

**Order.** Capture threads assign dense `seq` numbers, then enqueue events. Frames can arrive out of order.
`TraceStore` waits for predecessors. At stream end, or with 4096 events waiting behind a gap, it releases them in sequence order.

**Nodes.** The `node` of a `LAUNCHED` or `DISCOVERED` event defines a node. An event can refer to a node that the
stream defines later, so a reader keeps a placeholder.

**Coroutines without a Job** (`suspend fun main`, bare `startCoroutine`) are `COROUTINE` nodes without what a Job
brings. They have no cancellation events, and a `CancellationException` that ends one makes it `FAILED`.
`suspend fun main` is a child of its thread. The rest are roots. Generators (`sequence`, `iterator`) aren't in the
trace at all.

**Two-node events** belong to `node_id` and identify the other participant in `other_node_id`. Readers show them on both nodes.
Readers derive these cross-links:

| Source | Cross-link |
|---|---|
| `creator_id ≠ parent_id` | *launched from* |
| `CANCELLATION_REQUESTED` | *cancels* |
| `THREAD_INTERRUPTED` | *interrupts* |
| The thread of the latest `RESUMED` | *runs on* |

**State** of a node is a fold over its events, in this order:

1. The final state, if `FINISHED`.
2. *Blocked*, while `THREAD_BLOCKED`s outnumber `THREAD_UNBLOCKED`s. They nest: a thread blocked in `runBlocking`
   runs a coroutine that sleeps.
3. *Cancelling*, after `CANCELLING`.
4. *Suspended* or *active*, by the latest of `SUSPENDED` and `RESUMED`.

**Source site.** The agent picks `site_frame`: the innermost frame of the creating stack that is outside the JDK, the
Kotlin standard library and kotlinx.coroutines. `origin` is `PROJECT` when that frame's class matches
`include_packages` and not `exclude_packages`. A thread is `PROJECT` only when the site called a thread-starting API
directly. A timer thread that `delay` starts has a site but is `LIBRARY`.

**Inlined code.** The agent expands JVM frames through Kotlin source maps (SMAP) into logical frames, in this order:

1. The body, `inlined = true`. `class_name` is the class that declares the inline function, because package and
   `file_name` then find the file like for any frame. `line` is the line in that file. `method_name` is the function, and
   empty where the class file didn't give it.
2. In project classes, one `inlined` frame for every inline function between the body and the call site, at the line
   of the call.
3. The call site: the JVM frame's class and method with the file and line of the outermost inline call.

The source site is in the project's inline function, or at the project call site for a library's inline function.
These rules apply to event, exception and suspension stacks in project and library classes.
A lambda compiled into a copy of an inline function's anonymous class has one frame, with its real file and line.
The synthetic `main(String[])` for `suspend fun main` has no line numbers, so it uses the declaration's line.

**Execution control.** [DESIGN §3.1](DESIGN.md#31-execution-control-m12) defines sequences, steps and holds.
Holds cause no events or node states. The format records their duration and settings:

- `held_nanos` is the thread's total hold time since its previous event. Holds in hooks that emit nothing carry to the next event.
- `same_step` is false on the step's first event and true on the rest. Pace and step commands count steps, not individual events.
- The first event's `time_nanos` is the gate release time, which preserves exact spacing between steps of a sequence.
- `PaceDef` records each complete setting change in change order. Readers maintain a global setting and node overrides.
  The innermost override along a node's ancestry governs it. Otherwise, the global setting applies.

`PaceDef` reasons:

| Reason | The change |
|---|---|
| `CONFIG` | The first one: what the run started with. |
| `CONTROLLER` | A command of a live client. |
| `FAIL_OPEN` | The last controller went away, or none can ever exist. Everything is as configured again, unpaused, and the subtree settings are gone. |
| `NODE_FINISHED` | The node that carried the setting ended. |
| `SHUTDOWN` | The JVM is going down and the gate stays open. |

## Live stream

The live socket uses loopback TCP on a random port.
Every agent writes `<sessions dir>/<pid>.json` to `~/.coroutree/sessions`, or the directory set by `sessions.dir`.
An empty `sessions.dir` writes no descriptor.

```json
{"pid":4242,"port":51234,"token":"9f…32 hex","traceFile":"/abs/run-4242.ctrace","taskPath":":app:run",
 "buildId":"20260917-211201-7c32","command":"com.acme.MainKt","startedAt":1789672321000,"ended":false}
```

`paceable` is true when a live socket and gate are available. Without a socket the descriptor omits `port` and `token`,
and has `"liveFailed":true` when `live=true` and the socket couldn't open. The GUI lists that run as a recorded trace.

A client sends the token followed by `\n`, then receives the trace from its first byte and follows new frames until JVM exit.
A wrong token closes the connection.

On orderly shutdown, the agent sets `"ended":true`. After a crash, the GUI checks process identity and start time.
The trace remains readable through the last flush.
The GUI deletes the descriptors of JVMs that are gone when their trace is gone, and all but the newest 50 of the rest.
The agent never deletes them.

### Commands

After authentication, a client can send ASCII commands terminated by `\n` or `\r\n`, at most 256 characters per line.
The agent discards longer lines in full.

```
pace <intervalNanos> [node]     at most one step for each interval in every sequence; 0 = no limit
pause [node]
resume [node]
step <n> [node]                 lets n steps through a paused gate; sent to a running program, stops it after n
inherit <node>                  drops the node's setting: its parent's governs it again
```

Rules of the commands:

- `node` is a node id of the trace. 0 or absent means the global setting.
- The first command for a node copies its governing setting into an independent override.
- The agent sends no reply. Clients observe changes through `PaceDef` frames in the shared stream.
- The last command wins.
- A client that sent a well-formed command is a *controller*, also when the command named an unknown node.
  `FAIL_OPEN` is about controllers.
- The agent ignores unknown lines, unknown nodes and nodes that have ended.
- Intervals above a day are a day.
- In a JVM without a gate nobody reads what a client sends.
