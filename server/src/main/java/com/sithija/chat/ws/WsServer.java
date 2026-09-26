package com.sithija.chat.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Hand-written WebSocket server (RFC 6455) on a raw ServerSocket.
 *
 * Why not Spring WebSocket / Netty: the brief requires the core real-time layer to be
 * built by hand, one level below framework integration.
 *
 * Why blocking I/O + virtual threads instead of NIO selectors: one virtual thread per
 * connection keeps the code sequential and readable (read header, read length, read
 * payload), while virtual threads park cheaply on blocking reads, so thousands of idle
 * sockets don't cost thousands of OS threads. NIO would scale similarly but forces a
 * state machine for partially-received frames, which is where most hand-rolled
 * WebSocket bugs live.
 */
public class WsServer {

    private static final Logger log = LoggerFactory.getLogger(WsServer.class);
    // Separate logger so heartbeat activity can be switched to DEBUG on its own
    // (logging.level.com.sithija.chat.ws.heartbeat=DEBUG) without also logging message contents,
    // which WsServer logs at DEBUG.
    private static final Logger heartbeatLog = LoggerFactory.getLogger("com.sithija.chat.ws.heartbeat");

    // Fixed by RFC 6455 section 1.3. Its only purpose is to prove the server actually
    // understands WebSocket, so a plain HTTP server or cache can't accidentally "accept".
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // Limits on the handshake: without them a client could send an endless header line
    // or thousands of headers and exhaust memory before we ever reject it.
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADERS = 100;

    // A client that opens a TCP connection and never finishes the handshake
    // (slowloris-style) would otherwise hold a socket forever.
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    // Chat messages are small. The cap bounds how much heap one client can make us hold
    // while reassembling, which is what protects the server when there are thousands of connections.
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;

    // How long to wait for a client to finish closing after we reject it (see drainBeforeClose).
    private static final int CLOSE_DRAIN_MS = 1_000;

    private final WsProperties props;
    private final LongSupplier clock;
    // Shared builder so threads get sequential names (ws-conn-1, ws-conn-2...) in thread dumps.
    private final Thread.Builder connectionThreads = Thread.ofVirtual().name("ws-conn-", 1);

    // Concurrent: added and removed by connection threads, iterated by the sweeper and stop().
    // Iteration is weakly consistent, so it never throws ConcurrentModificationException.
    private final Set<WsConnection> connections = ConcurrentHashMap.newKeySet();
    // Open (or handshaking) connections per remote IP, for the reconnect-storm limit.
    private final ConcurrentHashMap<InetAddress, Integer> connectionsPerIp = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private Thread sweeper;
    // volatile: written by Spring's shutdown thread, read by the accept-loop thread.
    private volatile boolean running;

    public WsServer(WsProperties props, LongSupplier clock) {
        this.props = props;
        this.clock = clock;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(props.port());
        running = true;
        Thread.ofVirtual().name("ws-acceptor").start(this::acceptLoop);
        sweeper = Thread.ofVirtual().name("ws-heartbeat").start(this::sweepLoop);
        log.info("WebSocket server listening on port {}", serverSocket.getLocalPort());
    }

    public void stop() throws IOException, InterruptedException {
        running = false;
        // Closing the ServerSocket is the only way to unblock accept(); it throws a
        // SocketException, which the accept loop treats as a normal shutdown.
        if (serverSocket != null) {
            serverSocket.close();
        }
        if (sweeper != null) {
            sweeper.interrupt();
        }
        // 1001 Going Away lets clients tell a restart from a network failure and reconnect
        // calmly. Each Close is sent on its own thread, so one client that stopped reading
        // can't hold up shutdown for the rest.
        for (WsConnection conn : connections) {
            Thread.startVirtualThread(() -> {
                try {
                    conn.sendClose(1001, "Server shutting down");
                } catch (IOException e) {
                    // Already gone; it'll be closed below either way.
                }
            });
        }
        // A well-behaved client answers with its own Close, and its reader thread then removes it.
        long deadline = System.nanoTime() + props.shutdownGrace().toNanos();
        while (!connections.isEmpty() && System.nanoTime() - deadline < 0) {
            Thread.sleep(20);
        }
        for (WsConnection conn : connections) {
            closeQuietly(conn);
        }
    }

