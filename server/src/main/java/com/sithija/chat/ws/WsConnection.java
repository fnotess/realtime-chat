package com.sithija.chat.ws;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server side of one WebSocket connection: every outbound frame and the close handshake.
 *
 * Only this connection's own thread reads from the socket, but from Phase 5 any user's
 * thread may write to it during fan-out. That's why every write goes through sendFrame().
 */
public class WsConnection implements Closeable {

    enum State { OPEN, CLOSING, CLOSED }

    private final OutputStream out;
    private final Closeable transport;

    // ReentrantLock, not synchronized: on Java 21 a virtual thread that blocks (on the lock
    // or on a socket write) inside synchronized pins its carrier OS thread. One slow client
    // could then tie up a carrier per waiting writer. With ReentrantLock they just park.
    // Reentrant also lets sendClose()/onCloseReceived() hold the lock across check-then-send.
    private final ReentrantLock writeLock = new ReentrantLock();

    // Changed under writeLock, except in close(); volatile so that change is visible too.
    private volatile State state = State.OPEN;

    public WsConnection(Socket socket) throws IOException {
        this(socket.getOutputStream(), socket);
    }

    // Package-private so tests can capture the exact bytes in a ByteArrayOutputStream.
    WsConnection(OutputStream out, Closeable transport) {
        // Buffered so a small frame's header and payload leave in one write() (one TCP
        // segment) rather than a 2-byte packet followed by the payload.
        this.out = new BufferedOutputStream(out);
        this.transport = transport;
    }

    public void sendText(String text) throws IOException {
        sendFrame(WsFrame.OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendBinary(byte[] data) throws IOException {
        sendFrame(WsFrame.OP_BINARY, data);
    }

    // Section 5.5.3: a pong must carry the ping's payload unchanged.
    void sendPong(byte[] pingPayload) throws IOException {
        sendFrame(WsFrame.OP_PONG, pingPayload);
    }

    /** Starts a server-initiated close. Does nothing if a Close was already sent or received. */
    void sendClose(int code, String reason) throws IOException {
        writeLock.lock();
        try {
            // Checked under the lock so two threads can't both see OPEN and both send Close.
            if (state == State.OPEN) {
                sendFrame(WsFrame.OP_CLOSE, closePayload(code, reason));
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** Handles a Close frame from the client: echoes its code (or 1002 if the payload is invalid). */
    void onCloseReceived(byte[] payload) throws IOException {
        writeLock.lock();
        try {
            if (state == State.OPEN) {
                sendFrame(WsFrame.OP_CLOSE, closeReply(payload));
            }
            // If we were CLOSING, this is the client answering our Close: nothing left to send.
            state = State.CLOSED;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Closes the TCP connection. Deliberately does NOT take writeLock. A writer stuck on a
     * client that stopped reading holds the lock, and closing the socket is the only thing
     * that makes its write() fail and release the lock.
     */
    @Override
    public void close() throws IOException {
        state = State.CLOSED;
        transport.close();
    }

    /**
     * The only method that writes to the socket. Without the lock, two threads could write
     * header A, header B, payload A, payload B, and the client would parse garbage from then on.
     *
     * Known gap (Phase 4/5 backpressure): the write has no timeout. If a client stops reading,
     * its TCP buffers fill up, and write() blocks while holding writeLock. Every fan-out thread
     * sending to that client would then queue behind it. Fix with a bounded per-connection
     * send queue, or by calling close() after a write deadline.
     */
    private void sendFrame(int opcode, byte[] payload) throws IOException {
        writeLock.lock();
        try {
            // Section 5.5.1: nothing may follow our Close, and we never send data after the
            // client's Close. Throwing tells a fan-out caller that this recipient is gone.
            if (state != State.OPEN) {
                throw new IOException("Connection is " + state);
            }
            if (opcode == WsFrame.OP_CLOSE) {
                state = State.CLOSING;
            }
            writeHeader(opcode, payload.length);
            out.write(payload);
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    private void writeHeader(int opcode, int length) throws IOException {
        // FIN always 1: the whole message is already in memory, so there's no reason to fragment.
        out.write(0x80 | opcode);
        // MASK bit always 0: servers must not mask (section 5.1), and clients must fail the
        // connection if they receive a masked frame. Using the shortest length form is also a
        // MUST (section 5.2), not an optimisation.
        if (length <= 125) {
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(126);
            out.write(length >>> 8);
            out.write(length); // write(int) keeps only the low 8 bits
        } else {
            out.write(127);
            // Java arrays are int-indexed, so the top 4 of the 8 length bytes are always 0.
            out.write(new byte[4]);
            out.write(length >>> 24);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
        }
    }

    private static byte[] closeReply(byte[] received) {
        // An empty Close has no code to echo, and 1005 ("no status") must never go on the
        // wire, so the reply is empty too.
        if (received.length == 0) {
            return new byte[0];
        }
        if (isValidClosePayload(received)) {
            return closePayload(closeCode(received), "");
        }
        return closePayload(WsProtocolException.PROTOCOL_ERROR, "");
    }

    /** Empty, or a legal 2-byte code followed by a UTF-8 reason. A 1-byte payload is invalid. */
    static boolean isValidClosePayload(byte[] payload) {
        if (payload.length == 0) {
            return true;
        }
        return payload.length >= 2
                && isValidCloseCode(closeCode(payload))
                && WsFrameReader.isValidUtf8(payload, 2, payload.length - 2);
    }

    // Section 7.4: 1004 is reserved, and 1005/1006/1015 exist only for local reporting and must
    // never be sent. 1012-1014 were added to the IANA registry after the RFC. 3000-4999 are for
    // libraries and applications.
    private static boolean isValidCloseCode(int code) {
        return (code >= 1000 && code <= 1003)
                || (code >= 1007 && code <= 1014)
                || (code >= 3000 && code <= 4999);
    }

    private static byte[] closePayload(int code, String reason) {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        // Control payloads are at most 125 bytes, 2 of them the code. Drop a reason that's too
        // long rather than truncate it: cutting mid-character would make the reason invalid UTF-8.
        if (reasonBytes.length > 123) {
            reasonBytes = new byte[0];
        }
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >>> 8);
        payload[1] = (byte) code;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return payload;
    }

    // Returns 1005 ("no status received") for an empty payload. That's the code the RFC
    // reserves for reporting this case locally, as in our logs.
    static int closeCode(byte[] payload) {
        return payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
    }
}
