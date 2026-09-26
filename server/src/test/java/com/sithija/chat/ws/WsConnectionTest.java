package com.sithija.chat.ws;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsConnectionTest {

    private final AtomicLong now = new AtomicLong();
    private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
    private final WsConnection conn = new WsConnection(wire, () -> { }, now::get, "test", 16);

    @Test
    void usesShortestLengthFormAtEachBoundary() throws IOException {
        // The boundary values on both sides of each switch between length forms.
        assertHeader(125, 0x82, 125);
        assertHeader(126, 0x82, 126, 0x00, 126);
        assertHeader(65_535, 0x82, 126, 0xFF, 0xFF);
        assertHeader(65_536, 0x82, 127, 0, 0, 0, 0, 0x00, 0x01, 0x00, 0x00);
    }

    @Test
    void closeFrameCarriesCodeThenReason() throws IOException {
        conn.sendClose(1000, "bye");

        // 0x88 = FIN + close, 5 = unmasked length, 03 E8 = 1000 big-endian, then "bye".
        assertArrayEquals(bytes(0x88, 5, 0x03, 0xE8, 'b', 'y', 'e'), wire.toByteArray());
        // After our Close, the RFC forbids sending data frames.
        assertThrows(IOException.class, () -> conn.sendText("too late"));
    }

    @Test
    void pongEchoesPingPayload() throws IOException {
        conn.sendPong(bytes('h', 'b'));

        assertArrayEquals(bytes(0x8A, 2, 'h', 'b'), wire.toByteArray());
    }

    @Test
    void repliesToCloseByEchoingCodeOr1002IfInvalid() throws IOException {
        assertCloseReply(bytes(0x03, 0xE8, 'o', 'k'), bytes(0x88, 2, 0x03, 0xE8)); // 1000 echoed, reason dropped
        assertCloseReply(bytes(), bytes(0x88, 0));                                // empty in, empty out
        assertCloseReply(bytes(0x03), bytes(0x88, 2, 0x03, 0xEA));                // 1 byte: invalid, so 1002
        assertCloseReply(bytes(0x03, 0xED), bytes(0x88, 2, 0x03, 0xEA));          // 1005 is never legal on the wire
        assertCloseReply(bytes(0x03, 0xE8, 0xC3), bytes(0x88, 2, 0x03, 0xEA));    // reason is not valid UTF-8
    }

    @Test
    void concurrentSendsNeverInterleave() throws Exception {
        int threads = 16;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        Set<String> expected = new HashSet<>();

        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                expected.add(message(t, i));
            }
            int thread = t;
            futures.add(pool.submit(() -> {
                start.await(); // release all threads at once to maximise contention
                for (int i = 0; i < perThread; i++) {
                    conn.sendText(message(thread, i));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // Parse the whole stream back. Any interleaving shifts the frame boundaries, so it shows
        // up as a bad header, a payload that isn't one of our messages, or leftover bytes.
        Set<String> received = new HashSet<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire.toByteArray()));
        while (in.available() > 0) {
            received.add(new String(readServerFrame(in, 0x81), StandardCharsets.UTF_8));
        }
        assertEquals(expected, received);
        assertEquals(threads * perThread, received.size());
    }

    @Test
    void stuckWriteIsDetectedAndClosingReleasesTheLock() throws Exception {
        WsProperties props = new WsProperties(0, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(2), 20, 16);
        BlockingOutputStream socketOut = new BlockingOutputStream();
        WsConnection stuck = new WsConnection(socketOut, socketOut::close, now::get, "stuck", 16);

        // A client that stopped reading: this write blocks until the "socket" is closed.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> writer = pool.submit(() -> {
            stuck.sendText("never delivered");
            return null;
        });
        assertTrue(socketOut.writeEntered.await(2, TimeUnit.SECONDS));

        now.addAndGet(Duration.ofSeconds(9).toNanos());
        assertEquals(WsConnection.Liveness.OK, stuck.checkLiveness(now.get(), props));
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(WsConnection.Liveness.STUCK_WRITE, stuck.checkLiveness(now.get(), props));

        // What the sweeper does on STUCK_WRITE. The blocked write must fail, and the lock must
        // be free: a later send fails at once with "closed" instead of hanging behind the lock.
        stuck.close();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            ExecutionException e = assertThrows(ExecutionException.class, writer::get);
            assertTrue(e.getCause() instanceof IOException);
            assertThrows(IOException.class, () -> stuck.sendText("after close"));
        });
        pool.shutdown();
    }

    @Test
    void fullQueueClosesSlowConsumerWithoutBlockingTheSender() throws Exception {
        BlockingOutputStream socketOut = new BlockingOutputStream();
        WsConnection slow = new WsConnection(socketOut, socketOut::close, now::get, "slow", 2);
        slow.startWriter();
        byte[] msg = "{}".getBytes(StandardCharsets.UTF_8);

        // The writer takes the first frame and blocks writing it: a client that stopped reading.
        assertTrue(slow.enqueueText(msg));
        assertTrue(socketOut.writeEntered.await(2, TimeUnit.SECONDS));
        assertTrue(slow.enqueueText(msg));
        assertTrue(slow.enqueueText(msg)); // queue now full (2 of 2)

        // The sender (fan-out) must get an immediate "no", not wait for the slow client.
        boolean accepted = assertTimeoutPreemptively(Duration.ofMillis(500), () -> slow.enqueueText(msg));
        assertFalse(accepted);

        // The close happens on a separate thread. The writer holds the lock, so no Close frame
        // is attempted and the "socket" is closed directly, which also fails the stuck write.
        assertTrue(socketOut.closed.await(2, TimeUnit.SECONDS));
        assertFalse(slow.enqueueText(msg), "dropped once closed");
    }

    // --- helpers ---

    /** Stands in for a socket whose peer stopped reading: write() blocks until close(). */
    private static final class BlockingOutputStream extends OutputStream {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            writeEntered.countDown();
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Socket closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    // Lengths vary so that both the 7-bit and 16-bit forms are exercised concurrently.
    private static String message(int thread, int i) {
        return "t" + thread + "-m" + i + "-" + "x".repeat((thread * 37 + i * 13) % 300);
    }

    private void assertHeader(int payloadLength, int... expectedHeader) throws IOException {
        wire.reset();
        conn.sendBinary(new byte[payloadLength]);
        byte[] out = wire.toByteArray();

        assertEquals(expectedHeader.length + payloadLength, out.length);
        for (int i = 0; i < expectedHeader.length; i++) {
            assertEquals((byte) expectedHeader[i], out[i], "header byte " + i + " for length " + payloadLength);
        }
    }

    private static void assertCloseReply(byte[] clientPayload, byte[] expectedReply) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WsConnection(out, () -> { }, System::nanoTime, "test", 16).onCloseReceived(clientPayload);
        assertArrayEquals(expectedReply, out.toByteArray());
    }

    /** Minimal parser for server frames: unmasked and unfragmented. */
    private static byte[] readServerFrame(DataInputStream in, int expectedB0) throws IOException {
        assertEquals(expectedB0, in.readUnsignedByte(), "first header byte");
        int b1 = in.readUnsignedByte();
        assertEquals(0, b1 & 0x80, "server frames must not be masked");
        int length = b1 & 0x7F;
        if (length == 126) {
            length = in.readUnsignedShort();
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
