# coroutree trace format, version 1

A trace is what the agent writes and what the GUI reads — from a file (`*.ctrace`) or, byte for byte the same, from
the live socket of a running JVM.

Two implementations share this format and nothing else:

- the writer in the agent's bootstrap runtime, dependency-free Java: `kotlinx.coroutree.runtime.TraceEncoder`, `Wire`;
- the model, Kotlin with kotlinx.serialization: `coroutree-model`, `Trace.kt` (`TraceReader`, `TraceWriter`).

`coroutree-integration-tests` decodes what the real agent wrote with the model in every test; `CorpusTest` fails if an
event kind stops occurring in the sample corpus, so every message shape stays covered.

Version 1 has grown once, by fields that old readers skip: execution control (M1.2) added `Frame.pace`,
`TraceHeader.paceable`, `Event.held_nanos` and `Event.same_step`. A JVM without a gate (`pace=false`, or no live socket
and no configured pace) writes none of them, and its trace is byte for byte what it was before.

## Layout

```
trace  := magic frame*
magic  := "COROTREE"                      8 bytes, ASCII
frame  := length message                  length: base-128 varint, number of bytes of message
message:= a protobuf-encoded Frame
```

- The first frame holds the `header`.
- A trace is append-only and its writer may die at any moment. A reader treats a frame that is cut short by the end of
  the stream as the end of the trace, not as an error.
- Proto3 conventions: every field has a zero default and is left out when it holds it; `0` is "none" for node and
  frame ids and "unknown" for line numbers; every enum starts with `UNSPECIFIED = 0`. Unknown fields must be skipped:
  that is how the format grows within a version.

## Schema

