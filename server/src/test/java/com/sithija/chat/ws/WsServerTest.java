package com.sithija.chat.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real sockets against a real WsServer, but with a fake clock. Time moves only when a test
 * advances it, and sweeps run only when a test calls sweep(), so timeouts of 30s or more
 * run in milliseconds.
 */
class WsServerTest {

    private static final Duration PING_AFTER_IDLE = Duration.ofSeconds(30);
    private static final Duration PONG_TIMEOUT = Duration.ofSeconds(10);

    // Arbitrary and negative on purpose: nanoTime's origin is arbitrary, so the code must only
    // ever subtract timestamps, never compare them to 0 or to wall-clock time.
    private final AtomicLong now = new AtomicLong(-5_000_000_000_000L);
    private final List<Socket> clients = new ArrayList<>();
    private WsServer server;

    @AfterEach
    void tearDown() throws Exception {
        for (Socket c : clients) {
            c.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void idleConnectionGetsPinged() throws Exception {
        start(20);
        Client c = connect();

        advance(PING_AFTER_IDLE.minusSeconds(1));
        server.sweep();
        advance(Duration.ofSeconds(1));
        server.sweep();

        // 0x89 = FIN + ping, 0x00 = unmasked, empty payload.
        assertArrayEquals(bytes(0x89, 0x00), c.readBytes(2));
    }

    @Test
    void connectionThatNeverAnswersIsClosedAfterPongTimeout() throws Exception {
        start(20);
        Client c = connect();

        advance(PING_AFTER_IDLE);
        server.sweep();
        assertArrayEquals(bytes(0x89, 0x00), c.readBytes(2));

        // The client stays silent.
        advance(PONG_TIMEOUT.minusSeconds(1));
        server.sweep();
        assertEquals(1, server.connectionCount(), "not yet: timeout hasn't passed");
        advance(Duration.ofSeconds(1));
        server.sweep();

        assertTrue(c.isClosedByServer(), "server should drop the dead connection");
        eventually(() -> server.connectionCount() == 0);
    }

    @Test
    void connectionThatAnswersStaysOpen() throws Exception {
        start(20);
        Client c = connect();

        advance(PING_AFTER_IDLE);
        server.sweep();
        assertArrayEquals(bytes(0x89, 0x00), c.readBytes(2));
        // Answer, then send our own ping. The server handles frames in order, so its pong
        // reply proves it has processed our pong, without needing a sleep.
        c.send(0x8A, new byte[0]);
        c.send(0x89, utf8("sync"));
        assertArrayEquals(concat(bytes(0x8A, 4), utf8("sync")), c.readBytes(6));

        advance(PONG_TIMEOUT);
        server.sweep();
        advance(PONG_TIMEOUT);
        server.sweep();

        // Still open and still serving: an echo comes back.
        c.send(0x81, utf8("alive"));
        assertArrayEquals(concat(bytes(0x81, 5), utf8("alive")), c.readBytes(7));
        assertEquals(1, server.connectionCount());
    }

    @Test
    void stopSendsGoingAwayToEveryConnection() throws Exception {
        start(20);
        Client a = connect();
        Client b = connect();

        server.stop();
        server = null; // already stopped

        // 0x88 = close, then 2-byte code 0x03E9 = 1001, then the reason.
        for (Client c : List.of(a, b)) {
            byte[] header = c.readBytes(2);
            assertEquals((byte) 0x88, header[0]);
            byte[] payload = c.readBytes(header[1]);
            assertEquals(1001, ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF));
            // Neither client replied, so after the grace period the server closed them anyway.
            assertTrue(c.isClosedByServer());
        }
    }

    @Test
    void perIpLimitReturns429AndFreesSlotOnClose() throws Exception {
        start(2);
        Client first = connect();
        connect();

        assertEquals("HTTP/1.1 429 Too Many Requests", handshake().statusLine);

        first.send(0x88, bytes(0x03, 0xE8)); // clean close with 1000
        eventually(() -> server.connectionCount() == 1);
        // The slot is released just after the connection leaves the set, so retry briefly.
        eventually(() -> {
            try {
                return handshake().statusLine.equals("HTTP/1.1 101 Switching Protocols");
            } catch (IOException e) {
                return false;
            }
        });
    }

    // --- helpers ---

    private void start(int maxPerIp) throws IOException {
        // Port 0 = any free port. The sweep interval is huge so the real sweeper thread never
        // fires during a test; the tests call sweep() themselves.
        WsProperties props = new WsProperties(0, PING_AFTER_IDLE, PONG_TIMEOUT, Duration.ofSeconds(10),
                Duration.ofHours(1), Duration.ofMillis(300), maxPerIp);
        server = new WsServer(props, now::get);
        server.start();
    }

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    /** Handshakes and waits until the server has registered the connection. */
    private Client connect() throws Exception {
        int before = server.connectionCount();
        Client c = handshake();
        assertEquals("HTTP/1.1 101 Switching Protocols", c.statusLine);
        // The server adds the connection just after writing the 101, on its own thread.
        eventually(() -> server.connectionCount() == before + 1);
        return c;
    }

    private Client handshake() throws IOException {
        Socket s = new Socket("localhost", server.localPort());
        clients.add(s);
        s.setSoTimeout(2000); // a missing frame fails the test instead of hanging it
        s.getOutputStream().write(("GET /chat HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return new Client(s);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("condition not met within 2s");
            }
            Thread.sleep(5);
        }
    }

    private static final class Client {
        final Socket socket;
        final DataInputStream in;
        final OutputStream out;
        final String statusLine;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
            this.statusLine = readHttpHead(in);
        }

        byte[] readBytes(int n) throws IOException {
            byte[] b = new byte[n];
            in.readFully(b);
            return b;
        }

        /** Sends a masked client frame (payload under 126 bytes). */
        void send(int b0, byte[] payload) throws IOException {
            byte[] mask = bytes(1, 2, 3, 4);
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(b0);
            f.write(0x80 | payload.length);
            f.writeBytes(mask);
            for (int i = 0; i < payload.length; i++) {
                f.write(payload[i] ^ mask[i % 4]);
            }
            out.write(f.toByteArray());
        }

        boolean isClosedByServer() throws IOException {
            try {
                return in.read() == -1;
            } catch (SocketException e) {
                return true; // reset also means closed
            }
        }

        // Reads byte by byte up to the blank line so no frame bytes are consumed.
        private static String readHttpHead(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int b = in.read();
                if (b == -1) {
                    break;
                }
                head.append((char) b);
            }
            return head.toString().split("\r\n", 2)[0];
        }
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
