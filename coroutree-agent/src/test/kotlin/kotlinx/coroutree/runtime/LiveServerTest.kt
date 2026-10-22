package kotlinx.coroutree.runtime

import kotlinx.coroutree.model.TraceReader
import kotlinx.coroutree.runtime.TraceEvent.DiagnosticDef
import kotlinx.coroutree.runtime.TraceEvent.PaceDef
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The live socket (TRACE_FORMAT, "Live stream"). A client gets the trace file byte for byte from its first byte and
 * then as it grows, whenever it joins and however it reads. With a gate in the JVM a client can send commands, which
 * makes it a controller, and a controller that leaves must not leave the program stopped.
 */
@Timeout(60)
internal class LiveServerTest {
    @TempDir
    lateinit var dir: File

    private lateinit var traceFile: File
    private lateinit var descriptor: File
    private lateinit var session: SessionDescriptor
    private lateinit var writer: TraceWriterThread
    private lateinit var server: LiveServer
    private val sockets = ArrayList<Socket>()
    private val defs: MutableList<PaceDef> = CopyOnWriteArrayList()

    @BeforeEach
    fun startSession() {
        assertNotNull(Pace.GATE, "the test JVM was to have a gate")
        traceFile = File(dir, "run.ctrace")
        val sessions = File(dir, "sessions/b42")
        val config = AgentConfig.parse("sessions.dir=$sessions,build.id=b42,task.path=:app:\"run\\now\"")
        writer = TraceWriterThread(traceFile, config, 1789672321000L)
        writer.start()
        server = LiveServer(writer)
        server.start()
        session = SessionDescriptor(config, traceFile, 1789672321000L, server)
        session.write(false)
        descriptor = File(sessions, "${ProcessHandle.current().pid()}.json")
        Tracer.paceSink = defs
        Tracer.active = true // the gate reverts on a controller's leaving only while tracing is on
    }

    @AfterEach
    fun endSession() {
        for (socket in sockets) socket.close()
        if (writer.isAlive) writer.shutdown()
        server.shutdown()
        awaitThreads("coroutree-live-control", 0)
        // A failed test can leave the JVM's one gate paused or at another pace. The tests after it need it as configured.
        val gate = Pace.GATE
        if (gate.root.paused) gate.command("resume")
        if (gate.root.intervalNanos != TracedJvm.INTERVAL_NANOS) gate.command("pace ${TracedJvm.INTERVAL_NANOS}")
        Tracer.active = false
        Tracer.paceSink = null
    }

    // ------------------------------------------------------------------ fixtures

    private fun connect(token: String = server.token()): Socket {
        val socket = Socket(InetAddress.getLoopbackAddress(), server.port())
        sockets.add(socket)
        socket.soTimeout = 30_000
        send(socket, token)
        return socket
    }

    private fun events(from: Long, to: Long) {
        for (seq in from..to) {
            val event = TraceEvent(1, Wire.RESUMED, 1)
            event.seq = seq
            writer.enqueue(event)
        }
    }

    private fun awaitDef(index: Int): PaceDef {
        await("setting number ${index + 1} of the gate") { defs.size > index }
        return defs[index]
    }