```proto
syntax = "proto3";

message Frame {                       // exactly one field is set
  TraceHeader   header      = 1;
  Event         event       = 2;
  StackFrameDef stack_frame = 3;
  Diagnostic    diagnostic  = 4;
  PaceDef       pace        = 5;   // execution control: a setting of the agent's gate, see below
}

message TraceHeader {
  int32       format_version          = 1;   // 1
  string      agent_version           = 2;
  string      build_id                = 3;   // one per Gradle invocation; groups the traces of its JVMs
  string      task_path               = 4;   // ":app:run"; empty when the agent was attached by hand
  JvmInfo     jvm                     = 5;
  SourceIndex source_index            = 6;
  repeated string include_packages    = 7;   // prefixes of project code; empty = everything that is not a runtime
  repeated string exclude_packages    = 8;
  int64       started_at_epoch_millis = 9;
  string      project_dir             = 10;  // root project directory, absolute
  bool        paceable                = 11;  // the JVM has a gate: it can be slowed down, paused and stepped
}

message JvmInfo {
  int64  pid = 1;  string java_version = 2;  string vm_name = 3;  string vm_version = 4;
  string command = 5;                        // sun.java.command
}

// A stack frame knows its class and the bare name of its source file. Kotlin and Java both put a class into the
// package its file declares, so (package of the class, file name) finds the file.
message SourceIndex  { repeated SourceModule modules = 1; }
message SourceModule { string path = 1;  string root_dir = 2;  repeated SourceFile files = 3; }   // ":app", absolute dir
message SourceFile   { string package_name = 1;  string file_name = 2;  string path = 3; }        // path relative to root_dir

// Stack frames are interned. A definition precedes the first event that refers to it. Ids start at 1 and are dense.
message StackFrameDef {
  int32 id = 1;  string class_name = 2;  string method_name = 3;  string file_name = 4;  int32 line = 5;
  bool inlined = 6;                          // the body of an inline function, see "Inlined code" below
}

message Diagnostic {                         // the agent talking about the capture itself
  enum Severity { UNSPECIFIED = 0; INFO = 1; WARNING = 2; ERROR = 3; }
  Severity severity = 1;  string message = 2;
}

message Event {
  int64     seq           = 1;   // global order across all threads: starts at 1, dense, strictly increasing
  int64     time_nanos    = 2;   // since the trace started (System.nanoTime)
  int64     node_id       = 3;   // the node the event happens to
  EventKind kind          = 4;
  int64     thread_id     = 5;   // node id of the thread the event was captured on
  repeated int32 stack    = 6 [packed = true];   // frame ids, innermost first
  NodeInfo  node          = 7;   // LAUNCHED, DISCOVERED: definition of node_id
  int64     other_node_id = 8;   // where it came from / who did it, see below
  ExceptionInfo exception = 9;
  repeated ContextChange context_diff = 10;      // CONTEXT_CHANGED, DISPATCHER_CHANGED
  BlockReason block_reason = 11;                 // THREAD_BLOCKED
  HandledBy   handled_by   = 12;                 // EXCEPTION_HANDLED
  PropagationDirection direction = 13;           // *_PROPAGATED
  NodeState   final_state  = 14;                 // FINISHED: COMPLETED, FAILED or CANCELLED
  int64       held_nanos   = 15;                 // how long the gate held thread_id since that thread's previous event
  bool        same_step    = 16;                 // not the first event of its step, see "Execution control"
}

// A setting of the agent's gate as it is from now on. Not an event: the program did nothing, and it takes no seq.
message PaceDef {
  enum Reason { REASON_UNSPECIFIED = 0; CONFIG = 1; CONTROLLER = 2; FAIL_OPEN = 3; SHUTDOWN = 4; NODE_FINISHED = 5; }
  int64  time_nanos     = 1;   // since the trace started
  int64  after_seq      = 2;   // the latest seq handed out when the setting changed: where among the events it belongs
  int64  scope_node_id  = 3;   // 0 = the global setting; else the node whose structural subtree it holds for
  int64  interval_nanos = 4;   // minimum distance between two steps of one sequence; 0 = no limit
  bool   paused         = 5;
  int32  steps          = 6;   // steps this change let through a paused gate (`step n`); 0 for any other change
  Reason reason         = 7;
  bool   dropped        = 8;   // the node's setting is gone, it goes by its parent's again; never for the global one
}

enum EventKind {
  EVENT_KIND_UNSPECIFIED = 0;
  LAUNCHED = 1;                 // node created under observation
  DISCOVERED = 2;               // node existed before it was first seen (the main thread)
  CONTEXT_CHANGED = 3;          // context diff against the structural parent, without Job and dispatcher
  DISPATCHER_CHANGED = 4;       // the dispatcher part of the diff
  SUSPENDED = 5;                // stack = the coroutine's logical stack from continuation debug metadata
  RESUMED = 6;                  // thread_id says where
  EXCEPTION_THROWN = 7;         // exception.stack = where it was thrown
  EXCEPTION_PROPAGATED = 8;     // node_id received it from other_node_id
  EXCEPTION_HANDLED = 9;
  CANCELLATION_REQUESTED = 10;  // Job.cancel(); other_node_id = the requester, stack = the call site
  CANCELLATION_PROPAGATED = 11; // node_id was cancelled by other_node_id (its parent, dynamically)
  CANCELLING = 12;              // the job entered the cancelling state; exception = the cause
  THREAD_BLOCKED = 13;          // node_id = the thread; other_node_id = the coroutine that was running on it
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
  string   name       = 4;    // CoroutineName the node was given itself (not an inherited one), or the thread name
  int64    parent_id  = 5;    // structural parent: parent Job / starting thread / owning pool; 0 = root
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
  string value = 3;           // toString() of the element, identity hashes stripped; empty for the Job
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

(`SUSPENDED_STATE` and `CANCELLING_STATE` are spelled `SUSPENDED` and `CANCELLING` in the code; proto enums share one
namespace, the numbers are what counts.)

## Semantics worth knowing

**Order.** `seq` is assigned when the event happens, on the thread where it happens. Threads race between taking a
number and handing the event to the writer, so frames may be slightly out of order on the wire. Numbers are dense: a
reader restores the exact order by holding an event back until its predecessor has arrived (`TraceStore` does; at the
end of the stream, or when too many events pile up behind a gap, it lets them through in order).

**Nodes** are defined by the `node` of a `LAUNCHED` or `DISCOVERED` event. An event may refer to a node that is defined
later in the stream; a reader keeps a placeholder.

**Coroutines without a Job** (`suspend fun main`, bare `startCoroutine`) are `COROUTINE` nodes like any other, minus
what a Job brings: no cancellation events, and a `CancellationException` they end with is `FAILED`. `suspend fun main`
is a child of its thread, the rest are roots. Generators (`sequence`, `iterator`) are not in the trace at all.

**Two-node events** belong to the node they happen to (`node_id`) and name the other one in `other_node_id`. A reader
shows them on both. Cross-links are derived: `creator_id ≠ parent_id` → *launched from*; `CANCELLATION_REQUESTED` →
*cancels*; `THREAD_INTERRUPTED` → *interrupts*; the thread of the latest `RESUMED` → *runs on*.

**State** of a node is a fold over its events: final state if `FINISHED`; else *blocked* while `THREAD_BLOCKED`s
outnumber `THREAD_UNBLOCKED`s (they nest: a thread blocked in `runBlocking` runs a coroutine that sleeps); else
*cancelling* after `CANCELLING`; else *suspended* / *active* by the latest of `SUSPENDED` / `RESUMED`.

**Source site.** The agent picks `site_frame`: the innermost frame of the creating stack that is outside the JDK, the
Kotlin standard library and kotlinx.coroutines. `origin` is `PROJECT` when that frame's class matches
`include_packages` (and not `exclude_packages`). Threads are `PROJECT` only when the site called a thread-starting API
directly; a timer thread started behind `delay` has a site but is `LIBRARY`.

**Inlined code.** Stacks are logical, not the JVM's. The Kotlin compiler copies the body of an inline function into
the calling method and numbers the copy with lines past the end of the caller's file; the agent reads the way back
from the class's source map (SMAP) and writes such a JVM frame as several. First the body, `inlined = true`:
`class_name` is the class that declares the inline function (so that package and `file_name` find the file like for
any frame), `line` the line in that file, `method_name` the function, empty where the class file did not tell. Then,
in project classes, one `inlined` frame for every inline function that one was inlined through, at the line of the
call. Last the call site: the JVM frame's class and method with the file and line of the outermost inline call. A
frame in a lambda that was compiled into a copy of an inline function's anonymous class is one frame, with its real
file and line. The rule for the source site is unchanged and now means what it should: a construct inside a project's
inline function has its site there, in a library's inline function at the project's call. This goes for every stack
in the trace (events, exceptions, suspension points) and for the classes of libraries as well as the project's.
The `main(String[])` behind a `suspend fun main`, which has no line numbers, is given the line of the declaration.

**Execution control** (DESIGN §3.1). The agent can hold the program's threads at their events: to slow it down (a
*pace*: a minimum interval between two steps of one *sequence* — steps made by the same flow, or happening to the same
node), to pause it, and to let it go on step by step; for the whole program or for the structural subtree of a node.
The hold itself is **not in the trace**: no event, no state, no `THREAD_BLOCKED`. What is in the trace is the truth
about time and about settings:

- `held_nanos`: how long the thread of an event was held since its previous event. Sums per thread are exact (time
  held for a hook call that then had nothing to report is carried to the thread's next event), which is what a later
  virtual clock needs: program time = wall time minus time held.
- A *step* is one hook call that emits at least one event — *launched* + *context changed*, *thrown* + *handled*, a
  job's *propagated to the caller* + *finished*. There is no program code between the events of a step, so they are
  never spread out. The first event of a step has `same_step = false`, the others `true`; steps are what a pace spaces
  and what `step n` counts.
- `time_nanos` of the first event of a step is the moment the gate let it go. Two steps that share a sequence are
  therefore at least the interval apart *exactly*, whatever order they reach the writer in.
- `PaceDef`: every change of a setting, in the order it was made, each a complete setting. The first one (`CONFIG`)
  is what the run started with. `CONTROLLER`: a command of a live client. `FAIL_OPEN`: the last controlling client
  went away (or there never could be one) and everything is as configured again, unpaused, subtree settings dropped.
  `NODE_FINISHED`: the node that carried the setting ended. `SHUTDOWN`: the JVM is going down and the gate is open for
  good. A reader folds them into "global setting + settings by node"; what governs a node is the innermost setting on
  its way to the root, else the global one.

## Live stream

Loopback TCP, random port. The agent publishes a session descriptor, `<sessions dir>/<pid>.json`:

```json
{"pid":4242,"port":51234,"token":"9f…32 hex","traceFile":"/abs/run-4242.ctrace","taskPath":":app:run",
 "buildId":"20260917-211201-7c32","command":"com.acme.MainKt","startedAt":1789672321000,"ended":false}
