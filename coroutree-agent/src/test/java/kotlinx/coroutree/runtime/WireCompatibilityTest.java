package kotlinx.coroutree.runtime;

import kotlinx.coroutree.model.BlockReason;
import kotlinx.coroutree.model.ContextChange;
import kotlinx.coroutree.model.ContextElement;
import kotlinx.coroutree.model.ContextElementKind;
import kotlinx.coroutree.model.Diagnostic;
import kotlinx.coroutree.model.Event;
import kotlinx.coroutree.model.EventKind;
import kotlinx.coroutree.model.ExceptionInfo;
import kotlinx.coroutree.model.Frame;
import kotlinx.coroutree.model.HandledBy;
import kotlinx.coroutree.model.JvmInfo;
import kotlinx.coroutree.model.NodeInfo;
import kotlinx.coroutree.model.NodeKind;
import kotlinx.coroutree.model.NodeState;
import kotlinx.coroutree.model.Origin;
import kotlinx.coroutree.model.PaceDef;
import kotlinx.coroutree.model.PropagationDirection;
import kotlinx.coroutree.model.SourceFile;
import kotlinx.coroutree.model.SourceModule;
import kotlinx.coroutree.model.StackFrameDef;
import kotlinx.coroutree.model.ThreadInfo;
import kotlinx.coroutree.model.TraceHeader;
import kotlinx.coroutree.model.TraceReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The trace format has two implementations that share nothing: the agent's encoder, written by hand, and the model.
 * Here the first writes and the second reads, field for field; docs/TRACE_FORMAT.md is what both are held to.
 */
