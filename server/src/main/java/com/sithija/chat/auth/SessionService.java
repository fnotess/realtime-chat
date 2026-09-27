package com.sithija.chat.auth;

import com.sithija.chat.auth.AuthRepository.SessionRow;
import com.sithija.chat.ws.SessionAuthenticator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Opaque server-side session tokens, used by both REST (AuthFilter) and the WebSocket handshake.
 *
 * Why not JWT: a JWT is valid until it expires, so logout can't really end it without a
 * server-side denylist, which is a session table again. And a WebSocket is authenticated once, at
 * the handshake, then lives for hours; with sessions in our own table, logout can find and close
 * exactly that session's sockets. A signed token would also add key management for no benefit on
 * a single backend that already hits the database on every request.
 */
@Service
public class SessionService implements SessionAuthenticator {

    private static final int TOKEN_BYTES = 32;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final AuthRepository repo;
    private final AuthProperties props;
    // Thread-safe, and seeded from the OS. One instance: creating one per call is slow and gains nothing.
    private final SecureRandom random = new SecureRandom();

    SessionService(AuthRepository repo, AuthProperties props) {
        this.repo = repo;
        this.props = props;
    }

    record NewSession(String rawToken, SessionRow row) { }

    /**
     * Always a fresh random token, never one the client supplied. That is what prevents session
     * fixation: an attacker who plants a cookie before the victim logs in gains nothing, because
     * login replaces it with a token the attacker has never seen.
     */
    NewSession create(long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        // base64url without padding: cookie-safe characters only, no quoting or escaping needed.
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new NewSession(token, repo.insertSession(userId, hash(token), props.sessionTtl()));
    }

    /** Resolves a cookie value to its session. Used for every authenticated REST request. */
    Optional<SessionRow> find(String rawToken) {
        if (rawToken == null || rawToken.isEmpty() || rawToken.length() > 100) {
            return Optional.empty(); // not one of ours; skip the query
        }
        Optional<SessionRow> row = repo.findActiveSession(hash(rawToken));
        // Throttled so a burst of requests doesn't become a burst of UPDATEs on one row.
        row.filter(s -> Duration.between(s.lastSeenAt(), Instant.now()).compareTo(TOUCH_INTERVAL) > 0)
                .ifPresent(s -> repo.touch(s.id()));
        return row;
    }

    /** Deletes the session if it exists. Returns its id so the caller can close its sockets. */
    Optional<Long> delete(String rawToken) {
        return rawToken == null ? Optional.empty() : repo.deleteSession(hash(rawToken));
    }

    @Override
    public Optional<AuthenticatedSession> authenticate(String rawToken) {
        return find(rawToken).map(s -> new AuthenticatedSession(s.id(), s.username(), s.expiresAt()));
    }

    @Override
    public boolean isActive(long sessionId) {
        return repo.isActive(sessionId);
    }

    static byte[] hash(String rawToken) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM ships SHA-256
        }
    }
}