```

With a gate in the JVM the descriptor also says `"paceable":true`.

A client connects, sends the token and `\n`, and receives the trace from its first byte (magic, header, everything so
far), then frames as they are written, until the JVM exits. A wrong token gets the connection closed. On orderly
shutdown the descriptor is rewritten with `"ended":true`; after a crash it is stale, the connection is refused, and
`traceFile` has everything up to the last flush.

### Commands

In a JVM that has a gate the socket is two-way. After the token a client may send commands, one per line (`\n` or
`\r\n`, ASCII, at most 256 characters; a longer line is nothing, not its tail):

```
pace <intervalNanos> [node]     at most one step per interval in every sequence; 0 = no limit
pause [node]
resume [node]
step <n> [node]                 lets n steps through a paused gate; asked of a running program, stops it after n
inherit <node>                  drops the node's setting: it goes by its parent's again
```

`node` is a node id of the trace (0 or absent: the global setting); a node gets a setting of its own with the first
command about it, which starts as a copy of what governed the node and is independent from then on. There is **no
reply**: what a command did comes back as a `PaceDef` in the stream, the same for every client, which is also how
several clients agree. The last command wins. A client that has sent a well-formed command is a *controller* (that is
what `FAIL_OPEN` is about). Unknown lines, unknown nodes and nodes that have ended are ignored. Intervals above a day
are a day. In a JVM without a gate nobody reads what a client sends.
