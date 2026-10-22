package kotlinx.coroutree.runtime;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * A loopback client authenticates with the session token and a line feed, then receives the trace from its first byte.
 * Each client tails the file on a separate thread, so slow readers can't block trace capture.
 *
 * With a gate, a separate thread reads each client's control commands after authentication, one on each line.
 * Command results appear in the trace stream for every client. There is no direct reply.
 * The first valid command makes a client a controller. When the last controller disconnects, the program resumes at its configured pace.
 * Without a gate, client input after authentication is ignored.
 *
 * Port and token are published by the {@link SessionDescriptor}, which is how the GUI finds running JVMs.
 */
final class LiveServer extends AgentThread {
    private static final int TOKEN_TIMEOUT_MILLIS = 5000;
    private static final long POLL_NANOS = 5_000_000;             // 5 ms
    private static final long CLIENT_DRAIN_NANOS = 3_000_000_000L; // 3 s

    private final List<Client> clients = new ArrayList<>();

    private final ServerSocket server;
    private final String token;
    private final TraceWriterThread writer;

    LiveServer(TraceWriterThread writer) throws IOException {
        super("coroutree-live");
        this.writer = writer;
        this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder();
        for (byte b : random) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        this.token = hex.toString();
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
                if (token.equals(readLine(socket.getInputStream()))) {
                    socket.setSoTimeout(0);
                    socket.setTcpNoDelay(true);
                    Client client = new Client(socket);
                    synchronized (clients) {
                        clients.add(client);
                    }
                    client.start();
                    if (Pace.GATE != null) new Control(socket, Pace.GATE).start();
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

    /** Stops accepting and gives the clients a moment to receive the end of the trace. */
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
            client.close(); // slow clients can read the complete trace from the file
        }
    }

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

    /**
     * Must not depend on threads held at the gate. {@link Pace#command} documents the command-path constraints.
     */
    private static final class Control extends AgentThread {
        private final Socket socket;
        private final Pace gate;

        Control(Socket socket, Pace gate) {
            super("coroutree-live-control");
            this.socket = socket;
            this.gate = gate;
        }

        @Override
        public void run() {
            boolean controller = false;
            try {
                InputStream in = socket.getInputStream();
                while (true) {
                    String line = readLine(in);
                    if (line == null) break;
                    if (gate.command(line) && !controller) {
                        controller = true;
                        gate.controllerJoined();
                    }
                }
            } catch (IOException | RuntimeException e) {
                // The client went away.
            } finally {
                if (controller) gate.controllerLeft();
            }
        }
    }

    /**
     * Loads connection and command dependencies before application threads can be held during class loading.
     */
    void warmUp() {
        try (Socket socket = new Socket()) {
            socket.connect(server.getLocalSocketAddress(), 1000);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write((token + "\nwarm-up\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.getInputStream().read();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * Oversized lines must be rejected whole so their tails can't become commands.
     * Lines over 256 characters return empty. Lines over 65536 bytes throw, which closes the connection.
     */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c = in.read();
        if (c < 0) return null;
        int length = 0;
        for (; c >= 0 && c != '\n'; c = in.read()) {
            if (++length > 65536) throw new IOException("line longer than 65536 bytes");
            if (c != '\r' && line.length() <= 256) line.append((char) c);
        }
        return line.length() > 256 ? "" : line.toString();
    }
}
