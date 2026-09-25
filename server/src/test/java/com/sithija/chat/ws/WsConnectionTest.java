package com.sithija.chat.ws;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WsConnectionTest {

    private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
    private final WsConnection conn = new WsConnection(wire, () -> { });

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

    // --- helpers ---

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
        new WsConnection(out, () -> { }).onCloseReceived(clientPayload);
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
