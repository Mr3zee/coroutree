# coroutree trace format, version 1

A trace is what the agent writes and what the GUI reads — from a file (`*.ctrace`) or, byte for byte the same, from
the live socket of a running JVM.

Two implementations share this format and nothing else:

- the writer in the agent's bootstrap runtime, dependency-free Java: `kotlinx.coroutree.runtime.TraceEncoder`, `Wire`;
- the model, Kotlin with kotlinx.serialization: `coroutree-model`, `Trace.kt` (`TraceReader`, `TraceWriter`).

`coroutree-integration-tests` decodes what the real agent wrote with the model in every test; `CorpusTest` fails if an
event kind stops occurring in the sample corpus, so every message shape stays covered.

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
  string   construct  = 3;    // launch, withContext, coroutineScope, Job(), Thread.start, thread, worker, …
  string   name       = 4;    // CoroutineName the node was given itself (not an inherited one), or the thread name
  int64    parent_id  = 5;    // structural parent: parent Job / starting thread / owning pool; 0 = root
  int64    creator_id = 6;    // execution unit whose code created the node; a cross-link, not a tree edge
  int32    site_frame = 7;    // frame id of the source site
  Origin   origin     = 8;
  repeated ContextElement context = 9;
  string   impl_class = 10;   // kotlinx.coroutines.StandaloneCoroutine
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

## Live stream

Loopback TCP, random port. The agent publishes a session descriptor, `<sessions dir>/<pid>.json`:

```json
{"pid":4242,"port":51234,"token":"9f…32 hex","traceFile":"/abs/run-4242.ctrace","taskPath":":app:run",
 "buildId":"20260917-211201-7c32","command":"com.acme.MainKt","startedAt":1789672321000,"ended":false}
```

A client connects, sends the token and `\n`, and receives the trace from its first byte (magic, header, everything so
far), then frames as they are written, until the JVM exits. A wrong token gets the connection closed. On orderly
shutdown the descriptor is rewritten with `"ended":true`; after a crash it is stale, the connection is refused, and
`traceFile` has everything up to the last flush.
