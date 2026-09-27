package com.sithija.chat.ws;

import java.time.Instant;
import java.util.Optional;

/**
 * What the WebSocket handshake needs from the session store. Defined here and implemented by
 * auth.SessionService, like MessageStore, so the ws package stays free of JDBC and its tests can
 * use a fake.
 */
public interface SessionAuthenticator {

    /** Set by the REST login, read by both REST and the WebSocket handshake. */
    String COOKIE_NAME = "chat_session";

    /** The session for this raw cookie token, if it exists and hasn't expired. */
    Optional<AuthenticatedSession> authenticate(String rawToken);

    /** Whether the session still exists and hasn't expired (see the logout race in WsServer). */
    boolean isActive(long sessionId);

    record AuthenticatedSession(long sessionId, String username, Instant expiresAt) { }
}