    /** The bound port. Useful when props.port() is 0 (tests pick a free ephemeral port). */
    int localPort() {
        return serverSocket.getLocalPort();
    }

    int connectionCount() {
        return connections.size();
    }

    // One thread for all connections: each sweep is a cheap pass over timestamps, versus
    // 10k+ scheduled timers that would each need creating, cancelling and rescheduling on
    // every message.
    private void sweepLoop() {
        while (running) {
            try {
                Thread.sleep(props.sweepInterval());
            } catch (InterruptedException e) {
                return; // stop() interrupts us
            }
            try {
                sweep();
            } catch (RuntimeException e) {
                // One bad sweep must not end heartbeats for every connection.
                log.error("Heartbeat sweep failed", e);
            }
        }
    }

    // Package-private so tests can run a sweep at a chosen fake time without waiting for the thread.
    void sweep() {
        long now = clock.getAsLong();
        for (WsConnection conn : connections) {
            switch (conn.checkLiveness(now, props)) {
                // Sent on its own virtual thread: sendPing() may wait for the write lock or on a full
                // socket buffer, and the sweeper itself must never block.
                case PING -> {
                    heartbeatLog.debug("Pinging {} (idle {}ms)", conn, nanosToMillis(now - conn.lastReceivedAt()));
                    Thread.startVirtualThread(() -> {
                        try {
                            conn.sendPing();
                        } catch (IOException e) {
                            // Connection is closing; the reader thread cleans it up.
                        }
                    });
                }
                // No Close handshake: a dead peer can't answer, and waiting would just hold the socket.
                case DEAD -> {
                    heartbeatLog.info("No response to ping from {}, closing", conn);
                    closeQuietly(conn);
                }
                // INFO, not DEBUG: this cuts off a live peer, and it's the first sign of a slow
                // consumer or backpressure problem.
                case STUCK_WRITE -> {
                    heartbeatLog.info("Write to {} stuck for {}ms (limit {}ms), force-closing", conn,
                            nanosToMillis(now - conn.writeStartedAt()), props.writeTimeout().toMillis());
                    closeQuietly(conn);
                }
                case OK -> { }
            }
        }
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000;
    }

    private static void closeQuietly(WsConnection conn) {
        try {
            conn.close();
        } catch (IOException e) {
            // Closing is best-effort; the socket is unusable either way.
        }
    }

    // compute() is atomic per key, so two handshakes racing from one IP can't both take the last slot.
    private boolean tryAdmit(InetAddress ip) {
        boolean[] admitted = {false};
        connectionsPerIp.compute(ip, (k, count) -> {
            int current = count == null ? 0 : count;
            if (current >= props.maxConnectionsPerIp()) {
                return count;
            }
            admitted[0] = true;
            return current + 1;
        });
        return admitted[0];
    }

