package com.sithija.chat.messages;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All SQL for users, conversations and messages. Plain SQL through JdbcClient, so the queries
 * that carry the concurrency guarantees (ON CONFLICT, UPDATE ... RETURNING) are visible as written.
 */
@Repository
public class MessageRepository {

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record MessageRow(long id, long conversationId, long seq, String sender, UUID clientMsgId,
                             String body, Instant createdAt) { }

    public record ConversationSummary(long id, String otherUser, long lastSeq, String lastSender,
                                      String lastBody, Instant lastAt) { }

    // ON CONFLICT DO NOTHING instead of "SELECT, then INSERT if missing": two first connects
    // at once would both see no row, and the second INSERT would fail.
    void ensureUser(String username) {
        jdbc.sql("INSERT INTO users (username) VALUES (:username) ON CONFLICT (username) DO NOTHING")
                .param("username", username)
                .update();
    }

    Optional<Long> findUserId(String username) {
        return jdbc.sql("SELECT id FROM users WHERE username = :username")
                .param("username", username)
                .query(Long.class)
                .optional();
    }

    Optional<MessageRow> findByClientMsgId(long senderId, UUID clientMsgId) {
        return jdbc.sql(SELECT_MESSAGE + " WHERE m.sender_id = :senderId AND m.client_msg_id = :clientMsgId")
                .param("senderId", senderId)
                .param("clientMsgId", clientMsgId)
                .query(MessageRepository::mapMessage)
                .optional();
    }

    /**
     * Returns the conversation for this pair, creating it if needed. Both users may send their
     * first message at the same moment. INSERT ... ON CONFLICT waits for the other transaction's
     * insert to commit or roll back and then does nothing, so exactly one row exists. The
     * SELECT that follows runs as a new statement, so it sees whichever row won.
     */
    long findOrCreateConversation(long userX, long userY) {
        long a = Math.min(userX, userY);
        long b = Math.max(userX, userY);
        jdbc.sql("""
                INSERT INTO conversations (user_a_id, user_b_id) VALUES (:a, :b)
                ON CONFLICT (user_a_id, user_b_id) DO NOTHING
                """)
                .param("a", a).param("b", b)
                .update();
        return jdbc.sql("SELECT id FROM conversations WHERE user_a_id = :a AND user_b_id = :b")
                .param("a", a).param("b", b)
                .query(Long.class)
                .single();
    }

    /**
     * Claims the next seq. The UPDATE row-locks the conversation until the transaction ends,
     * so a second sender in the same conversation waits here and then reads the incremented
     * value. Seqs are therefore gapless and in commit order. Other conversations lock other
     * rows and don't wait at all.
     */
    long nextSeq(long conversationId) {
        return jdbc.sql("UPDATE conversations SET last_seq = last_seq + 1 WHERE id = :id RETURNING last_seq")
                .param("id", conversationId)
                .query(Long.class)
                .single();
    }

    MessageRow insertMessage(long conversationId, long seq, long senderId, UUID clientMsgId, String body) {
        return jdbc.sql("""
                WITH m AS (
                    INSERT INTO messages (conversation_id, seq, sender_id, client_msg_id, body)
                    VALUES (:conversationId, :seq, :senderId, :clientMsgId, :body)
                    RETURNING *
                )
                SELECT m.id, m.conversation_id, m.seq, u.username AS sender, m.client_msg_id, m.body, m.created_at
                FROM m JOIN users u ON u.id = m.sender_id
                """)
                .param("conversationId", conversationId)
                .param("seq", seq)
                .param("senderId", senderId)
                .param("clientMsgId", clientMsgId)
                .param("body", body)
                .query(MessageRepository::mapMessage)
                .single();
    }

    /** The other participant's username, or empty if the user isn't a member (or the conversation doesn't exist). */
    public Optional<String> otherMember(long conversationId, String username) {
        return jdbc.sql("""
                SELECT other.username
                FROM conversations c
                JOIN users me ON me.username = :username AND me.id IN (c.user_a_id, c.user_b_id)
                JOIN users other ON other.id = CASE WHEN c.user_a_id = me.id THEN c.user_b_id ELSE c.user_a_id END
                WHERE c.id = :id
                """)
                .param("id", conversationId)
                .param("username", username)
                .query(String.class)
                .optional();
    }

    /** Newest conversation first, each with its last message. */
    public List<ConversationSummary> conversationsOf(String username) {
        // The last message is found by (conversation_id, last_seq), one unique-index lookup
        // per conversation, instead of scanning messages for the max.
        return jdbc.sql("""
                SELECT c.id, other.username AS other_user, c.last_seq,
                       sender.username AS last_sender, m.body AS last_body, m.created_at AS last_at
                FROM users me
                JOIN conversations c ON me.id IN (c.user_a_id, c.user_b_id)
                JOIN users other ON other.id = CASE WHEN c.user_a_id = me.id THEN c.user_b_id ELSE c.user_a_id END
                LEFT JOIN messages m ON m.conversation_id = c.id AND m.seq = c.last_seq
                LEFT JOIN users sender ON sender.id = m.sender_id
                WHERE me.username = :username
                ORDER BY m.created_at DESC NULLS LAST, c.id DESC
                """)
                .param("username", username)
                .query((rs, n) -> new ConversationSummary(
                        rs.getLong("id"), rs.getString("other_user"), rs.getLong("last_seq"),
                        rs.getString("last_sender"), rs.getString("last_body"),
                        toInstant(rs.getObject("last_at", OffsetDateTime.class))))
                .list();
    }

    /**
     * One page of history: the `limit` messages just before `beforeSeq`, newest first.
     * Keyset paging: the index jumps straight to seq < beforeSeq, so page 1,000 costs the same
     * as page 1. OFFSET would read and discard every earlier row on every request.
     */
    public List<MessageRow> historyBefore(long conversationId, long beforeSeq, int limit) {
        return jdbc.sql(SELECT_MESSAGE + """
                 WHERE m.conversation_id = :conversationId AND m.seq < :beforeSeq
                 ORDER BY m.seq DESC
                 LIMIT :limit
                """)
                .param("conversationId", conversationId)
                .param("beforeSeq", beforeSeq)
                .param("limit", limit)
                .query(MessageRepository::mapMessage)
                .list();
    }

    private static final String SELECT_MESSAGE = """
            SELECT m.id, m.conversation_id, m.seq, u.username AS sender, m.client_msg_id, m.body, m.created_at
            FROM messages m JOIN users u ON u.id = m.sender_id
            """;

    private static MessageRow mapMessage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MessageRow(rs.getLong("id"), rs.getLong("conversation_id"), rs.getLong("seq"),
                rs.getString("sender"), rs.getObject("client_msg_id", UUID.class), rs.getString("body"),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
