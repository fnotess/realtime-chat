package com.sithija.chat.messages;

import com.sithija.chat.TestWsClient;
import com.sithija.chat.TestcontainersConfig;
import com.sithija.chat.ws.MessageStore.Receipt;
import com.sithija.chat.ws.WsServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reconnect sync and receipts against the real stack: Postgres (Testcontainers), the real
 * WsServer on a free port, and REST through MockMvc.
 */
@SpringBootTest(properties = {"chat.ws.port=0", "chat.auth.bcrypt-cost=4"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SyncAndReceiptsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    MessageService service;
    @Autowired
    WsServer wsServer;

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Cookie> cookies = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Bob was offline for 3 messages. He reconnects the way the client does: subscribe, wait for
     * "ready", fetch everything after his last seq (in pages), then merge with what arrived live,
     * deduping by id. Alice sends one message after "ready" but before the fetch (it's in both the
     * fetch and the live stream) and one after the fetch (live only). Bob must end with all five,
     * each exactly once.
     */
    @Test
    void reconnectSyncReceivesEveryMissedMessageExactlyOnce() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        try (TestWsClient a = TestWsClient.connect(wsServer.localPort(), token(alice))) {
            List<Long> ids = new ArrayList<>();
            long conversationId = 0;
            for (int i = 1; i <= 3; i++) {
                JsonNode ack = send(a, bob, "offline " + i);
                ids.add(ack.get("id").asLong());
                conversationId = ack.get("conversationId").asLong();
            }

            // Reconnect: subscribe first.
            try (TestWsClient b = TestWsClient.connect(wsServer.localPort(), token(bob))) {
                ids.add(send(a, bob, "during sync, before fetch").get("id").asLong());
                List<JsonNode> fetched = fetchAfter(conversationId, 0, bob);
                ids.add(send(a, bob, "during sync, after fetch").get("id").asLong());

                // Both "during sync" messages have been fanned out by the time alice got their acks.
                List<JsonNode> live = new ArrayList<>(List.of(b.next("message"), b.next("message")));

                Map<Long, JsonNode> merged = new LinkedHashMap<>();
                List<JsonNode> all = new ArrayList<>(fetched);
                all.addAll(live);
                all.forEach(m -> merged.putIfAbsent(m.get("id").asLong(), m));

                assertEquals(5, merged.size());
                assertEquals(ids, merged.values().stream().sorted((x, y) -> Long.compare(x.get("seq").asLong(), y.get("seq").asLong()))
                        .map(m -> m.get("id").asLong()).toList(), "every message, in seq order");
                assertEquals(LongStream.rangeClosed(1, 5).boxed().toList(),
                        merged.values().stream().map(m -> m.get("seq").asLong()).sorted().toList(), "no gaps");
                // The overlap is real: one message came both ways, which is why dedupe by id is required.
                assertEquals(6, all.size());
                assertTrue(b.drain().stream().noneMatch(f -> "message".equals(f.get("type").stringValue())),
                        "nothing delivered live twice");
            }
        }
    }

    /**
     * Why the client subscribes before fetching: done the other way round, a message committed
     * after the fetch but before the socket is registered is in neither, and is only found on the
     * next reconnect (or never, if nothing else happens in that conversation).
     */
    @Test
    void fetchThenSubscribeWouldMissAMessage() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        long conversationId = service.save(alice, bob, UUID.randomUUID(), "one").conversationId();
        try (TestWsClient a = TestWsClient.connect(wsServer.localPort(), token(alice))) {
            List<JsonNode> fetched = fetchAfter(conversationId, 0, bob);          // fetch first...
            send(a, bob, "sent in the gap");                                        // ...a message lands...
            try (TestWsClient b = TestWsClient.connect(wsServer.localPort(), token(bob))) {  // ...then subscribe
                assertEquals(1, fetched.size());
                a.send("{\"type\":\"read\",\"conversationId\":" + conversationId + ",\"seq\":1}");
                // Alice's receipt (sent after the gap message) reaches bob, but the message never did.
                assertEquals("receipt", b.next().get("type").stringValue());
                assertTrue(b.drain().isEmpty());
            }
        }
    }

    @Test
    void afterSeqPagesForwardWithHasMore() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        long conversationId = 0;
        for (int i = 1; i <= 5; i++) {
            conversationId = service.save(alice, bob, UUID.randomUUID(), "m" + i).conversationId();
        }
        String base = "/api/conversations/" + conversationId + "/messages?limit=2&afterSeq=";
        JsonNode p1 = getJson(base + "1", bob);
        assertEquals(List.of(2L, 3L), seqs(p1));
        assertTrue(p1.get("hasMore").asBoolean());
        JsonNode p2 = getJson(base + "3", bob);
        assertEquals(List.of(4L, 5L), seqs(p2));
        assertFalse(p2.get("hasMore").asBoolean(), "exactly a full page left: no false hasMore");
        assertEquals(List.of(), seqs(getJson(base + "5", bob)));

        mvc.perform(get("/api/conversations/" + conversationId + "/messages?afterSeq=1&beforeSeq=3")
                .cookie(cookies.get(bob))).andExpect(status().isBadRequest());
    }

    @Test
    void watermarksNeverMoveBackwardsAndReadImpliesDelivered() {
        String alice = user("alice");
        String bob = user("bob");
        long conversationId = 0;
        for (int i = 1; i <= 8; i++) {
            conversationId = service.save(alice, bob, UUID.randomUUID(), "m" + i).conversationId();
        }
        assertWatermarks(5, 5, service.recordReceipt(bob, conversationId, 5, true).orElseThrow());
        assertWatermarks(5, 5, service.recordReceipt(bob, conversationId, 3, false).orElseThrow());
        assertWatermarks(5, 5, service.recordReceipt(bob, conversationId, 2, true).orElseThrow());
        assertWatermarks(7, 5, service.recordReceipt(bob, conversationId, 7, false).orElseThrow());
        assertWatermarks(8, 8, service.recordReceipt(bob, conversationId, 8, true).orElseThrow());
        assertEquals(alice, service.recordReceipt(bob, conversationId, 8, true).orElseThrow().otherUser());
    }

    /**
     * Many tabs racing receipts with random seqs, as after a reconnect. The final watermarks must be
     * the maximums sent, whatever order the transactions commit in. A read-then-write in Java, or
     * plain "SET last_read_seq = :seq", would leave whichever write committed last.
     */
    @Test
    void concurrentReceiptsKeepTheMaximum() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        long conversationId = 0;
        for (int i = 1; i <= 100; i++) {
            conversationId = service.save(alice, bob, UUID.randomUUID(), "m" + i).conversationId();
        }
        long cid = conversationId;
        long maxDelivered = 0;
        long maxRead = 0;
        List<long[]> receipts = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long seq = ThreadLocalRandom.current().nextLong(1, 100);  // 100 itself is sent last
            boolean read = ThreadLocalRandom.current().nextBoolean();
            receipts.add(new long[]{seq, read ? 1 : 0});
            maxDelivered = Math.max(maxDelivered, seq);
            if (read) {
                maxRead = Math.max(maxRead, seq);
            }
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (long[] r : receipts) {
                futures.add(pool.submit(() -> service.recordReceipt(bob, cid, r[0], r[1] == 1)));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        // A no-op receipt (seq 1) just to read the final state.
        assertWatermarks(maxDelivered, maxRead, service.recordReceipt(bob, cid, 1, false).orElseThrow());
    }

    @Test
    void outOfRangeAndNonMemberReceiptsAreRejected() {
        String alice = user("alice");
        String bob = user("bob");
        String carol = user("carol");
        long conversationId = service.save(alice, bob, UUID.randomUUID(), "hi").conversationId();

        assertTrue(service.recordReceipt(bob, conversationId, 2, true).isEmpty(), "beyond last_seq");
        assertTrue(service.recordReceipt(bob, conversationId, 0, true).isEmpty(), "seq 0");
        assertTrue(service.recordReceipt(carol, conversationId, 1, true).isEmpty(), "not a member");
        assertTrue(service.recordReceipt(bob, 999_999_999, 1, true).isEmpty(), "no such conversation");
        assertTrue(service.recordReceipt(bob, conversationId, 1, true).isPresent());
    }

    @Test
    void conversationListHasWatermarksAndUnreadCounts() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        long conversationId = 0;
        for (int i = 1; i <= 3; i++) {
            conversationId = service.save(alice, bob, UUID.randomUUID(), "a" + i).conversationId();
        }
        assertEquals(3, summary(bob).get("unreadCount").asLong());
        assertEquals(0, summary(alice).get("unreadCount").asLong(), "own messages are never unread");

        service.recordReceipt(bob, conversationId, 2, true);
        service.save(bob, alice, UUID.randomUUID(), "b4");                     // seq 4

        JsonNode forBob = summary(bob);
        assertEquals(1, forBob.get("unreadCount").asLong(), "a3 only; bob's own b4 doesn't count");
        JsonNode forAlice = summary(alice);
        assertEquals(1, forAlice.get("unreadCount").asLong());
        assertEquals(2, forAlice.get("otherDeliveredSeq").asLong());
        assertEquals(2, forAlice.get("otherReadSeq").asLong());
        assertEquals(4, forAlice.get("lastSeq").asLong());

        service.recordReceipt(alice, conversationId, 4, true);
        assertEquals(0, summary(alice).get("unreadCount").asLong());
        assertEquals(4, summary(bob).get("otherReadSeq").asLong());
    }

    // --- helpers ---

    private static void assertWatermarks(long delivered, long read, Receipt r) {
        assertEquals(delivered, r.deliveredSeq(), "delivered");
        assertEquals(read, r.readSeq(), "read");
    }

    /** Sends over the socket and waits for the ack, so the message is committed and fanned out. */
    private static JsonNode send(TestWsClient c, String to, String text) throws InterruptedException {
        c.send("{\"type\":\"send\",\"to\":\"" + to + "\",\"clientMsgId\":\"" + UUID.randomUUID() + "\",\"text\":\"" + text + "\"}");
        return c.next("ack");
    }

    /** The client's catch-up loop: pages of 2 so hasMore is exercised. */
    private List<JsonNode> fetchAfter(long conversationId, long afterSeq, String user) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        JsonNode page;
        do {
            page = getJson("/api/conversations/" + conversationId + "/messages?limit=2&afterSeq=" + afterSeq, user);
            for (JsonNode m : page.get("messages")) {
                out.add(m);
                afterSeq = m.get("seq").asLong();
            }
        } while (page.get("hasMore").asBoolean());
        return out;
    }

    private JsonNode summary(String user) throws Exception {
        JsonNode list = getJson("/api/conversations", user);
        assertEquals(1, list.size());
        return list.get(0);
    }

    private static List<Long> seqs(JsonNode page) {
        List<Long> seqs = new ArrayList<>();
        page.get("messages").forEach(m -> seqs.add(m.get("seq").asLong()));
        return seqs;
    }

    private String token(String user) {
        return cookies.get(user).getValue();
    }

    private String user(String prefix) {
        String name = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Cookie cookie = mvc.perform(post("/api/auth/register").header("Origin", "http://localhost:8080")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getCookie("chat_session");
            cookies.put(name, cookie);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return name;
    }

    private JsonNode getJson(String url, String user) throws Exception {
        return json.readTree(mvc.perform(get(url).cookie(cookies.get(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
