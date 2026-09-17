package kotlinx.coroutree.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Live stream: a loopback-only TCP socket on a random port. A client sends the session token and a line feed, and
 * gets exactly what the trace file contains — from its first byte, then on as it grows. Literally so: every client
 * has a thread that reads the trace file back and follows it like {@code tail -f}, which makes a late joiner and a
 * slow reader the same, unremarkable case and keeps both away from the thread that writes the trace.
 *
 * Port and token are published in a session descriptor, {@code <sessions dir>/<pid>.json}, which is how the GUI finds
 * running JVMs. The descriptor is rewritten with {@code "ended": true} on orderly shutdown.
 */
final class LiveServer extends AgentThread {
    private static final int TOKEN_TIMEOUT_MILLIS = 5000;
    private static final long POLL_NANOS = 5_000_000;             // 5 ms
    private static final long CLIENT_DRAIN_NANOS = 3_000_000_000L; // 3 s

    private final List<Client> clients = new ArrayList<>();

    private final ServerSocket server;
    private final String token;
    private final TraceWriterThread writer;
    private final File descriptor;
    private final String descriptorFields;

    LiveServer(AgentConfig config, File traceFile, long startedAtEpochMillis, TraceWriterThread writer) throws IOException {
        super("coroutree-live");
        this.writer = writer;
        this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder();
        for (byte b : random) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        this.token = hex.toString();

        long pid = ProcessHandle.current().pid();
        this.descriptor = config.sessionsDir.isEmpty() ? null : new File(config.sessionsDir, pid + ".json");
        this.descriptorFields = "\"pid\":" + pid
            + ",\"port\":" + server.getLocalPort()
            + ",\"token\":" + json(token)
            + ",\"traceFile\":" + json(traceFile.getPath())
            + ",\"taskPath\":" + json(config.taskPath)
            + ",\"buildId\":" + json(config.buildId)
            + ",\"command\":" + json(System.getProperty("sun.java.command", ""))
            + ",\"startedAt\":" + startedAtEpochMillis;
        writeDescriptor(false);
    }

    int port() {
        return server.getLocalPort();
    }

    String token() {
        return token;
    }

    @Override
    public void run() {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return; // closed on shutdown
            }
            try {
                socket.setSoTimeout(TOKEN_TIMEOUT_MILLIS);
                if (readLine(socket.getInputStream()).equals(token)) {
                    socket.setSoTimeout(0);
                    socket.setTcpNoDelay(true);
                    Client client = new Client(socket);
                    synchronized (clients) {
                        clients.add(client);
                    }
                    client.start();
                } else {
                    socket.close();
                }
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Stops accepting, gives the clients a moment to receive the end of the trace, and marks the session as ended. */
    void shutdown() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
        long deadline = System.nanoTime() + CLIENT_DRAIN_NANOS;
        List<Client> current;
        synchronized (clients) {
            current = new ArrayList<>(clients);
        }
        for (Client client : current) {
            try {
                client.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
            } catch (InterruptedException e) {
                break;
            }
            client.close(); // a client that could not keep up is cut off; the file has everything
        }
        writeDescriptor(true);
    }

    /** Sends the trace file, as far as it has been written, and then whatever is appended to it. */
    private final class Client extends AgentThread {
        private final Socket socket;

        Client(Socket socket) {
            super("coroutree-live-client");
            this.socket = socket;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1 << 16];
            try (InputStream trace = new FileInputStream(writer.file); OutputStream out = socket.getOutputStream()) {
                while (true) {
                    boolean complete = writer.isClosed(); // read before reading the file: no tail is missed
                    int read = trace.read(buffer);
                    if (read > 0) {
                        out.write(buffer, 0, read);
                    } else if (complete) {
                        break;
                    } else {
                        LockSupport.parkNanos(POLL_NANOS);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // The client went away, or was cut off by close().
            } finally {
                close();
                synchronized (clients) {
                    clients.remove(this);
                }
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c >= 0 && c != '\n' && line.length() < 256; c = in.read()) {
            if (c != '\r') line.append((char) c);
        }
        return line.toString();
    }

    private void writeDescriptor(boolean ended) {
        if (descriptor == null) return;
        try {
            descriptor.getParentFile().mkdirs();
            // Written aside and moved into place: a reader polling the directory never sees half a file.
            File temp = new File(descriptor.getPath() + ".tmp");
            Files.write(temp.toPath(), ("{" + descriptorFields + ",\"ended\":" + ended + "}\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), descriptor.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Tracer.reportInternalError("cannot write session descriptor " + descriptor, e);
        }
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }
}
