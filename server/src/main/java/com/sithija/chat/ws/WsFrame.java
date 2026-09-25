package com.sithija.chat.ws;

/**
 * One WebSocket frame, or a complete reassembled message (then fin is always true).
 *
 * payload is a byte[], so the record's generated equals() compares array references,
 * not contents; compare payloads with Arrays.equals.
 */
public record WsFrame(boolean fin, int opcode, byte[] payload) {

    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    // RFC 6455 section 5.5: opcodes 0x8-0xF are control frames, so the top bit of the
    // 4-bit opcode is enough to tell them apart from data frames.
    public static boolean isControl(int opcode) {
        return (opcode & 0x8) != 0;
    }
}
