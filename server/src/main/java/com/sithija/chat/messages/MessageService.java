package com.sithija.chat.messages;

import com.sithija.chat.messages.MessageRepository.MessageRow;
import com.sithija.chat.ws.MessageStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Stores chat messages. Transactions are opened explicitly with TransactionTemplate rather than
 * @Transactional: the duplicate-retry fallback below has to run AFTER the failed transaction
 * rolls back, and that ordering is easiest to see in code.
 */
@Service
public class MessageService implements MessageStore {

    private final MessageRepository repo;
    private final TransactionTemplate tx;

    public MessageService(MessageRepository repo, TransactionTemplate tx) {
        this.repo = repo;
        this.tx = tx;
    }

    @Override
    public void ensureUser(String username) {
        repo.ensureUser(username);
    }

    @Override
    public StoredMessage save(String sender, String recipient, UUID clientMsgId, String text) {
        long senderId = repo.findUserId(sender).orElseThrow(() -> new IllegalStateException("Unknown sender " + sender));
        long recipientId = repo.findUserId(recipient).orElseThrow(() -> new UnknownRecipientException(recipient));
        try {
            return tx.execute(status -> {
                // A retry after a reconnect: hand back the original instead of storing it twice.
                var existing = repo.findByClientMsgId(senderId, clientMsgId);
                if (existing.isPresent()) {
                    return toStored(existing.get(), sender, recipient, true);
                }
                long conversationId = repo.findOrCreateConversation(senderId, recipientId);
                long seq = repo.nextSeq(conversationId);
                MessageRow row = repo.insertMessage(conversationId, seq, senderId, clientMsgId, text);
                return toStored(row, sender, recipient, false);
            });
        } catch (DuplicateKeyException e) {
            // Two identical retries raced: both passed the check above, and the second one's
            // INSERT hit UNIQUE (sender_id, client_msg_id) once the first committed. Postgres
            // aborts the whole transaction on any error, so this one has rolled back, and its
            // last_seq increment with it: no seq is burned. The winner has committed, so read it.
            return repo.findByClientMsgId(senderId, clientMsgId)
                    .map(row -> toStored(row, sender, recipient, true))
                    .orElseThrow(() -> e);
        }
    }

    private static StoredMessage toStored(MessageRow row, String sender, String recipient, boolean duplicate) {
        return new StoredMessage(row.id(), row.conversationId(), row.seq(), sender, recipient,
                row.clientMsgId(), row.body(), row.createdAt(), duplicate);
    }
}