    // Removes the entry at zero, so the map doesn't keep one entry for every IP ever seen.
    private void release(InetAddress ip) {
        connectionsPerIp.computeIfPresent(ip, (k, count) -> count == 1 ? null : count - 1);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                connectionThreads.start(() -> handleConnection(socket));
            } catch (SocketException e) {
                if (running) {
                    log.error("Accept failed", e);
                }
            } catch (IOException e) {
                log.error("Accept failed", e);
            }
        }
    }

    private void handleConnection(Socket socket) {
        InetAddress ip = socket.getInetAddress();
        // Decided at accept time, so half-finished handshakes count too, which also caps how
        // many slowloris sockets one IP can hold. The 429 is only sent once the request has
        // been read, so it's a proper HTTP response (see performHandshake).
        boolean admitted = tryAdmit(ip);
        // try-with-resources guarantees the socket is closed however this method exits,
        // so a crashed handler can't leak file descriptors.
        try (socket) {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            // Raw byte stream, NOT a BufferedReader: a Reader decodes bytes as text and
            // reads ahead, so it would swallow and mangle the first binary WebSocket frame
            // that can arrive right after the handshake. We also must keep using this SAME
            // buffered stream afterwards, because it may already hold those frame bytes.
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            if (!performHandshake(in, out, admitted)) {
                return;
            }
            log.info("Handshake OK from {}", socket.getRemoteSocketAddress());

            // After the handshake the connection is long-lived and idle most of the time,
            // so the read timeout must go. The heartbeat sweeper detects dead peers instead.
            socket.setSoTimeout(0);

            // Nagle's algorithm holds back small writes (up to ~40ms with delayed ACKs) hoping to
            // batch them. Chat frames are small and latency-sensitive, and each one already goes
            // out in a single write.
            socket.setTcpNoDelay(true);

            var addr = socket.getRemoteSocketAddress();
            // Wraps the SAME buffered stream the handshake used (see above), so frame bytes
            // that arrived together with the handshake aren't skipped.
            WsConnection conn = new WsConnection(socket, clock);
            WsFrameReader reader = new WsFrameReader(new DataInputStream(in), MAX_MESSAGE_BYTES, conn::markReceived);
            connections.add(conn);
            // The finally releases the slot even on exceptions, so the set can't leak.
            try (conn) {
                // Handshake finished after stop() already sent its 1001s: close instead of serving.
                if (!running) {
                    return;
                }
                try {
                    WsFrame message;
                    while ((message = reader.readMessage()) != null) {
                        switch (message.opcode()) {
                            // Echo for now; Phase 5 routes messages to their recipient instead.
                            case WsFrame.OP_TEXT -> {
                                String text = new String(message.payload(), StandardCharsets.UTF_8);
                                log.debug("Text from {}: {}", addr, text);
                                conn.sendText(text);
                            }
                            case WsFrame.OP_BINARY -> {
                                log.debug("Binary from {}: {} bytes", addr, message.payload().length);
                                conn.sendBinary(message.payload());
                            }
                            case WsFrame.OP_PING -> conn.sendPong(message.payload());
                            // Any frame, a pong included, already updated lastReceivedAt through
                            // markReceived. Section 5.5.3 allows unsolicited pongs too.
                            case WsFrame.OP_PONG -> heartbeatLog.debug("Pong from {}", addr);
                            case WsFrame.OP_CLOSE -> {
                                byte[] payload = message.payload();
                                conn.onCloseReceived(payload);
                                if (WsConnection.isValidClosePayload(payload)) {
                                    log.info("Close from {} (code {})", addr, WsConnection.closeCode(payload));
                                } else {
                                    log.info("Invalid Close payload from {} ({} bytes), replied 1002", addr, payload.length);
                                }
                                // Returning closes TCP here. Section 7.1.1: the server should close
                                // first, so the TIME_WAIT state lands on the server, not the client.
                                return;
                            }
                        }
                    }
                    log.info("Connection closed by {} without a Close frame", addr);
                } catch (WsProtocolException e) {
                    log.info("Protocol violation from {}: {} (close code {})", addr, e.getMessage(), e.closeCode());
                    // Tell the client why; otherwise it only sees 1006 "abnormal closure".
                    conn.sendClose(e.closeCode(), e.getMessage());
                    drainBeforeClose(socket, in);
                }
            } finally {
                connections.remove(conn);
            }
        } catch (IOException e) {
            log.debug("Connection error: {}", e.getMessage());
        } finally {
            if (admitted) {
                release(ip);
            }
        }
    }

    /**
     * Used after we send a Close because of a protocol error, when the rest of the client's
     * bad message may still be unread. Closing a socket with unread input makes the OS send a
     * TCP reset (RST) instead of a normal close (FIN). A reset can make the client discard our
     * Close frame before reading it, so it only sees 1006.
     * So we half-close (a FIN goes out after the Close frame), then read and discard until the
     * client closes its side. The deadline stops a client that keeps sending from holding us.
     */
    private static void drainBeforeClose(Socket socket, InputStream in) {
        try {
            socket.shutdownOutput();
            byte[] discard = new byte[8192];
            long deadline = System.nanoTime() + CLOSE_DRAIN_MS * 1_000_000L;
            long remainingMs;
            while ((remainingMs = (deadline - System.nanoTime()) / 1_000_000) > 0) {
                socket.setSoTimeout((int) remainingMs);
                if (in.read(discard) == -1) {
                    return; // client closed its side: a clean close
                }
            }
        } catch (IOException e) {
            // Timeout or reset: we tried, and the socket is closed right after either way.
        }
    }

    /** Returns true if the upgrade succeeded; otherwise writes an HTTP error and returns false. */
    private boolean performHandshake(InputStream in, OutputStream out, boolean admitted) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || !requestLine.startsWith("GET ")) {
            // RFC 6455 section 4.1: the opening handshake must be an HTTP GET.
            writeHttpError(out, "400 Bad Request");
            return false;
        }

        Map<String, String> headers = readHeaders(in);
        if (headers == null) {
            writeHttpError(out, "400 Bad Request");
            return false;
        }

        boolean isUpgrade = "websocket".equalsIgnoreCase(headers.get("upgrade"));
        // contains(), not equals(): Firefox sends "Connection: keep-alive, Upgrade".
        boolean hasConnectionUpgrade =
                headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade");
        String key = headers.get("sec-websocket-key");

        if (!isUpgrade || !hasConnectionUpgrade || key == null) {
            writeHttpError(out, "400 Bad Request");
            return false;
        }
        if (!"13".equals(headers.get("sec-websocket-version"))) {
            // RFC 6455 section 4.4: tell the client which version we do speak.
            out.write(("HTTP/1.1 426 Upgrade Required\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return false;
        }

        // After reading the whole request, so the client gets a real HTTP response rather than
        // a reset. A reconnect loop without backoff can open hundreds of sockets a second. The
        // real fix is exponential backoff with jitter in the client; this stops one IP from
        // exhausting the server's file descriptors meanwhile.
        if (!admitted) {
            writeHttpError(out, "429 Too Many Requests");
            return false;
        }

        // TODO (Phase 7): check the Origin header and the session cookie here, before
        // accepting. Browsers don't apply CORS to WebSockets, so without an Origin check
        // any website a logged-in user visits could open a socket as them (CSWSH).

        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + computeAcceptKey(key) + "\r\n"
                + "\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return true;
    }

    /** Accept key = base64(SHA-1(clientKey + GUID)), per RFC 6455 section 4.2.2. */
    static String computeAcceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey.trim() + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to ship SHA-1, so this can't happen in practice.
            throw new IllegalStateException(e);
        }
    }

    /** Reads headers until the blank line. Names are lower-cased because HTTP header names are case-insensitive. */
    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (int count = 0; count <= MAX_HEADERS; count++) {
            String line = readLine(in);
            if (line == null) {
                return null; // connection closed mid-handshake
            }
            if (line.isEmpty()) {
                return headers; // blank line = end of headers
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return null; // malformed header
            }
            // Locale.ROOT, not the default locale: under e.g. a Turkish locale "CONNECTION"
            // lower-cases to "connectıon" (dotless i), so the lookup would miss and a valid
            // handshake would get a 400.
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return null; // too many headers
    }

    /**
     * Reads one CRLF-terminated line byte by byte. Returns null on EOF.
     * Byte-by-byte is fine here: the stream is buffered, and the handshake is tiny.
     */
    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return sb.toString();
            }
            if (b != '\r') {
                if (sb.length() >= MAX_LINE_BYTES) {
                    throw new IOException("Handshake line too long");
                }
                sb.append((char) b);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private void writeHttpError(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}
