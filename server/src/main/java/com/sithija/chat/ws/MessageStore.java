package com.sithija.chat.ws;

import java.time.Instant;
import java.util.UUID;

/**
 * What the WebSocket layer needs from storage. Defined here, implemented by the database code,
 * so the ws package doesn't depend on JDBC and its tests can use an in-memory fake.
 */
public interface MessageStore {

    /** DEV ONLY: creates the user on first connect, because there's no sign-up until Phase 7. */
    void ensureUser(String username);

    /**
     * Stores the message and returns only after it has COMMITTED, so the caller can ack and
     * fan out safely. A retry with the same (sender, clientMsgId) returns the original message,
     * with duplicate = true, and uses no new seq.
     *
     * @throws UnknownRecipientException if the recipient has never connected
     */
    StoredMessage save(String sender, String recipient, UUID clientMsgId, String text);

    record StoredMessage(long id, long conversationId, long seq, String from, String to,
                         UUID clientMsgId, String text, Instant createdAt, boolean duplicate) { }

    class UnknownRecipientException extends RuntimeException {
        public UnknownRecipientException(String username) {
            super("Unknown recipient: " + username);
        }
    }
}
