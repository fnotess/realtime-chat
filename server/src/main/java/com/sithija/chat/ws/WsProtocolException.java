package com.sithija.chat.ws;

import java.io.IOException;

/**
 * The client broke RFC 6455. Carries the close status code (section 7.4.1) so that
 * Phase 3 can tell the client why before closing, instead of just dropping the socket.
 *
 * Extends IOException because to the read loop it's just another reason the connection
 * can't continue.
 */
public class WsProtocolException extends IOException {

    public static final int PROTOCOL_ERROR = 1002;
    public static final int INVALID_PAYLOAD = 1007;
    public static final int MESSAGE_TOO_BIG = 1009;

    private final int closeCode;

    public WsProtocolException(int closeCode, String message) {
        super(message);
        this.closeCode = closeCode;
    }

    public int closeCode() {
        return closeCode;
    }
}
