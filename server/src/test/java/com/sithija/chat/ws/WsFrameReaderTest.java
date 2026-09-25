package com.sithija.chat.ws;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsFrameReaderTest {

    private static final int MAX = 64 * 1024;

    @Test
    void readsRfcMaskedHelloExample() throws IOException {
        // Byte-for-byte from RFC 6455 section 5.7, so we don't depend only on our own encoder helper.
        WsFrameReader reader = reader(bytes(
                0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58));

        WsFrame message = reader.readMessage();

        assertTrue(message.fin());
        assertEquals(WsFrame.OP_TEXT, message.opcode());
        assertEquals("Hello", new String(message.payload(), StandardCharsets.UTF_8));
        assertNull(reader.readMessage(), "clean EOF between frames");
    }

    @Test
    void reads16BitLength() throws IOException {
        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        byte[] frame = masked(0x82, payload);
        // 126 marker + 0x0100 big-endian: checks that the helper really produced the 16-bit form.
        assertEquals((byte) 0xFE, frame[1]);
        assertEquals(0x01, frame[2]);
        assertEquals(0x00, frame[3]);

        WsFrame message = reader(frame).readMessage();

        assertEquals(WsFrame.OP_BINARY, message.opcode());
        assertArrayEquals(payload, message.payload());
    }

    @Test
    void reassemblesFragmentsAndPassesThroughInterleavedPing() throws IOException {
        WsFrameReader reader = reader(concat(
                masked(0x01, utf8("Hel")),     // FIN=0, text: first fragment
                masked(0x89, utf8("hb")),      // ping in the middle, allowed by section 5.4
                masked(0x80, utf8("lo"))));    // FIN=1, continuation: last fragment

        WsFrame ping = reader.readMessage();
        assertEquals(WsFrame.OP_PING, ping.opcode());

        WsFrame message = reader.readMessage();
        assertTrue(message.fin());
        assertEquals(WsFrame.OP_TEXT, message.opcode());
        assertEquals("Hello", new String(message.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnmaskedFrame() {
        // RFC 6455 section 5.7 unmasked "Hello": valid from a server, forbidden from a client.
        WsFrameReader reader = reader(bytes(0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f));

        assertCloseCode(WsProtocolException.PROTOCOL_ERROR, reader);
    }

    @Test
    void rejectsOversizedFrameBeforeReadingPayload() {
        // Claims 4GB via the 64-bit form, then the stream ends. Getting MESSAGE_TOO_BIG rather
        // than EOFException shows the limit is checked before any allocation or payload read.
        WsFrameReader reader = reader(bytes(0x82, 0xFF, 0, 0, 0, 1, 0, 0, 0, 0));

        assertCloseCode(WsProtocolException.MESSAGE_TOO_BIG, reader);
    }

    @Test
    void rejectsFragmentsThatTogetherExceedLimit() {
        // Each fragment fits on its own; the limit has to be on the whole message.
        byte[] half = new byte[40_000];
        WsFrameReader reader = reader(concat(masked(0x02, half), masked(0x80, half)));

        assertCloseCode(WsProtocolException.MESSAGE_TOO_BIG, reader);
    }

    @Test
    void rejectsInvalidUtf8Text() {
        // 0xC3 starts a 2-byte sequence that never finishes.
        WsFrameReader reader = reader(masked(0x81, bytes(0xC3)));

        assertCloseCode(WsProtocolException.INVALID_PAYLOAD, reader);
    }

    // --- helpers ---

    private static void assertCloseCode(int expected, WsFrameReader reader) {
        WsProtocolException e = assertThrows(WsProtocolException.class, reader::readMessage);
        assertEquals(expected, e.closeCode());
    }

    private static WsFrameReader reader(byte[] wire) {
        return new WsFrameReader(new DataInputStream(new ByteArrayInputStream(wire)), MAX);
    }

    /** Encodes a client frame: first byte as given, MASK bit set, 7- or 16-bit length. */
    private static byte[] masked(int b0, byte[] payload) {
        byte[] mask = bytes(0x11, 0x22, 0x33, 0x44);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(b0);
        if (payload.length < 126) {
            out.write(0x80 | payload.length);
        } else {
            out.write(0x80 | 126);
            out.write(payload.length >> 8);
            out.write(payload.length & 0xFF);
        }
        out.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i % 4]);
        }
        return out.toByteArray();
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

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
