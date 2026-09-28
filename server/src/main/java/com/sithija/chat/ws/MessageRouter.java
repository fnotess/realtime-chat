package com.sithija.chat.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sithija.chat.Usernames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Handles the JSON chat protocol carried in text frames: store first, then ack, then fan out.
 *
 * Invalid input gets an {"type":"error"} reply and the connection stays open. A typo in one
 * message is a client bug, not a protocol violation, and dropping the socket would take every
 * other conversation in that tab down with it.
 */
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    static final int MAX_TEXT_CHARS = 4000;
    // Error replies echo clientMsgId even when it isn't a valid UUID, so cap what we reflect:
    // otherwise a client could make us send back arbitrarily large strings.
    private static final int MAX_CLIENT_MSG_ID_CHARS = 64;
    // Canonical form only. UUID.fromString alone is lenient and accepts things like "1-1-1-1-1".
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ConnectionRegistry registry;
    private final MessageStore store;
    private final JsonMapper json = JsonMapper.builder().build();

    public MessageRouter(ConnectionRegistry registry, MessageStore store) {
        this.registry = registry;
        this.store = store;
    }

    record Ack(String type, String clientMsgId, long id, long conversationId, long seq, long ts) { }

    record Message(String type, long id, long conversationId, long seq, String from, String to,
                   String text, String clientMsgId, long ts) { }

    record Ready(String type, String user) { }

    record ReceiptPush(String type, long conversationId, String user, long deliveredSeq, long readSeq) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorReply(String type, String code, String message, String clientMsgId) { }

    void onText(WsConnection sender, String senderId, String frameText) {
        JsonNode msg;
        try {
            msg = json.readTree(frameText);
        } catch (JacksonException e) {
            reply(sender, error("invalid_json", "Frame is not valid JSON", null));
            return;
        }
        if (msg == null || !msg.isObject()) {
            reply(sender, error("invalid_json", "Expected a JSON object", null));
            return;
        }
        String clientMsgId = string(msg, "clientMsgId");
        String type = string(msg, "type");
        switch (type == null ? "" : type) {
            case "send" -> onSend(sender, senderId, msg, clientMsgId);
            case "delivered" -> onReceipt(sender, senderId, msg, false);
            case "read" -> onReceipt(sender, senderId, msg, true);
            default -> reply(sender, error("unknown_type", "Unknown type: " + type, clientMsgId));
        }
    }

    /**
     * Sent once the connection is registered for fan-out, which happens after the 101. A client
     * that starts its catch-up fetch on "ready" (not on the browser's onopen) is guaranteed that
     * every message committed after the fetch's query arrives live on this socket, so fetch plus
     * live leaves no gap. Started on onopen instead, a message committed between the 101 and the
     * registration would be in neither.
     */
    void sendReady(WsConnection conn, String user) {
        reply(conn, new Ready("ready", user));
    }

    private void onSend(WsConnection sender, String senderId, JsonNode msg, String clientMsgId) {
        String to = string(msg, "to");
        String text = string(msg, "text");
        String problem = validateSend(senderId, to, clientMsgId, text);
        if (problem != null) {
            reply(sender, error(problem, describe(problem), clientMsgId));
            return;
        }

        // Runs on this connection's reader thread, so a tab's messages are stored one at a time,
        // in the order it sent them. Blocking on the database is fine on a virtual thread.
        MessageStore.StoredMessage stored;
        try {
            stored = store.save(senderId, to, UUID.fromString(clientMsgId), text);
        } catch (MessageStore.UnknownRecipientException e) {
            reply(sender, error("unknown_recipient", "No user named '" + to + "'", clientMsgId));
            return;
        } catch (RuntimeException e) {
            // Nothing was stored, so no ack. The client can retry with the same clientMsgId;
            // the unique constraint makes that retry safe even if the first attempt actually
            // committed and only the reply was lost.
            log.warn("Failed to store message from {}", senderId, e);
            reply(sender, error("server_error", "Message not stored; retry with the same clientMsgId", clientMsgId));
            return;
        }

        // Committed, so it's now safe to tell anyone. Fanning out before the commit could deliver
        // a message that then rolls back, and the recipient would see a message that doesn't exist.
        long ts = stored.createdAt().toEpochMilli();
        reply(sender, new Ack("ack", clientMsgId, stored.id(), stored.conversationId(), stored.seq(), ts));

        // A duplicate was already fanned out the first time. Tabs that missed it catch up from
        // history, and clients dedupe by id either way.
        if (stored.duplicate()) {
            return;
        }
        // Encoded once and shared by every recipient connection: fan-out to N tabs is N
        // queue offers, not N serializations.
        byte[] message = encode(new Message("message", stored.id(), stored.conversationId(), stored.seq(),
                senderId, to, text, clientMsgId, ts));
        for (WsConnection conn : registry.connectionsOf(to)) {
            conn.enqueueText(message);
        }
        // The sender's other tabs get the message too, so their view of the conversation stays
        // complete. Not the originating tab: it has the text already and gets an ack instead.
        for (WsConnection conn : registry.connectionsOf(senderId)) {
            if (conn != sender) {
                conn.enqueueText(message);
            }
        }
    }

    /**
     * {"type":"delivered"|"read","conversationId","seq"}: "I have received (read) everything up to
     * seq". Clients coalesce these (one per conversation per burst, not one per message), and the
     * store keeps only the highest, so resends and out-of-order receipts are harmless.
     */
    private void onReceipt(WsConnection conn, String user, JsonNode msg, boolean read) {
        Long conversationId = positiveLong(msg, "conversationId");
        Long seq = positiveLong(msg, "seq");
        if (conversationId == null || seq == null) {
            reply(conn, error("missing_field", "'conversationId' and 'seq' must be positive integers", null));
            return;
        }
        MessageStore.Receipt receipt;
        try {
            receipt = store.recordReceipt(user, conversationId, seq, read).orElse(null);
        } catch (RuntimeException e) {
            // Safe to drop: the client sends a higher watermark with its next receipt anyway.
            log.warn("Failed to store receipt from {}", user, e);
            reply(conn, error("server_error", "Receipt not stored", null));
            return;
        }
        if (receipt == null) {
            // Not a member, no such conversation, or a seq that doesn't exist yet. One code for all
            // three, like the REST 404, so receipts can't be used to probe conversation ids.
            reply(conn, error("invalid_receipt", "No such message in a conversation of yours", null));
            return;
        }
        // Current watermarks, not the delta, so a push that arrives late or twice can't move the
        // other side's ticks backwards either: the client also keeps the max.
        byte[] push = encode(new ReceiptPush("receipt", receipt.conversationId(), user,
                receipt.deliveredSeq(), receipt.readSeq()));
        for (WsConnection c : registry.connectionsOf(receipt.otherUser())) {
            c.enqueueText(push);
        }
        // The user's other tabs too, so reading on one device clears the unread badge on the rest.
        for (WsConnection c : registry.connectionsOf(user)) {
            if (c != conn) {
                c.enqueueText(push);
            }
        }
    }

    void onBinary(WsConnection sender) {
        reply(sender, error("unsupported", "Binary frames are not part of the protocol", null));
    }

    private static String validateSend(String senderId, String to, String clientMsgId, String text) {
        if (to == null || clientMsgId == null || text == null) {
            return "missing_field";
        }
        if (!Usernames.isValid(to)) {
            return "invalid_recipient";
        }
        if (to.equals(senderId)) {
            return "self_send";
        }
        if (!UUID_FORMAT.matcher(clientMsgId).matches()) {
            return "invalid_client_msg_id";
        }
        if (text.isBlank()) {
            return "empty_text";
        }
        if (text.length() > MAX_TEXT_CHARS) {
            return "text_too_long";
        }
        return null;
    }

    private static String describe(String code) {
        return switch (code) {
            case "missing_field" -> "'to', 'clientMsgId' and 'text' must all be strings";
            case "invalid_recipient" -> "'to' is not a valid user id";
            case "self_send" -> "Cannot send a message to yourself";
            case "invalid_client_msg_id" -> "'clientMsgId' must be a UUID";
            case "empty_text" -> "'text' is empty";
            case "text_too_long" -> "'text' exceeds " + MAX_TEXT_CHARS + " characters";
            default -> code;
        };
    }

    // null if absent or not a string: {"to": 5} counts as missing, not as user "5".
    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    // null unless an integer >= 1 that fits in a long ("5", 5.5 and 2^70 are all rejected).
    private static Long positiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            return null;
        }
        long v = value.longValue();
        return v >= 1 ? v : null;
    }

    private static ErrorReply error(String code, String message, String clientMsgId) {
        // Only echo an id we'd also accept, for the same reflection reason as the limit above.
        String safeId = clientMsgId != null && clientMsgId.length() <= MAX_CLIENT_MSG_ID_CHARS ? clientMsgId : null;
        return new ErrorReply("error", code, message, safeId);
    }

    private void reply(WsConnection conn, Object payload) {
        // Through the queue like everything else, so replies stay in order with messages.
        conn.enqueueText(encode(payload));
    }

    private byte[] encode(Object payload) {
        return json.writeValueAsBytes(payload);
    }
}
