package kotlinx.coroutree.runtime;

import kotlinx.coroutree.model.Frame;
import kotlinx.coroutree.model.TraceReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live socket (TRACE_FORMAT, "Live stream"): a client gets the trace file byte for byte from its first byte and
 * then as it grows, whenever it joins and however it reads; and with a gate in the JVM it may send commands, which
 * makes it a controller whose leaving must not leave the program stopped.
 */
@Timeout(60)
class LiveServerTest {
    static {
        // The JVM's one gate, as PaceTest makes it, whichever of the two is loaded first.
        if (Tracer.config == null) Tracer.config = AgentConfig.parse(TracedJvm.GATE_OPTIONS);
    }

    @TempDir
    File dir;

    private File traceFile;
    private File descriptor;
    private TraceWriterThread writer;
    private LiveServer server;
    private final List<Socket> sockets = new ArrayList<>();
    private final List<TraceEvent.PaceDef> defs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startSession() throws IOException {
        assertNotNull(Pace.GATE, "the test JVM was to have a gate");
        traceFile = new File(dir, "run.ctrace");
        File sessions = new File(dir, "sessions/b42");
        AgentConfig config = AgentConfig.parse("sessions.dir=" + sessions + ",build.id=b42,task.path=:app:\"run\\now\"");
        writer = new TraceWriterThread(traceFile, config, 1_789_672_321_000L);
        writer.start();
        server = new LiveServer(config, traceFile, 1_789_672_321_000L, writer);
        server.start();
        descriptor = new File(sessions, ProcessHandle.current().pid() + ".json");
        Tracer.paceSink = defs;
        Tracer.active = true; // a controller's leaving reverts the gate only while tracing is on
    }

    @AfterEach
    void endSession() throws Exception {
        for (Socket socket : sockets) socket.close();
        if (writer.isAlive()) writer.shutdown();
        server.shutdown();
        awaitThreads("coroutree-live-control", 0);
        // Whatever a failed test left of the JVM's gate: as configured, for the tests that come after.
        Pace gate = Pace.GATE;
        if (gate.root.paused) gate.command("resume");
        if (gate.root.intervalNanos != TracedJvm.INTERVAL_NANOS) gate.command("pace " + TracedJvm.INTERVAL_NANOS);
        Tracer.active = false;
        Tracer.paceSink = null;
    }

    // ------------------------------------------------------------------ fixtures

