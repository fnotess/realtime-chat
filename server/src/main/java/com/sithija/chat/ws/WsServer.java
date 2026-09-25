package com.sithija.chat.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private final int port;
    // Shared builder so threads get sequential names (ws-conn-1, ws-conn-2...) in thread dumps.
    private final Thread.Builder connectionThreads = Thread.ofVirtual().name("ws-conn-", 1);

    private ServerSocket serverSocket;
    // volatile: written by Spring's shutdown thread, read by the accept-loop thread.
    private volatile boolean running;

    public WsServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        Thread.ofVirtual().name("ws-acceptor").start(this::acceptLoop);
        log.info("WebSocket server listening on port {}", port);
    }

    public void stop() throws IOException {
        running = false;
        // Closing the ServerSocket is the only way to unblock accept(); it throws a
        // SocketException, which the accept loop treats as a normal shutdown.
        if (serverSocket != null) {
            serverSocket.close();
        }
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

            if (!performHandshake(in, out)) {
                return;
            }
            log.info("Handshake OK from {}", socket.getRemoteSocketAddress());

            // After the handshake the connection is long-lived and idle most of the time,
            // so the read timeout must go. Dead connections will be detected by ping/pong
            // (Phase 4) rather than by read timeouts.
            socket.setSoTimeout(0);

            // Wraps the SAME buffered stream the handshake used (see above), so frame bytes
            // that arrived together with the handshake aren't skipped.
            WsFrameReader reader = new WsFrameReader(new DataInputStream(in), MAX_MESSAGE_BYTES);
            WsFrame message;
            while ((message = reader.readMessage()) != null) {
                switch (message.opcode()) {
                    case WsFrame.OP_TEXT -> log.info("Text from {}: {}", socket.getRemoteSocketAddress(),
                            new String(message.payload(), StandardCharsets.UTF_8));
                    case WsFrame.OP_BINARY -> log.info("Binary from {}: {} bytes",
                            socket.getRemoteSocketAddress(), message.payload().length);
                    case WsFrame.OP_CLOSE -> {
                        // Phase 3 must echo a Close frame before closing; for now we just drop the socket.
                        log.info("Close frame from {} (code {})", socket.getRemoteSocketAddress(),
                                closeCode(message.payload()));
                        return;
                    }
                    // Phase 3 answers pings with pongs; Phase 4 uses pongs for liveness.
                    default -> log.debug("Control frame opcode {} from {}", message.opcode(),
                            socket.getRemoteSocketAddress());
                }
            }
            log.info("Connection closed by {}", socket.getRemoteSocketAddress());
        } catch (WsProtocolException e) {
            // Phase 3 sends a Close frame with e.closeCode() first; for now just drop the socket.
            log.info("Protocol violation from {}: {} (close code {})",
                    socket.getRemoteSocketAddress(), e.getMessage(), e.closeCode());
        } catch (IOException e) {
            log.debug("Connection error: {}", e.getMessage());
        }
    }

    // A close payload is optional; when present it starts with a 2-byte big-endian status code.
    // 1005 is the RFC's "no status received" code, reserved for exactly this local reporting.
    private static int closeCode(byte[] payload) {
        return payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
    }

    /** Returns true if the upgrade succeeded; otherwise writes an HTTP error and returns false. */
    private boolean performHandshake(InputStream in, OutputStream out) throws IOException {
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
