package com.sithija.chat.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Handles the JSON chat protocol carried in text frames, and fans messages out through the
 * registry.
 *
 * Invalid input gets an {"type":"error"} reply and the connection stays open. A typo in one
 * message is a client bug, not a protocol violation, and dropping the socket would take every
 * other conversation in that tab down with it.
 */
public class MessageRouter {

    static final int MAX_TEXT_CHARS = 4000;
    // Bounded because we echo it back in the ack; an unbounded id would let a client make
    // us reflect arbitrary amounts of data.
    private static final int MAX_CLIENT_MSG_ID_CHARS = 64;
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final ConnectionRegistry registry;
    private final JsonMapper json = JsonMapper.builder().build();

    public MessageRouter(ConnectionRegistry registry) {
        this.registry = registry;
    }

    static boolean isValidUserId(String userId) {
        return userId != null && USER_ID.matcher(userId).matches();
    }

    record Ack(String type, String clientMsgId, String id, long ts) { }

    record Message(String type, String id, String from, String to, String text, String clientMsgId, long ts) { }

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
        if (!"send".equals(type)) {
            reply(sender, error("unknown_type", "Unknown type: " + type, clientMsgId));
            return;
        }
        String to = string(msg, "to");
        String text = string(msg, "text");
        String problem = validateSend(senderId, to, clientMsgId, text);
        if (problem != null) {
            reply(sender, error(problem, describe(problem), clientMsgId));
            return;
        }

        // Temporary server id. Phase 6 replaces it with the stored row's id and a per-conversation
        // sequence number, and saves the message before acking ("never ack what isn't stored").
        // Until then, a message to an offline user is acked but lost.
        String id = UUID.randomUUID().toString();
        // Wall-clock time is right here, unlike the heartbeat: this is a timestamp people read,
        // not an interval we measure.
        long ts = System.currentTimeMillis();

        // Encoded once and shared by every recipient connection: fan-out to N tabs is N
        // queue offers, not N serializations.
        byte[] message = encode(new Message("message", id, senderId, to, text, clientMsgId, ts));
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
        reply(sender, new Ack("ack", clientMsgId, id, ts));
    }

    void onBinary(WsConnection sender) {
        reply(sender, error("unsupported", "Binary frames are not part of the protocol", null));
    }

    private static String validateSend(String senderId, String to, String clientMsgId, String text) {
        if (to == null || clientMsgId == null || text == null) {
            return "missing_field";
        }
        if (!isValidUserId(to)) {
            return "invalid_recipient";
        }
        if (to.equals(senderId)) {
            return "self_send";
        }
        if (clientMsgId.isEmpty() || clientMsgId.length() > MAX_CLIENT_MSG_ID_CHARS) {
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
            case "invalid_client_msg_id" -> "'clientMsgId' must be 1-" + MAX_CLIENT_MSG_ID_CHARS + " characters";
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
