package com.sithija.chat.messages;

import com.sithija.chat.auth.AuthFilter;
import com.sithija.chat.messages.MessageRepository.ConversationSummary;
import com.sithija.chat.messages.MessageRepository.MessageRow;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Conversation list and message history over REST. Live messages arrive over the WebSocket;
 * this is for opening a chat, or catching up after being offline.
 *
 * The caller is the session's user, resolved from the cookie by AuthFilter. Required, so a request
 * that somehow skipped the filter fails with 400 instead of running as nobody.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final int MAX_LIMIT = 100;

    private final MessageRepository repo;

    public ConversationController(MessageRepository repo) {
        this.repo = repo;
    }

    // hasMore: more messages exist in the direction being paged (older for beforeSeq, newer for
    // afterSeq). nextBeforeSeq is the cursor for the next older page, null when paging forwards.
    public record Page(List<HistoryMessage> messages, Long nextBeforeSeq, boolean hasMore) { }

    // Same field names as the WebSocket "message" payload, so the client renders both the same way.
    public record HistoryMessage(long id, long conversationId, long seq, String from, String to,
                                 String text, String clientMsgId, long ts) { }

    @GetMapping
    public List<ConversationSummary> list(@RequestAttribute(AuthFilter.USERNAME) String me) {
        return repo.conversationsOf(me);
    }

    @GetMapping("/{id}/messages")
    public Page history(@RequestAttribute(AuthFilter.USERNAME) String me,
                        @PathVariable long id,
                        @RequestParam(required = false) Long beforeSeq,
                        @RequestParam(required = false) Long afterSeq,
                        @RequestParam(defaultValue = "50") int limit) {
        if (beforeSeq != null && afterSeq != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use beforeSeq or afterSeq, not both");
        }
        // 404, not 403, for "exists but not yours": a 403 would confirm the id exists, letting
        // anyone enumerate conversation ids by probing.
        String other = repo.otherMember(id, me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        int pageSize = Math.clamp(limit, 1, MAX_LIMIT);

        if (afterSeq != null) {
            // One extra row answers "is there more?" without a count query. The client keeps asking
            // from its new last seq until hasMore is false, so a long offline period is fetched in
            // bounded pages rather than one unbounded response.
            List<MessageRow> after = repo.historyAfter(id, afterSeq, pageSize + 1);
            boolean more = after.size() > pageSize;
            return new Page(toHistory(more ? after.subList(0, pageSize) : after, me, other), null, more);
        }

        List<MessageRow> rows = repo.historyBefore(id, beforeSeq == null ? Long.MAX_VALUE : beforeSeq, pageSize);
        // Fetched newest first (that's what LIMIT has to cut), returned oldest first for display.
        List<HistoryMessage> messages = toHistory(rows.reversed(), me, other);
        // A full page whose oldest seq is above 1 means older messages exist.
        Long next = rows.size() == pageSize && messages.getFirst().seq() > 1 ? messages.getFirst().seq() : null;
        return new Page(messages, next, next != null);
    }

    private static List<HistoryMessage> toHistory(List<MessageRow> rows, String me, String other) {
        return rows.stream()
                .map(r -> new HistoryMessage(r.id(), r.conversationId(), r.seq(), r.sender(),
                        r.sender().equals(me) ? other : me, r.body(), r.clientMsgId().toString(),
                        r.createdAt().toEpochMilli()))
                .toList();
    }
}
