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

    // otherDeliveredSeq/otherReadSeq are the other member's watermarks (the ticks on my messages);
    // unreadCount is their messages above my read watermark.
    public record ConversationSummary(long id, String otherUser, long lastSeq, String lastSender,
                                      String lastBody, Instant lastAt, long otherDeliveredSeq,
                                      long otherReadSeq, long unreadCount) { }

    /** A member's watermarks after a receipt, plus who to tell about them. */
    record ReceiptRow(long conversationId, String otherUser, long deliveredSeq, long readSeq) { }

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
                       sender.username AS last_sender, m.body AS last_body, m.created_at AS last_at,
                       COALESCE(theirs.last_delivered_seq, 0) AS other_delivered_seq,
                       COALESCE(theirs.last_read_seq, 0) AS other_read_seq,
                       -- A range scan on the (conversation_id, seq) index from my watermark up.
                       -- Only the other member's messages count: my own are never unread to me.
                       (SELECT count(*) FROM messages u
                        WHERE u.conversation_id = c.id AND u.seq > COALESCE(mine.last_read_seq, 0)
                          AND u.sender_id <> me.id) AS unread_count
                FROM users me
                JOIN conversations c ON me.id IN (c.user_a_id, c.user_b_id)
                JOIN users other ON other.id = CASE WHEN c.user_a_id = me.id THEN c.user_b_id ELSE c.user_a_id END
                LEFT JOIN messages m ON m.conversation_id = c.id AND m.seq = c.last_seq
                LEFT JOIN users sender ON sender.id = m.sender_id
                LEFT JOIN conversation_receipts mine ON mine.conversation_id = c.id AND mine.user_id = me.id
                LEFT JOIN conversation_receipts theirs ON theirs.conversation_id = c.id AND theirs.user_id = other.id
                WHERE me.username = :username
                ORDER BY m.created_at DESC NULLS LAST, c.id DESC
                """)
                .param("username", username)
                .query((rs, n) -> new ConversationSummary(
                        rs.getLong("id"), rs.getString("other_user"), rs.getLong("last_seq"),
                        rs.getString("last_sender"), rs.getString("last_body"),
                        toInstant(rs.getObject("last_at", OffsetDateTime.class)),
                        rs.getLong("other_delivered_seq"), rs.getLong("other_read_seq"), rs.getLong("unread_count")))
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

    /**
     * Catch-up after a reconnect: up to `limit` messages after `afterSeq`, oldest first. The same
     * index as historyBefore, scanned forwards. Seqs are gapless, so "everything after the last seq
     * I have" is exactly what was missed.
     */
    public List<MessageRow> historyAfter(long conversationId, long afterSeq, int limit) {
        return jdbc.sql(SELECT_MESSAGE + """
                 WHERE m.conversation_id = :conversationId AND m.seq > :afterSeq
                 ORDER BY m.seq ASC
                 LIMIT :limit
                """)
                .param("conversationId", conversationId)
                .param("afterSeq", afterSeq)
                .param("limit", limit)
                .query(MessageRepository::mapMessage)
                .list();
    }

    /**
     * Moves a member's watermarks up to `seq` (read also moves delivered: you can't read what you
     * haven't received). Empty if the user isn't a member, the conversation doesn't exist, or seq
     * is outside 1..last_seq: the three look the same to the caller, so receipts can't be used to
     * probe conversation ids.
     *
     * GREATEST makes it monotonic: a late or duplicate receipt (two tabs, a retry, reordering
     * between connections) can never move a watermark back. ON CONFLICT DO UPDATE applies GREATEST
     * to the row as locked at update time, so concurrent receipts can't lose the higher value; a
     * read-then-write in Java could.
     */
    Optional<ReceiptRow> recordReceipt(String username, long conversationId, long seq, boolean read) {
        return jdbc.sql("""
                WITH target AS (
                    SELECT c.id AS conversation_id, me.id AS user_id, other.username AS other_user
                    FROM conversations c
                    JOIN users me ON me.username = :username AND me.id IN (c.user_a_id, c.user_b_id)
                    JOIN users other ON other.id = CASE WHEN c.user_a_id = me.id THEN c.user_b_id ELSE c.user_a_id END
                    WHERE c.id = :conversationId AND :seq BETWEEN 1 AND c.last_seq
                ), upserted AS (
                    INSERT INTO conversation_receipts AS r (conversation_id, user_id, last_delivered_seq, last_read_seq)
                    SELECT conversation_id, user_id, :seq, :readSeq FROM target
                    ON CONFLICT (conversation_id, user_id) DO UPDATE
                    SET last_delivered_seq = GREATEST(r.last_delivered_seq, EXCLUDED.last_delivered_seq),
                        last_read_seq = GREATEST(r.last_read_seq, EXCLUDED.last_read_seq)
                    RETURNING conversation_id, last_delivered_seq, last_read_seq
                )
                SELECT u.conversation_id, t.other_user, u.last_delivered_seq, u.last_read_seq
                FROM upserted u CROSS JOIN target t
                """)
                .param("username", username)
                .param("conversationId", conversationId)
                .param("seq", seq)
                .param("readSeq", read ? seq : 0)
                .query((rs, n) -> new ReceiptRow(rs.getLong("conversation_id"), rs.getString("other_user"),
                        rs.getLong("last_delivered_seq"), rs.getLong("last_read_seq")))
                .optional();
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