    private Socket connect(String token) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
        sockets.add(socket);
        socket.setSoTimeout(30_000);
        send(socket, token);
        return socket;
    }

    private Socket connect() throws IOException {
        return connect(server.token());
    }

    private static void send(Socket socket, String line) throws IOException {
        socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private void events(long from, long to) {
        for (long seq = from; seq <= to; seq++) {
            TraceEvent event = new TraceEvent(1, Wire.RESUMED, 1);
            event.seq = seq;
            writer.enqueue(event);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("never happened: " + what);
            Thread.sleep(2);
        }
    }

    private static int threads(String name) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) if (thread.getName().equals(name)) count++;
        return count;
    }

    private static void awaitThreads(String name, int count) throws InterruptedException {
        await(count + " threads named " + name, () -> threads(name) == count);
    }

    private TraceEvent.PaceDef awaitDef(int index) throws InterruptedException {
        await("setting number " + (index + 1) + " of the gate", () -> defs.size() > index);
        return defs.get(index);
    }

    /** Keeps what was read, to compare with the file. */
    private static final class Recorded extends FilterInputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Recorded(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) bytes.write(b);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) bytes.write(buffer, offset, read);
            return read;
        }
    }

    /** The value of a string field of the descriptor, unescaped the way any JSON reader would. */
    private static String jsonString(String json, String field) {
        int at = json.indexOf("\"" + field + "\":\"");
        if (at < 0) throw new AssertionError("no string field " + field + " in " + json);
        StringBuilder value = new StringBuilder();
        for (int i = at + field.length() + 4; ; i++) {
            char c = json.charAt(i);
            if (c == '"') return value.toString();
            if (c == '\\') {
                c = json.charAt(++i);
                if (c != '"' && c != '\\') throw new AssertionError("escape \\" + c + " in " + json);
            }
            value.append(c);
        }
    }

    private static String jsonValue(String json, String field) {
        int at = json.indexOf("\"" + field + "\":");
        if (at < 0) throw new AssertionError("no field " + field + " in " + json);
        int start = at + field.length() + 3;
        int end = start;
        while (json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end);
    }

    // ------------------------------------------------------------------ the session descriptor

    @Test
    void theDescriptorSaysWhereTheSessionIsAndThatItEndedInOrder() throws Exception {
        String json = Files.readString(descriptor.toPath()).trim();
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        assertEquals(Long.toString(ProcessHandle.current().pid()), jsonValue(json, "pid"));
        assertEquals(Integer.toString(server.port()), jsonValue(json, "port"));
        assertEquals(server.token(), jsonString(json, "token"));
        assertTrue(server.token().matches("[0-9a-f]{32}"), server.token());
        assertEquals(traceFile.getPath(), jsonString(json, "traceFile"));
        assertEquals(":app:\"run\\now\"", jsonString(json, "taskPath"), "whatever is in a value, the file stays JSON");
        assertEquals("b42", jsonString(json, "buildId"));
        assertEquals(System.getProperty("sun.java.command", ""), jsonString(json, "command"));
        assertEquals("1789672321000", jsonValue(json, "startedAt"));
        assertEquals("true", jsonValue(json, "paceable"), "with a gate in the JVM the descriptor says so");
        assertEquals("false", jsonValue(json, "ended"));

        writer.shutdown();
        server.shutdown();
        String ended = Files.readString(descriptor.toPath()).trim();
        assertEquals("true", jsonValue(ended, "ended"));
        assertEquals(json.replace("\"ended\":false", "\"ended\":true"), ended, "nothing else changes");
        assertFalse(new File(descriptor.getPath() + ".tmp").exists(), "written aside and moved into place: nothing is left aside");
    }

    // ------------------------------------------------------------------ the stream

    @Test
    void aLateJoinerGetsTheTraceFromItsFirstByteThenWhatIsAppendedThenTheEnd() throws Exception {
        events(1, 100);
        await("the writer to write 100 events", () -> TraceFiles.read(traceFile).size() == 101);

        Socket socket = connect();
        Recorded received = new Recorded(socket.getInputStream());
        TraceReader reader = new TraceReader(received);
        assertNotNull(reader.next().getHeader(), "magic, header, everything so far");
        for (long seq = 1; seq <= 100; seq++) assertEquals(seq, reader.next().getEvent().getSeq());

        events(101, 150);
        for (long seq = 101; seq <= 150; seq++) assertEquals(seq, reader.next().getEvent().getSeq(), "frames as they are written");

        events(151, 160);
        writer.shutdown();
        server.shutdown();
        for (long seq = 151; seq <= 160; seq++) assertEquals(seq, reader.next().getEvent().getSeq(), "to the last byte when the JVM goes down");
        assertNull(reader.next(), "then the stream ends");
        assertArrayEquals(Files.readAllBytes(traceFile.toPath()), received.bytes.toByteArray(), "byte for byte the file");
    }

    @Test
    void aWrongTokenGetsTheConnectionClosedAndNotAByteOfTheTrace() throws Exception {
        events(1, 10);
        for (String token : new String[] {"", "0123456789abcdef0123456789abcdef", server.token() + "x", server.token().substring(1), "x".repeat(100_000)}) {
            int first;
            try {
                Socket socket = connect(token);
                first = socket.getInputStream().read();
            } catch (IOException reset) {
                first = -1; // closed with what the client sent still unread: a reset is as closed as it gets
            }
            assertEquals(-1, first, "token of " + token.length() + " characters");
        }
        // And none of it was in the way of a client that knows the token.
        assertNotNull(new TraceReader(connect().getInputStream()).next().getHeader());
    }

    @Test
    void aClientThatWentAwayIsNobodysProblem() throws Exception {
        Socket gone = connect();
        assertNotNull(new TraceReader(gone.getInputStream()).next().getHeader());
        gone.close();
        events(1, 2_000);

        Socket socket = connect();
        TraceReader reader = new TraceReader(socket.getInputStream());
        assertNotNull(reader.next().getHeader());
        events(2_001, 2_010);
        writer.shutdown();
        long before = System.nanoTime();
        server.shutdown();
        assertTrue(System.nanoTime() - before < 2_500_000_000L, "shutdown waits for clients that are reading, not for one that left");
        int events = 0;
        for (Frame frame = reader.next(); frame != null; frame = reader.next()) events++;
        assertEquals(2_010, events);
        awaitThreads("coroutree-live-client", 0);
    }

    @Test
    void aClientThatDoesNotReadHoldsUpNeitherTheCaptureNorTheOthersAndIsCutOffAtTheEnd() throws Exception {
        connect(); // and never reads
        Socket reading = connect();
        Recorded received = new Recorded(reading.getInputStream());
        TraceReader reader = new TraceReader(received);
        assertNotNull(reader.next().getHeader());

        // Far more than the buffers of a socket take: the thread that serves the stalled client is stuck in its write.
        String chunk = "x".repeat(64 * 1024);
        int chunks = 256;
        for (int i = 0; i < chunks; i++) writer.enqueue(new TraceEvent.DiagnosticDef(Wire.INFO, chunk));
        for (int i = 0; i < chunks; i++) assertEquals(chunk.length(), reader.next().getDiagnostic().getMessage().length());

        writer.shutdown();
        assertTrue(writer.isClosed());
        assertEquals(1 + chunks, TraceFiles.read(traceFile).size(), "the file has everything");
        assertNull(reader.next(), "and so has the client that reads: its stream ends with the trace");
        assertArrayEquals(Files.readAllBytes(traceFile.toPath()), received.bytes.toByteArray());
        awaitThreads("coroutree-live-client", 1); // the one that serves the stalled client, still in its write

        server.shutdown(); // gives it its moment, then cuts it off
        awaitThreads("coroutree-live-client", 0);
        assertEquals("true", jsonValue(Files.readString(descriptor.toPath()).trim(), "ended"));
    }

    // ------------------------------------------------------------------ commands

    @Test
    void commandsOfAClientSetTheGateAndItsLeavingPutsEverythingBackAsConfigured() throws Exception {
        Socket socket = connect();
        send(socket, "pace 7000000\r"); // either line end
        TraceEvent.PaceDef pace = awaitDef(0);
        assertEquals(Wire.PACE_CONTROLLER, pace.reason);
        assertEquals(7_000_000, pace.intervalNanos);
        assertEquals(0, pace.scopeNodeId);
        assertFalse(pace.paused);

        send(socket, "step 3");
        TraceEvent.PaceDef step = awaitDef(1);
        assertTrue(step.paused, "asked of a running program, step stops it after that many");
        assertEquals(3, step.steps);
        assertTrue(Pace.GATE.root.paused);

        socket.close(); // a GUI that crashed must not leave the program stopped
        TraceEvent.PaceDef failOpen = awaitDef(2);
        assertEquals(Wire.PACE_FAIL_OPEN, failOpen.reason);
        assertFalse(failOpen.paused);
        assertEquals(TracedJvm.INTERVAL_NANOS, failOpen.intervalNanos, "the configured pace, not the one the controller had set");
        assertFalse(Pace.GATE.root.paused);
        assertEquals(3, defs.size());
    }

    @Test
    void aLineThatIsNotACommandIsNothingAndALongOneIsNotItsTail() throws Exception {
        Socket socket = connect();
        send(socket, "hello");
        send(socket, "");
        send(socket, "pause now please");
        // More than a command can be long. Cut to its first 256 characters or to its tail it would read "pause".
        send(socket, "pause" + " ".repeat(300) + "pause");
        send(socket, " ".repeat(300) + "pause");
        send(socket, "pause ".repeat(10_000));
        send(socket, "pace 3000000");
        TraceEvent.PaceDef pace = awaitDef(0);
        assertEquals(3_000_000, pace.intervalNanos, "the first line that was a command");
        assertFalse(pace.paused);
        assertFalse(Pace.GATE.root.paused);
        assertEquals(1, defs.size());
    }

    @Test
    void aLineThatNeverEndsClosesTheConnection() throws Exception {
        Socket socket = connect();
        send(socket, "pause");
        awaitDef(0);
        // No line feed in sight: the reader gives up on the client, which is thereby a controller that left.
        byte[] endless = "pause ".repeat(20_000).getBytes(StandardCharsets.US_ASCII);
        try {
            socket.getOutputStream().write(endless);
            socket.getOutputStream().flush();
        } catch (IOException closedAlready) {
            // which is the point
        }
        TraceEvent.PaceDef failOpen = awaitDef(1);
        assertEquals(Wire.PACE_FAIL_OPEN, failOpen.reason);
        assertFalse(Pace.GATE.root.paused);
    }

    @Test
    void onlyAClientThatSentACommandIsAControllerAndOnlyTheLastOneLeavingOpensTheGate() throws Exception {
        Socket first = connect();
        Socket watcher = connect();
        Socket second = connect();
        awaitThreads("coroutree-live-control", 3);

        send(first, "pause");
        assertTrue(awaitDef(0).paused);
        send(watcher, "what is going on");

        watcher.close(); // it only watched: its leaving is nothing
        awaitThreads("coroutree-live-control", 2);
        assertTrue(Pace.GATE.root.paused);

        send(second, "pace 2000000");
        assertEquals(2_000_000, awaitDef(1).intervalNanos);
        assertTrue(defs.get(1).paused, "every setting is said whole: the pace changed, the pause stands");

        first.close(); // another controller is still there
        awaitThreads("coroutree-live-control", 1);
        assertTrue(Pace.GATE.root.paused);
        assertEquals(2, defs.size());

        second.close();
        assertEquals(Wire.PACE_FAIL_OPEN, awaitDef(2).reason);
        assertFalse(Pace.GATE.root.paused);
    }

    @Test
    void theWarmUpConnectionLeavesNoMark() throws Exception {
        server.warmUp();
        awaitThreads("coroutree-live-control", 0);
        assertEquals(List.of(), defs, "it says something, which is not a command, and nobody became a controller");
    }
}