class WireCompatibilityTest {
    private static final StackFrameRef INLINE = new StackFrameRef("com.acme.util.UtilKt", "retry", "Util.kt", 30, true);
    private static final StackFrameRef MAIN = new StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12);
    private static final StackFrameRef REPO = new StackFrameRef("com.acme.Repo", "load", "Repo.java", 77);

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final TraceEncoder encoder = new TraceEncoder(bytes);

    private List<Frame> decoded() throws IOException {
        List<Frame> frames = new ArrayList<>();
        try (TraceReader reader = new TraceReader(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (Frame frame = reader.next(); frame != null; frame = reader.next()) frames.add(frame);
        }
        return frames;
    }

    private static Frame frame(Event event) {
        return new Frame(null, event, null, null, null);
    }

    private static Frame frame(StackFrameDef def) {
        return new Frame(null, null, def, null, null);
    }

    // ------------------------------------------------------------------ messages, field for field

    @Test
    void anEventWithEveryFieldSetIsReadBackFieldForField() throws IOException {
        TraceEvent event = new TraceEvent(41, Wire.LAUNCHED, 2);
        event.seq = 1_000_000_007L;
        event.timeNanos = 123_456_789_012L;
        event.stack = new StackFrameRef[] {INLINE, MAIN};
        event.otherNodeId = 40;
        event.blockReason = Wire.BLOCK_PARK;
        event.handledBy = Wire.BY_SUPERVISOR;
        event.direction = Wire.CHILD_TO_PARENT;
        event.finalState = Wire.STATE_CANCELLED;
        event.heldNanos = 5_000_000;
        event.sameStep = true;

        TraceEvent.NodeDef node = new TraceEvent.NodeDef();
        node.id = 41;
        node.kind = Wire.KIND_COROUTINE;
        node.construct = "launch";
        node.name = "worker №1";
        node.parentId = 40;
        node.creatorId = 39;
        node.siteFrame = MAIN;
        node.origin = Wire.ORIGIN_PROJECT;
        node.context = new ContextEntry[] {
            new ContextEntry(new Object(), Wire.CTX_JOB, "Job", "", false),
            new ContextEntry(new Object(), Wire.CTX_OTHER, "com.acme.Mdc", "MDC{user=7}", true),
        };
        node.implClass = "kotlinx.coroutines.StandaloneCoroutine";
        node.isThread = true;
        node.tid = 77;
        node.virtual = true;
        node.daemon = true;
        event.node = node;

        TraceEvent.ExceptionDef exception = new TraceEvent.ExceptionDef();
        exception.className = "java.lang.IllegalStateException";
        exception.message = "boom";
        exception.stack = new StackFrameRef[] {REPO, MAIN};
        exception.identity = 0x7ABCDEF0;
        exception.cancellation = true;
        event.exception = exception;

        TraceEvent.ContextChangeDef added = new TraceEvent.ContextChangeDef();
        added.kind = Wire.CTX_NAME;
        added.key = "CoroutineName";
        added.newValue = "worker";
        added.added = true;
        TraceEvent.ContextChangeDef removed = new TraceEvent.ContextChangeDef();
        removed.kind = Wire.CTX_EXCEPTION_HANDLER;
        removed.key = "CoroutineExceptionHandler";
        removed.oldValue = "Handler";
        removed.removed = true;
        TraceEvent.ContextChangeDef changed = new TraceEvent.ContextChangeDef();
        changed.kind = Wire.CTX_DISPATCHER;
        changed.key = "Dispatcher";
        changed.oldValue = "Dispatchers.Default";
        changed.newValue = "Dispatchers.IO";
        event.contextDiff = new TraceEvent.ContextChangeDef[] {added, removed, changed};

        encoder.writeMagic();
        encoder.writeEvent(event);

        Event expected = new Event(
            1_000_000_007L, 123_456_789_012L, 41, EventKind.LAUNCHED, 2, List.of(1, 2),
            new NodeInfo(41, NodeKind.COROUTINE, "launch", "worker №1", 40, 39, 2, Origin.PROJECT,
                List.of(new ContextElement(ContextElementKind.JOB, "Job", "", false),
                    new ContextElement(ContextElementKind.OTHER, "com.acme.Mdc", "MDC{user=7}", true)),
                "kotlinx.coroutines.StandaloneCoroutine", new ThreadInfo(77, true, true)),
            40,
            new ExceptionInfo("java.lang.IllegalStateException", "boom", List.of(3, 2), 0x7ABCDEF0, true),
            List.of(new ContextChange(ContextElementKind.NAME, "CoroutineName", "", "worker", true, false),
                new ContextChange(ContextElementKind.EXCEPTION_HANDLER, "CoroutineExceptionHandler", "Handler", "", false, true),
                new ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", "Dispatchers.Default", "Dispatchers.IO", false, false)),
            BlockReason.PARK, HandledBy.SUPERVISOR, PropagationDirection.CHILD_TO_PARENT, NodeState.CANCELLED, 5_000_000, true);
        assertEquals(
            List.of(
                frame(new StackFrameDef(1, "com.acme.util.UtilKt", "retry", "Util.kt", 30, true)),
                frame(new StackFrameDef(2, "com.acme.MainKt", "main", "Main.kt", 12, false)),
                frame(new StackFrameDef(3, "com.acme.Repo", "load", "Repo.java", 77, false)),
                frame(expected)),
            decoded());
    }

    @Test
    void theHeaderIsReadBackFieldForField(@TempDir Path dir) throws IOException {
        Path index = dir.resolve("index.tsv");
        Files.writeString(index, "M\t:app\t/work/app\nF\tcom.acme\tMain.kt\tsrc/main/kotlin/Main.kt\nF\t\tRoot.kt\tsrc/Root.kt\nM\t:lib\t/work/lib\n");
        AgentConfig config = AgentConfig.parse("build.id=b42,task.path=:app:run,project.dir=/work,include=com.acme;org.acme,exclude=com.acme.generated,"
            + "source.index=" + index + ",live=false,pace.events.per.second=4");
        assertEquals(List.of(), config.problems);

        encoder.writeMagic();
        encoder.writeHeader(config, 1_789_672_321_000L);

        List<Frame> frames = decoded();
        assertEquals(1, frames.size());
        TraceHeader header = frames.get(0).getHeader();
        assertNotNull(header, "the first frame holds the header");
        JvmInfo jvm = new JvmInfo(ProcessHandle.current().pid(), System.getProperty("java.version"), System.getProperty("java.vm.name"),
            System.getProperty("java.vm.version"), System.getProperty("sun.java.command"));
        assertEquals(
            new TraceHeader(TraceHeader.FORMAT_VERSION, BuildInfo.VERSION, "b42", ":app:run", jvm,
                new kotlinx.coroutree.model.SourceIndex(List.of(
                    new SourceModule(":app", "/work/app", List.of(new SourceFile("com.acme", "Main.kt", "src/main/kotlin/Main.kt"), new SourceFile("", "Root.kt", "src/Root.kt"))),
                    new SourceModule(":lib", "/work/lib", List.of()))),
                List.of("com.acme", "org.acme"), List.of("com.acme.generated"), 1_789_672_321_000L, "/work", true),
            header);
        assertEquals(1, Wire.FORMAT_VERSION, "version 1 grows by fields that old readers skip, not by a new number");
    }

    @Test
    void aJvmWithoutAGateSaysSoInItsHeader() throws IOException {
        encoder.writeMagic();
        encoder.writeHeader(AgentConfig.parse("pace=false"), 1);
        TraceHeader header = decoded().get(0).getHeader();
        assertFalse(header.getPaceable());
        assertEquals(List.of(), header.getSourceIndex().getModules());
        assertEquals(List.of(), header.getIncludePackages());
    }

    @Test
    void aSettingOfTheGateAndADiagnosticAreReadBackFieldForField() throws IOException {
        TraceEvent.PaceDef pace = new TraceEvent.PaceDef();
        pace.timeNanos = 9_000_000_000L;
        pace.afterSeq = 512;
        pace.scopeNodeId = 14;
        pace.intervalNanos = Pace.MAX_INTERVAL_NANOS;
        pace.paused = true;
        pace.steps = Integer.MAX_VALUE;
        pace.reason = Wire.PACE_CONTROLLER;
        pace.dropped = true;

        encoder.writeMagic();
        encoder.writePace(pace);
        encoder.writeDiagnostic(new TraceEvent.DiagnosticDef(Wire.WARNING, "Ignored stack.depth=many"));
        encoder.writePace(new TraceEvent.PaceDef()); // the global setting, unlimited, before the first event: all zeroes

        assertEquals(
            List.of(
                new Frame(null, null, null, null, new PaceDef(9_000_000_000L, 512, 14, Pace.MAX_INTERVAL_NANOS, true, Integer.MAX_VALUE, PaceDef.Reason.CONTROLLER, true)),
                new Frame(null, null, null, new Diagnostic(Diagnostic.Severity.WARNING, "Ignored stack.depth=many"), null),
                new Frame(null, null, null, null, new PaceDef())),
            decoded());
    }

    // ------------------------------------------------------------------ numbers

    /**
     * Every number the agent can write means, to the model, what its name says. The names differ by a prefix only
     * (proto enums share a namespace, the Java constants share a class).
     */
    @Test
    void everyEnumNumberOfTheAgentIsTheModelsValueOfTheSameName() throws Exception {
        Map<Class<?>, Integer> seen = new HashMap<>();
        for (Field field : Wire.class.getDeclaredFields()) {
            if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers()) || field.getName().equals("FORMAT_VERSION")) continue;
            field.setAccessible(true);
            int number = field.getInt(null);
            String name = field.getName();

            TraceEvent event = new TraceEvent(1, Wire.LAUNCHED, 1);
            event.seq = 1;
            TraceEvent.NodeDef node = new TraceEvent.NodeDef();
            event.node = node;
            TraceEvent.ContextChangeDef change = new TraceEvent.ContextChangeDef();
            event.contextDiff = new TraceEvent.ContextChangeDef[] {change};
            TraceEvent.PaceDef pace = new TraceEvent.PaceDef();
            int severity = 0;

            String expected;
            if (name.startsWith("KIND_")) {
                node.kind = number;
                expected = name.substring(5);
            } else if (name.startsWith("STATE_")) {
                event.finalState = number;
                expected = name.substring(6);
            } else if (name.startsWith("ORIGIN_")) {
                node.origin = number;
                expected = name.substring(7);
            } else if (name.startsWith("BLOCK_")) {
                event.blockReason = number;
                expected = name.substring(6);
            } else if (name.startsWith("BY_")) {
                event.handledBy = number;
                expected = name.substring(3);
            } else if (name.startsWith("CTX_")) {
                change.kind = number;
                node.context = new ContextEntry[] {new ContextEntry(null, number, "k", "v", false)};
                expected = name.substring(4);
            } else if (name.startsWith("PACE_")) {
                pace.reason = number;
                expected = name.substring(5);
            } else if (name.equals("PARENT_TO_CHILD") || name.equals("CHILD_TO_PARENT")) {
                event.direction = number;
                expected = name;
            } else if (name.equals("INFO") || name.equals("WARNING") || name.equals("ERROR")) {
                severity = number;
                expected = name;
            } else {
                event = new TraceEvent(1, number, 1);
                event.seq = 1;
                expected = name;
            }

            bytes.reset();
            encoder.writeMagic();
            encoder.writeEvent(event);
            encoder.writePace(pace);
            encoder.writeDiagnostic(new TraceEvent.DiagnosticDef(severity, "m"));
            List<Frame> frames = decoded();
            Event read = frames.get(0).getEvent();

            Enum<?> actual;
            if (name.startsWith("KIND_")) actual = read.getNode().getKind();
            else if (name.startsWith("STATE_")) actual = read.getFinalState();
            else if (name.startsWith("ORIGIN_")) actual = read.getNode().getOrigin();
            else if (name.startsWith("BLOCK_")) actual = read.getBlockReason();
            else if (name.startsWith("BY_")) actual = read.getHandledBy();
            else if (name.startsWith("CTX_")) {
                actual = read.getContextDiff().get(0).getKind();
                assertEquals(actual, read.getNode().getContext().get(0).getKind(), name);
            } else if (name.startsWith("PACE_")) actual = frames.get(1).getPace().getReason();
            else if (name.endsWith("_TO_CHILD") || name.endsWith("_TO_PARENT")) actual = read.getDirection();
            else if (severity != 0) actual = frames.get(2).getDiagnostic().getSeverity();
            else actual = read.getKind();

            assertEquals(expected, actual.name(), "Wire." + name + " = " + number);
            seen.merge(actual.getDeclaringClass(), 1, Integer::sum);
        }

        // And the other way round: nothing the model can read is beyond the agent's vocabulary. NodeState is the
        // exception the format spells out: the agent writes final states only, the others are a reader's fold.
        Map<Class<?>, Integer> expected = new HashMap<>();
        for (Class<? extends Enum<?>> type : List.of(EventKind.class, NodeKind.class, Origin.class, BlockReason.class, HandledBy.class,
            ContextElementKind.class, PaceDef.Reason.class, PropagationDirection.class, Diagnostic.Severity.class)) {
            expected.put(type, type.getEnumConstants().length - 1); // minus UNSPECIFIED
        }
        expected.put(NodeState.class, (int) Arrays.stream(NodeState.values()).filter(NodeState::isFinal).count());
        assertEquals(expected, seen, "values per enum that the agent has a number for");
    }

    @Test
    void theSignatureAndTheVersionAreTheModels() throws IOException {
        encoder.writeMagic();
        assertArrayEquals("COROTREE".getBytes(StandardCharsets.US_ASCII), bytes.toByteArray());
        assertEquals(TraceHeader.FORMAT_VERSION, Wire.FORMAT_VERSION);
        assertEquals(List.of(), decoded(), "a trace of nothing but its signature is an empty trace");
    }

    // ------------------------------------------------------------------ proto3 conventions

    @Test
    void aFieldThatHoldsItsZeroDefaultIsNotWritten() throws IOException {
        TraceEvent event = new TraceEvent(0, 0, 0);
        event.seq = 1;
        encoder.writeEvent(event);
        // length 4 | Frame.event (field 2, length-delimited) of 2 bytes | Event.seq (field 1, varint) = 1
        assertArrayEquals(new byte[] {4, 0x12, 2, 0x08, 1}, bytes.toByteArray(),
            "an event of a JVM without a gate is byte for byte what it was before held_nanos and same_step existed");
    }

    @Test
    void anEmptyMessageIsStillThere() throws IOException {
        // Presence is information: an exception nobody could describe is an exception; a thread with nothing set is a thread.
        TraceEvent event = new TraceEvent(1, Wire.EXCEPTION_THROWN, 1);
        event.seq = 1;
        event.exception = new TraceEvent.ExceptionDef();
        event.node = new TraceEvent.NodeDef();
        event.node.isThread = true;
        event.stack = new StackFrameRef[0];
        encoder.writeMagic();
        encoder.writeEvent(event);

        Event read = decoded().get(0).getEvent();
        assertEquals(new ExceptionInfo(), read.getException());
        assertEquals(new ThreadInfo(), read.getNode().getThread());
        assertEquals(List.of(), read.getStack());

        bytes.reset();
        TraceEvent plain = new TraceEvent(1, Wire.RESUMED, 1);
        plain.seq = 2;
        plain.node = new TraceEvent.NodeDef(); // not a thread
        encoder.writeMagic();
        encoder.writeEvent(plain);
        read = decoded().get(0).getEvent();
        assertNull(read.getException());
        assertNull(read.getNode().getThread(), "thread info is for threads only");
    }

    @Test
    void numbersAndTextSurviveAtTheirExtremes() throws IOException {
        // A message of megabytes (a frame length of several varint bytes), text outside ASCII and outside the BMP,
        // the largest and the negative numbers a field can hold.
        String huge = "∑🧵 ".repeat(200_000);
        TraceEvent event = new TraceEvent(Long.MAX_VALUE, Wire.EXCEPTION_THROWN, Long.MAX_VALUE);
        event.seq = Long.MAX_VALUE;
        event.timeNanos = Long.MAX_VALUE;
        event.heldNanos = -1; // never written by the agent, but an int64 is an int64
        event.exception = new TraceEvent.ExceptionDef();
        event.exception.message = huge;
        event.exception.identity = Integer.MIN_VALUE;
        StackFrameRef[] deep = new StackFrameRef[300];
        for (int i = 0; i < deep.length; i++) deep[i] = new StackFrameRef("com.acme.Deep", "level" + i, "Deep.kt", i == 0 ? Integer.MAX_VALUE : i);
        event.stack = deep;

        encoder.writeMagic();
        encoder.writeEvent(event);
        TraceEvent small = new TraceEvent(1, Wire.RESUMED, 1);
        small.seq = 2;
        encoder.writeEvent(small);

        List<Frame> frames = decoded();
        assertEquals(302, frames.size(), "300 frame definitions and two events: the reader found every frame's end");
        assertEquals(Integer.MAX_VALUE, frames.get(0).getStackFrame().getLine());
        Event read = frames.get(300).getEvent();
        assertEquals(Long.MAX_VALUE, read.getSeq());
        assertEquals(Long.MAX_VALUE, read.getTimeNanos());
        assertEquals(Long.MAX_VALUE, read.getNodeId());
        assertEquals(-1, read.getHeldNanos());
        assertEquals(huge, read.getException().getMessage());
        assertEquals(Integer.MIN_VALUE, read.getException().getIdentity());
        assertEquals(300, read.getStack().size());
        assertEquals(300, read.getStack().get(299), "frame ids above 127 take two bytes in a packed field");
        assertEquals(2, frames.get(301).getEvent().getSeq());
    }

    // ------------------------------------------------------------------ interned stack frames

    @Test
    void aStackFrameIsDefinedOnceBeforeTheFirstEventThatRefersToItAndIdsAreDense() throws IOException {
        encoder.writeMagic();
        TraceEvent first = new TraceEvent(1, Wire.THREAD_BLOCKED, 1);
        first.seq = 1;
        first.stack = new StackFrameRef[] {REPO, MAIN};
        encoder.writeEvent(first);

        // Equal by value, another instance; and the three places a frame can be referred to from.
        TraceEvent second = new TraceEvent(2, Wire.LAUNCHED, 1);
        second.seq = 2;
        second.stack = new StackFrameRef[] {new StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12), INLINE};
        second.node = new TraceEvent.NodeDef();
        second.node.siteFrame = new StackFrameRef("com.acme.Repo", "load", "Repo.java", 77);
        second.exception = new TraceEvent.ExceptionDef();
        second.exception.stack = new StackFrameRef[] {
            // The same line of the same method as INLINE, but the method's own code, not an inline function's body in it.
            new StackFrameRef("com.acme.util.UtilKt", "retry", "Util.kt", 30, false),
            // Everything as MAIN but the file: a class of the same name from another source.
            new StackFrameRef("com.acme.MainKt", "main", "Other.kt", 12),
        };
        encoder.writeEvent(second);

        List<String> shape = new ArrayList<>();
        for (Frame frame : decoded()) {
            StackFrameDef def = frame.getStackFrame();
            if (def != null) shape.add("frame " + def.getId() + " " + def.getClassName() + ":" + def.getLine() + (def.getInlined() ? " inlined" : "") + " " + def.getFileName());
            Event event = frame.getEvent();
            if (event != null) {
                shape.add("event " + event.getSeq() + " stack " + event.getStack()
                    + (event.getNode() == null ? "" : " site " + event.getNode().getSiteFrame())
                    + (event.getException() == null ? "" : " thrown at " + event.getException().getStack()));
            }
        }
        assertEquals(
            List.of(
                "frame 1 com.acme.Repo:77 Repo.java",
                "frame 2 com.acme.MainKt:12 Main.kt",
                "event 1 stack [1, 2]",
                "frame 3 com.acme.util.UtilKt:30 inlined Util.kt",
                "frame 4 com.acme.util.UtilKt:30 Util.kt",
                "frame 5 com.acme.MainKt:12 Other.kt",
                "event 2 stack [2, 3] site 1 thrown at [4, 5]"),
            shape);
    }

    @Test
    void aFrameOfNativeOrUnknownCodeHasLineZeroAndNoFile() throws IOException {
        // 0 is "unknown" for line numbers; the JVM says -2 for native methods and -1 for no line information.
        TraceEvent event = new TraceEvent(1, Wire.THREAD_BLOCKED, 1);
        event.seq = 1;
        event.stack = StackFrameRef.of(new StackTraceElement[] {
            new StackTraceElement("jdk.internal.misc.Unsafe", "park", "Unsafe.java", -2),
            new StackTraceElement("com.acme.Generated", "run", null, -1),
        }, 10);
        encoder.writeMagic();
        encoder.writeEvent(event);
        List<Frame> frames = decoded();
        assertEquals(new StackFrameDef(1, "jdk.internal.misc.Unsafe", "park", "Unsafe.java", 0, false), frames.get(0).getStackFrame());
        assertEquals(new StackFrameDef(2, "com.acme.Generated", "run", "", 0, false), frames.get(1).getStackFrame());
    }
}
