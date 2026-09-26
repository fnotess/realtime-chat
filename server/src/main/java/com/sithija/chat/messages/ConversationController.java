package com.sithija.chat.messages;

import com.sithija.chat.messages.MessageRepository.ConversationSummary;
import com.sithija.chat.messages.MessageRepository.MessageRow;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Conversation list and message history over REST. Live messages arrive over the WebSocket;
 * this is for opening a chat, or catching up after being offline.
 *
 * DEV ONLY: the caller is whoever the X-Dev-User header claims to be. Phase 7 replaces it
 * with the session cookie.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final int MAX_LIMIT = 100;

    private final MessageRepository repo;

    public ConversationController(MessageRepository repo) {
        this.repo = repo;
    }

    public record Page(List<HistoryMessage> messages, Long nextBeforeSeq) { }

    // Same field names as the WebSocket "message" payload, so the client renders both the same way.
    public record HistoryMessage(long id, long conversationId, long seq, String from, String to,
                                 String text, String clientMsgId, long ts) { }

    @GetMapping
    public List<ConversationSummary> list(@RequestHeader(value = "X-Dev-User", required = false) String user) {
        return repo.conversationsOf(requireUser(user));
    }

    @GetMapping("/{id}/messages")
    public Page history(@RequestHeader(value = "X-Dev-User", required = false) String user,
                        @PathVariable long id,
                        @RequestParam(required = false) Long beforeSeq,
                        @RequestParam(defaultValue = "50") int limit) {
        String me = requireUser(user);
        // 404, not 403, for "exists but not yours": a 403 would confirm the id exists, letting
        // anyone enumerate conversation ids by probing.
        String other = repo.otherMember(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        int pageSize = Math.clamp(limit, 1, MAX_LIMIT);

        List<MessageRow> rows = repo.historyBefore(id, beforeSeq == null ? Long.MAX_VALUE : beforeSeq, pageSize);
        // Fetched newest first (that's what LIMIT has to cut), returned oldest first for display.
        List<HistoryMessage> messages = rows.reversed().stream()
                .map(r -> new HistoryMessage(r.id(), r.conversationId(), r.seq(), r.sender(),
                        r.sender().equals(me) ? other : me, r.body(), r.clientMsgId().toString(),
                        r.createdAt().toEpochMilli()))
                .toList();
        // A full page whose oldest seq is above 1 means older messages exist.
        Long next = rows.size() == pageSize && messages.getFirst().seq() > 1 ? messages.getFirst().seq() : null;
        return new Page(messages, next);
    }

    private static String requireUser(String user) {
        if (user == null || user.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Dev-User header required");
        }
        return user;
    }
}
