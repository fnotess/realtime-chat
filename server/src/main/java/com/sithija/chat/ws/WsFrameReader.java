package com.sithija.chat.ws;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static com.sithija.chat.ws.WsProtocolException.INVALID_PAYLOAD;
import static com.sithija.chat.ws.WsProtocolException.MESSAGE_TOO_BIG;
import static com.sithija.chat.ws.WsProtocolException.PROTOCOL_ERROR;

/**
 * Reads client frames (RFC 6455 section 5.2) and reassembles fragmented messages.
 *
 * One instance per connection, used only by that connection's thread. It keeps the
 * partially reassembled message between calls, so it is deliberately not thread-safe.
 */
public class WsFrameReader {

    private final DataInputStream in;
    private final int maxMessageBytes;

    // Reassembly state. It survives across readMessage() calls because a control frame can
    // arrive between fragments (section 5.4), and we hand that frame to the caller right away.
    // Set to null (not reset()) once a message completes: reset() keeps the grown buffer, so
    // every idle connection would pin up to maxMessageBytes of heap forever.
    private ByteArrayOutputStream fragments;
    private int fragmentOpcode;

    // Runs after every complete frame, including each fragment and control frame, which
    // readMessage() otherwise hides. The heartbeat uses it: a client busy sending a long
    // fragmented message is alive even though no complete message has arrived yet.
    private final Runnable onFrame;

    public WsFrameReader(DataInputStream in, int maxMessageBytes) {
        this(in, maxMessageBytes, () -> { });
    }

    public WsFrameReader(DataInputStream in, int maxMessageBytes, Runnable onFrame) {
        this.in = in;
        this.maxMessageBytes = maxMessageBytes;
        this.onFrame = onFrame;
    }

    /**
     * Returns the next complete text/binary message (fragments already joined) or control
     * frame. Returns null if the client closed TCP cleanly on a frame boundary.
     */
    public WsFrame readMessage() throws IOException {
        while (true) {
            int budget = maxMessageBytes - (fragments == null ? 0 : fragments.size());
            WsFrame frame = readFrame(budget);
            if (frame == null) {
                return null;
            }
            onFrame.run();
            int opcode = frame.opcode();

            if (WsFrame.isControl(opcode)) {
                return frame;
            }
            if (opcode == WsFrame.OP_CONTINUATION) {
                if (fragments == null) {
                    throw new WsProtocolException(PROTOCOL_ERROR, "Continuation frame with no message to continue");
                }
            } else {
                if (fragments != null) {
                    throw new WsProtocolException(PROTOCOL_ERROR, "New message started before the previous one finished");
                }
                if (frame.fin()) {
                    // Unfragmented, which is nearly every message: return it without copying.
                    return validated(frame);
                }
                fragments = new ByteArrayOutputStream();
                fragmentOpcode = opcode;
            }

            fragments.writeBytes(frame.payload());
            if (frame.fin()) {
                WsFrame message = new WsFrame(true, fragmentOpcode, fragments.toByteArray());
                fragments = null;
                return validated(message);
            }
        }
    }

    /** Reads one raw frame. maxPayload applies to data frames only; control frames have their own limit. */
    private WsFrame readFrame(int maxPayload) throws IOException {
        // read(), not readUnsignedByte(), for the first byte only: EOF here is a clean
        // disconnect between frames. EOF anywhere later means the frame was cut off,
        // and readFully/readUnsigned* throw EOFException for that.
        int b0 = in.read();
        if (b0 == -1) {
            return null;
        }
        int b1 = in.readUnsignedByte();

        boolean fin = (b0 & 0x80) != 0;
        // RSV1-3 only mean something once an extension (e.g. permessage-deflate) is
        // negotiated in the handshake. We negotiate none, so a set bit means the client
        // is speaking something we'd misread, such as a compressed payload.
        if ((b0 & 0x70) != 0) {
            throw new WsProtocolException(PROTOCOL_ERROR, "RSV bits set but no extension negotiated");
        }
        int opcode = b0 & 0x0F;
        switch (opcode) {
            case WsFrame.OP_CONTINUATION, WsFrame.OP_TEXT, WsFrame.OP_BINARY,
                 WsFrame.OP_CLOSE, WsFrame.OP_PING, WsFrame.OP_PONG -> { }
            default -> throw new WsProtocolException(PROTOCOL_ERROR, "Unknown opcode " + opcode);
        }

        // Masking protects proxies, not the server: it keeps a malicious page from choosing
        // the exact bytes on the wire and poisoning an intermediary cache that misparses
        // WebSocket traffic as HTTP. The RFC says the server MUST close on unmasked frames.
        if ((b1 & 0x80) == 0) {
            throw new WsProtocolException(PROTOCOL_ERROR, "Client frame is not masked");
        }

        long length = b1 & 0x7F;
        if (length == 126) {
            length = in.readUnsignedShort();
        } else if (length == 127) {
            length = in.readLong();
            // Java has no unsigned long, so a set top bit reads as negative. The RFC
            // forbids that bit anyway, so rejecting negatives covers both cases.
            if (length < 0) {
                throw new WsProtocolException(PROTOCOL_ERROR, "64-bit length has its most significant bit set");
            }
        }

        // All limits are checked BEFORE allocating: the length is attacker-controlled, and
        // new byte[length] on a claimed 4GB frame would take the JVM down first.
        if (WsFrame.isControl(opcode)) {
            // Control frames can arrive in the middle of a fragmented message, so they must
            // be small and whole to be processed without disturbing reassembly (section 5.5).
            if (!fin) {
                throw new WsProtocolException(PROTOCOL_ERROR, "Fragmented control frame");
            }
            if (length > 125) {
                throw new WsProtocolException(PROTOCOL_ERROR, "Control frame payload over 125 bytes");
            }
        } else if (length > maxPayload) {
            throw new WsProtocolException(MESSAGE_TOO_BIG, "Message exceeds " + maxMessageBytes + " bytes");
        }

        // readFully, not read(buf): TCP delivers bytes in arbitrary chunks, and read() may
        // return after filling only part of the buffer.
        byte[] mask = new byte[4];
        in.readFully(mask);
        byte[] payload = new byte[(int) length];
        in.readFully(payload);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        return new WsFrame(fin, opcode, payload);
    }

    // Validated on the complete message, not per fragment: a multi-byte character may
    // legally be split across two fragments, and neither half is valid UTF-8 on its own.
    private static WsFrame validated(WsFrame message) throws WsProtocolException {
        byte[] payload = message.payload();
        if (message.opcode() == WsFrame.OP_TEXT && !isValidUtf8(payload, 0, payload.length)) {
            throw new WsProtocolException(INVALID_PAYLOAD, "Text message is not valid UTF-8");
        }
        return message;
    }

    // Shared with WsConnection, which must also validate the reason in a client's Close frame.
    static boolean isValidUtf8(byte[] bytes, int offset, int length) {
        try {
            // new String(bytes, UTF_8) would silently replace bad bytes with U+FFFD;
            // a decoder set to REPORT throws instead, which is what the RFC requires.
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
