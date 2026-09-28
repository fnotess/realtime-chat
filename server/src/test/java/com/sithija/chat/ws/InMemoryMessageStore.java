package com.sithija.chat.ws;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MessageStore fake for the WebSocket tests, which are about framing and routing, not storage.
 * It follows the same contract as the Postgres version: per-conversation seq, idempotent
 * retries, unknown recipients rejected. The real SQL is tested against Postgres in
 * MessagePersistenceTest. One lock around everything keeps the fake obviously correct.
 */
class InMemoryMessageStore implements MessageStore {

    private final Set<String> users = new HashSet<>();
    private final Map<List<String>, Long> conversations = new HashMap<>();
    private final Map<Long, Long> lastSeq = new HashMap<>();
    private final Map<List<Object>, StoredMessage> byClientMsgId = new HashMap<>();
    // (conversationId, user) → {delivered, read}
    private final Map<List<Object>, long[]> receipts = new HashMap<>();
    private long nextId = 1;

    synchronized void addUser(String username) {
        users.add(username);
    }

    @Override
    public synchronized Optional<Receipt> recordReceipt(String user, long conversationId, long seq, boolean read) {
        List<String> pair = conversations.entrySet().stream()
                .filter(e -> e.getValue() == conversationId).map(Map.Entry::getKey).findFirst().orElse(null);
        if (pair == null || !pair.contains(user) || seq < 1 || seq > lastSeq.getOrDefault(conversationId, 0L)) {
            return Optional.empty();
        }
        long[] w = receipts.computeIfAbsent(List.of(conversationId, user), k -> new long[2]);
        w[0] = Math.max(w[0], seq);
        if (read) {
            w[1] = Math.max(w[1], seq);
        }
        String other = pair.get(0).equals(user) ? pair.get(1) : pair.get(0);
        return Optional.of(new Receipt(conversationId, user, other, w[0], w[1]));
    }

    @Override
    public synchronized StoredMessage save(String sender, String recipient, UUID clientMsgId, String text) {
        if (!users.contains(recipient)) {
            throw new UnknownRecipientException(recipient);
        }
        StoredMessage existing = byClientMsgId.get(List.of(sender, clientMsgId));
        if (existing != null) {
            return new StoredMessage(existing.id(), existing.conversationId(), existing.seq(), existing.from(),
                    existing.to(), existing.clientMsgId(), existing.text(), existing.createdAt(), true);
        }
        List<String> pair = sender.compareTo(recipient) < 0 ? List.of(sender, recipient) : List.of(recipient, sender);
        long conversationId = conversations.computeIfAbsent(pair, p -> nextId++);
        long seq = lastSeq.merge(conversationId, 1L, Long::sum);
        StoredMessage stored = new StoredMessage(nextId++, conversationId, seq, sender, recipient,
                clientMsgId, text, Instant.now(), false);
        byClientMsgId.put(List.of(sender, clientMsgId), stored);
        return stored;
    }
}
