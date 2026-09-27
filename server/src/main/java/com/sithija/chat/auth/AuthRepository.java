package com.sithija.chat.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * SQL for accounts and sessions. Every time comparison uses the database's now(), so expiry is
 * judged by one clock however many app instances there are.
 */
@Repository
class AuthRepository {

    private final JdbcClient jdbc;

    AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Account(long id, String username, String passwordHash) { }

    record SessionRow(long id, long userId, String username, Instant expiresAt, Instant lastSeenAt) { }

    /** Throws DuplicateKeyException if the name is taken: the UNIQUE constraint decides races. */
    long insertUser(String username, String passwordHash) {
        return jdbc.sql("INSERT INTO users (username, password_hash) VALUES (:u, :h) RETURNING id")
                .param("u", username).param("h", passwordHash)
                .query(Long.class).single();
    }

    Optional<Account> findAccount(String username) {
        return jdbc.sql("SELECT id, username, password_hash FROM users WHERE username = :u")
                .param("u", username)
                .query((rs, n) -> new Account(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash")))
                .optional();
    }

    SessionRow insertSession(long userId, byte[] tokenHash, Duration ttl) {
        return jdbc.sql("""
                WITH s AS (
                    INSERT INTO sessions (user_id, token_hash, expires_at)
                    VALUES (:userId, :hash, now() + :ttlSeconds * interval '1 second')
                    RETURNING *
                )
                SELECT s.id, s.user_id, u.username, s.expires_at, s.last_seen_at
                FROM s JOIN users u ON u.id = s.user_id
                """)
                .param("userId", userId).param("hash", tokenHash).param("ttlSeconds", ttl.toSeconds())
                .query(AuthRepository::mapSession).single();
    }

    /** Only unexpired sessions: an expired row is treated exactly like a missing one. */
    Optional<SessionRow> findActiveSession(byte[] tokenHash) {
        return jdbc.sql(SELECT_SESSION + " WHERE s.token_hash = :hash AND s.expires_at > now()")
                .param("hash", tokenHash)
                .query(AuthRepository::mapSession).optional();
    }

    boolean isActive(long sessionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sessions WHERE id = :id AND expires_at > now())")
                .param("id", sessionId)
                .query(Boolean.class).single();
    }

    void touch(long sessionId) {
        jdbc.sql("UPDATE sessions SET last_seen_at = now() WHERE id = :id").param("id", sessionId).update();
    }

    /** Returns the deleted session's id, so the caller can close that session's sockets. */
    Optional<Long> deleteSession(byte[] tokenHash) {
        return jdbc.sql("DELETE FROM sessions WHERE token_hash = :hash RETURNING id")
                .param("hash", tokenHash)
                .query(Long.class).optional();
    }

    // Expired rows are already ignored by every lookup; this just stops them piling up. Done per
    // user at login, which needs no scheduler and touches only that user's rows.
    void deleteExpiredSessions(long userId) {
        jdbc.sql("DELETE FROM sessions WHERE user_id = :userId AND expires_at <= now()")
                .param("userId", userId).update();
    }

    private static final String SELECT_SESSION = """
            SELECT s.id, s.user_id, u.username, s.expires_at, s.last_seen_at
            FROM sessions s JOIN users u ON u.id = s.user_id
            """;

    private static SessionRow mapSession(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SessionRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("username"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_seen_at", OffsetDateTime.class).toInstant());
    }
}