    /** Records what was read, to compare with the file. */
    private class Recorded(input: InputStream) : FilterInputStream(input) {
        val bytes = ByteArrayOutputStream()

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) bytes.write(b)
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) bytes.write(buffer, offset, read)
            return read
        }
    }

    // ------------------------------------------------------------------ the session descriptor

    @Test
    fun theDescriptorSaysWhereTheSessionIsAndThatItEndedInOrder() {
        val json = Files.readString(descriptor.toPath()).trim()
        assertTrue(json.startsWith("{") && json.endsWith("}"), json)
        assertEquals(ProcessHandle.current().pid().toString(), jsonValue(json, "pid"))
        assertEquals(server.port().toString(), jsonValue(json, "port"))
        assertEquals(server.token(), jsonString(json, "token"))
        assertTrue(server.token().matches(Regex("[0-9a-f]{32}")), server.token())
        assertEquals(traceFile.path, jsonString(json, "traceFile"))
        assertEquals(":app:\"run\\now\"", jsonString(json, "taskPath"), "whatever is in a value, the file stays JSON")
        assertEquals("b42", jsonString(json, "buildId"))
        assertEquals(System.getProperty("sun.java.command", ""), jsonString(json, "command"))
        assertEquals("1789672321000", jsonValue(json, "startedAt"))
        assertEquals("true", jsonValue(json, "paceable"), "with a gate in the JVM the descriptor says so")
        assertEquals("false", jsonValue(json, "ended"))

        writer.shutdown()
        server.shutdown()
        session.write(true)
        val ended = Files.readString(descriptor.toPath()).trim()
        assertEquals("true", jsonValue(ended, "ended"))
        assertEquals(json.replace("\"ended\":false", "\"ended\":true"), ended, "nothing else changes")
        assertFalse(File(descriptor.path + ".tmp").exists(), "written aside and moved into place: nothing is left aside")
    }

    @Test
    fun withoutALiveSocketTheDescriptorStillSaysWhereTheTraceIs() {
        val config = AgentConfig.parse("sessions.dir=${File(dir, "offline")},live=false,task.path=:app:test")
        SessionDescriptor(config, traceFile, 7L, null).write(false)
        val json = Files.readString(File(dir, "offline/${ProcessHandle.current().pid()}.json").toPath()).trim()
        assertFalse("\"port\"" in json, json)
        assertFalse("\"token\"" in json, json)
        assertEquals(traceFile.path, jsonString(json, "traceFile"))
        assertEquals("false", jsonValue(json, "paceable"), "nobody could ever send a command")
        assertEquals("false", jsonValue(json, "ended"))

        assertEquals(File(System.getProperty("user.home"), ".coroutree/sessions").path, AgentConfig.parse("").sessionsDir, "the directory the GUI watches, unless told otherwise")
        assertEquals("", AgentConfig.parse("sessions.dir=").sessionsDir, "set empty: no descriptor")
    }

    // ------------------------------------------------------------------ the stream

    @Test
    fun aLateJoinerGetsTheTraceFromItsFirstByteThenWhatIsAppendedThenTheEnd() {
        events(1, 100)
        await("the writer to write 100 events") { TraceFiles.read(traceFile).size == 101 }

        val socket = connect()
        val received = Recorded(socket.getInputStream())
        val reader = TraceReader(received)
        assertNotNull(reader.next()!!.header, "magic, header, everything so far")
        for (seq in 1L..100L) assertEquals(seq, reader.next()!!.event!!.seq)

        events(101, 150)
        for (seq in 101L..150L) assertEquals(seq, reader.next()!!.event!!.seq, "frames as they are written")

        events(151, 160)
        writer.shutdown()
        server.shutdown()
        for (seq in 151L..160L) assertEquals(seq, reader.next()!!.event!!.seq, "to the last byte when the JVM goes down")
        assertNull(reader.next(), "then the stream ends")
        assertArrayEquals(Files.readAllBytes(traceFile.toPath()), received.bytes.toByteArray(), "byte for byte the file")
    }

    @Test
    fun aWrongTokenGetsTheConnectionClosedAndNotAByteOfTheTrace() {
        events(1, 10)
        for (token in listOf("", "0123456789abcdef0123456789abcdef", server.token() + "x", server.token().substring(1), "x".repeat(100_000))) {
            val first = try {
                connect(token).getInputStream().read()
            } catch (reset: IOException) {
                -1 // the server closes with the client's bytes still unread, which the client gets as a reset, not as end of stream
            }
            assertEquals(-1, first, "token of ${token.length} characters")
        }
        // None of it blocks a client with the right token.
        assertNotNull(TraceReader(connect().getInputStream()).next()!!.header)
    }

    @Test
    fun aClientThatWentAwayIsNobodysProblem() {
        val gone = connect()
        assertNotNull(TraceReader(gone.getInputStream()).next()!!.header)
        gone.close()
        events(1, 2000)

        val socket = connect()
        val reader = TraceReader(socket.getInputStream())
        assertNotNull(reader.next()!!.header)
        events(2001, 2010)
        writer.shutdown()
        val before = System.nanoTime()
        server.shutdown()
        assertTrue(System.nanoTime() - before < 2_500_000_000L, "shutdown waits for clients that are reading, not for one that left")
        val events = generateSequence { reader.next() }.count()
        assertEquals(2010, events)
        awaitThreads("coroutree-live-client", 0)
    }

    @Test
    fun aClientThatDoesNotReadHoldsUpNeitherTheCaptureNorTheOthersAndIsCutOffAtTheEnd() {
        connect() // and never reads
        val reading = connect()
        val received = Recorded(reading.getInputStream())
        val reader = TraceReader(received)
        assertNotNull(reader.next()!!.header)

        // Far more than a socket's buffers hold (16 MB): the thread that serves the stalled client blocks in its write.
        val chunk = "x".repeat(64 * 1024)
        val chunks = 256
        repeat(chunks) { writer.enqueue(DiagnosticDef(Wire.INFO, chunk)) }
        repeat(chunks) { assertEquals(chunk.length, reader.next()!!.diagnostic!!.message.length) }

        writer.shutdown()
        assertTrue(writer.isClosed())
        assertEquals(1 + chunks, TraceFiles.read(traceFile).size, "the file has everything")
        assertNull(reader.next(), "and so has the client that reads: its stream ends with the trace")
        assertArrayEquals(Files.readAllBytes(traceFile.toPath()), received.bytes.toByteArray())
        awaitThreads("coroutree-live-client", 1) // the one that serves the stalled client, still in its write

        server.shutdown() // waits up to 3 s for it, then closes its socket
        awaitThreads("coroutree-live-client", 0)
    }

    // ------------------------------------------------------------------ commands

    @Test
    fun commandsOfAClientSetTheGateAndItsLeavingPutsEverythingBackAsConfigured() {
        val socket = connect()
        send(socket, "pace 7000000\r") // CR LF is a line end too
        val pace = awaitDef(0)
        assertEquals(Wire.PACE_CONTROLLER, pace.reason)
        assertEquals(7_000_000, pace.intervalNanos)
        assertEquals(0, pace.scopeNodeId)
        assertFalse(pace.paused)

        send(socket, "step 3")
        val step = awaitDef(1)
        assertTrue(step.paused, "asked of a running program, step stops it after that many")
        assertEquals(3, step.steps)
        assertTrue(Pace.GATE.root.paused)

        socket.close() // a GUI that crashed must not leave the program stopped
        val failOpen = awaitDef(2)
        assertEquals(Wire.PACE_FAIL_OPEN, failOpen.reason)
        assertFalse(failOpen.paused)
        assertEquals(TracedJvm.INTERVAL_NANOS, failOpen.intervalNanos, "the configured pace, not the one the controller had set")
        assertFalse(Pace.GATE.root.paused)
        assertEquals(3, defs.size)
    }

    @Test
    fun aLineThatIsNotACommandIsNothingAndALongOneIsNotItsTail() {
        val socket = connect()
        send(socket, "hello")
        send(socket, "")
        send(socket, "pause now please")
        // Longer than any command (256 characters). Cut to its head or to its tail, it reads "pause".
        send(socket, "pause" + " ".repeat(300) + "pause")
        send(socket, " ".repeat(300) + "pause")
        send(socket, "pause ".repeat(10_000))
        send(socket, "pace 3000000")
        val pace = awaitDef(0)
        assertEquals(3_000_000, pace.intervalNanos, "the first line that was a command")
        assertFalse(pace.paused)
        assertFalse(Pace.GATE.root.paused)
        assertEquals(1, defs.size)
    }

    @Test
    fun aLineThatNeverEndsClosesTheConnection() {
        val socket = connect()
        send(socket, "pause")
        awaitDef(0)
        // No line feed in 120 000 bytes: the server closes the connection at 65 536, so the controller has left.
        val endless = "pause ".repeat(20_000).toByteArray(Charsets.US_ASCII)
        try {
            socket.getOutputStream().write(endless)
            socket.getOutputStream().flush()
        } catch (closedAlready: IOException) {
            // expected: the server closed the connection first
        }
        val failOpen = awaitDef(1)
        assertEquals(Wire.PACE_FAIL_OPEN, failOpen.reason)
        assertFalse(Pace.GATE.root.paused)
    }

    @Test
    fun onlyAClientThatSentACommandIsAControllerAndOnlyTheLastOneLeavingOpensTheGate() {
        val first = connect()
        val watcher = connect()
        val second = connect()
        awaitThreads("coroutree-live-control", 3)

        send(first, "pause")
        assertTrue(awaitDef(0).paused)
        send(watcher, "what is going on")

        watcher.close() // sent no command, so it isn't a controller
        awaitThreads("coroutree-live-control", 2)
        assertTrue(Pace.GATE.root.paused)

        send(second, "pace 2000000")
        assertEquals(2_000_000, awaitDef(1).intervalNanos)
        assertTrue(defs[1].paused, "every setting is said whole: the pace changed, the pause stands")

        first.close() // another controller is still there
        awaitThreads("coroutree-live-control", 1)
        assertTrue(Pace.GATE.root.paused)
        assertEquals(2, defs.size)

        second.close()
        assertEquals(Wire.PACE_FAIL_OPEN, awaitDef(2).reason)
        assertFalse(Pace.GATE.root.paused)
    }

    @Test
    fun theWarmUpConnectionLeavesNoMark() {
        server.warmUp()
        awaitThreads("coroutree-live-control", 0)
        assertEquals(emptyList<PaceDef>(), defs, "it says something, which is not a command, and nobody became a controller")
    }

    companion object {
        init {
            // Pace.GATE is made once per JVM, so the config must be PaceTest's, whichever class loads first.
            if (Tracer.config == null) Tracer.config = AgentConfig.parse(TracedJvm.GATE_OPTIONS)
        }

        private fun send(socket: Socket, line: String) {
            socket.getOutputStream().write("$line\n".toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
        }

        private fun await(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!condition()) {
                if (System.nanoTime() > deadline) throw AssertionError("never happened: $what")
                Thread.sleep(2)
            }
        }

        private fun threads(name: String): Int = Thread.getAllStackTraces().keys.count { it.name == name }

        private fun awaitThreads(name: String, count: Int) {
            await("$count threads named $name") { threads(name) == count }
        }

        /** The value of a string field of the descriptor, with its JSON escapes removed. */
        private fun jsonString(json: String, field: String): String {
            val at = json.indexOf("\"$field\":\"")
            if (at < 0) throw AssertionError("no string field $field in $json")
            val value = StringBuilder()
            var i = at + field.length + 4
            while (true) {
                var c = json[i]
                if (c == '"') return value.toString()
                if (c == '\\') {
                    c = json[++i]
                    if (c != '"' && c != '\\') throw AssertionError("escape \\$c in $json")
                }
                value.append(c)
                i++
            }
        }

        private fun jsonValue(json: String, field: String): String {
            val at = json.indexOf("\"$field\":")
            if (at < 0) throw AssertionError("no field $field in $json")
            val start = at + field.length + 3
            var end = start
            while (json[end] != ',' && json[end] != '}') end++
            return json.substring(start, end)
        }
    }
}
