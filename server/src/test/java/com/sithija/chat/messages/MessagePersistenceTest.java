package com.sithija.chat.messages;

import com.sithija.chat.TestcontainersConfig;
import com.sithija.chat.ws.MessageStore.StoredMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against a real Postgres (Testcontainers), because the guarantees under test (row locks,
 * ON CONFLICT, unique constraints) belong to the database. A fake would only test itself.
 * Every test uses fresh usernames, since all tests share one database.
 */
@SpringBootTest(properties = "chat.ws.port=0")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MessagePersistenceTest {

    @Autowired
    MessageService service;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MockMvc mvc;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void concurrentSendersGetDistinctConsecutiveSeqs() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        List<Callable<StoredMessage>> sends = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            sends.add(() -> service.save(alice, bob, UUID.randomUUID(), "from alice " + n));
            sends.add(() -> service.save(bob, alice, UUID.randomUUID(), "from bob " + n));
        }

        // Virtual threads, as in production: 100 concurrent senders, 10 pooled connections.
        List<StoredMessage> stored = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<StoredMessage> f : pool.invokeAll(sends)) {
                stored.add(f.get());
            }
        }

        long conversationId = stored.getFirst().conversationId();
        assertTrue(stored.stream().allMatch(m -> m.conversationId() == conversationId), "one conversation");
        List<Long> seqs = stored.stream().map(StoredMessage::seq).sorted().toList();
        assertEquals(LongStream.rangeClosed(1, 100).boxed().toList(), seqs, "gapless 1..100, no duplicates");
        assertEquals(100L, lastSeq(conversationId));
    }

    @Test
    void duplicateClientMsgIdReturnsSameMessageAndBurnsNoSeq() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        UUID clientMsgId = UUID.randomUUID();

        StoredMessage first = service.save(alice, bob, clientMsgId, "hi");
        StoredMessage retry = service.save(alice, bob, clientMsgId, "hi");
        assertFalse(first.duplicate());
        assertTrue(retry.duplicate());
        assertEquals(first.id(), retry.id());
        assertEquals(first.seq(), retry.seq());
        assertEquals(2, service.save(alice, bob, UUID.randomUUID(), "next").seq(), "the retry used no seq");

        // Identical retries racing each other: most see the row in the up-front check, and any
        // that slip past it hit the unique constraint and roll back.
        UUID raced = UUID.randomUUID();
        List<StoredMessage> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<StoredMessage>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return service.save(alice, bob, raced, "raced");
                }));
            }
            go.countDown();
            for (Future<StoredMessage> f : futures) {
                results.add(f.get());
            }
        }
        assertEquals(1, results.stream().map(StoredMessage::id).distinct().count(), "all got the same message");
        assertEquals(1, results.stream().filter(m -> !m.duplicate()).count(), "exactly one actually stored it");
        assertEquals(3L, lastSeq(first.conversationId()), "rolled-back losers burned no seq");
        assertEquals(1, jdbc.sql("SELECT count(*) FROM messages WHERE client_msg_id = :id")
                .param("id", raced).query(Integer.class).single());
    }

    @Test
    void firstMessagesFromBothSidesAtOnceCreateOneConversation() throws Exception {
        // Many fresh pairs, each racing its very first message from both sides, so the
        // INSERT ... ON CONFLICT path really gets contended.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int pair = 0; pair < 20; pair++) {
                String a = user("a");
                String b = user("b");
                CountDownLatch go = new CountDownLatch(1);
                Future<StoredMessage> ab = pool.submit(() -> {
                    go.await();
                    return service.save(a, b, UUID.randomUUID(), "hi b");
                });
                Future<StoredMessage> ba = pool.submit(() -> {
                    go.await();
                    return service.save(b, a, UUID.randomUUID(), "hi a");
                });
                go.countDown();

                assertEquals(ab.get().conversationId(), ba.get().conversationId());
                assertEquals(1, jdbc.sql("""
                        SELECT count(*) FROM conversations c
                        JOIN users ua ON ua.id = c.user_a_id JOIN users ub ON ub.id = c.user_b_id
                        WHERE (ua.username, ub.username) IN ((:a, :b), (:b, :a))
                        """).param("a", a).param("b", b).query(Integer.class).single());
                assertEquals(3, ab.get().seq() + ba.get().seq(), "seqs 1 and 2");
            }
        }
    }

    @Test
    void historyPagesBySeq() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        long conversationId = 0;
        for (int i = 1; i <= 120; i++) {
            conversationId = service.save(alice, bob, UUID.randomUUID(), "m" + i).conversationId();
        }
        String url = "/api/conversations/" + conversationId + "/messages?limit=50";

        JsonNode page1 = getJson(url, bob);
        assertSeqs(page1, 71, 120);
        assertEquals(71, page1.get("nextBeforeSeq").asLong());
        JsonNode first = page1.get("messages").get(0);
        assertEquals(alice, first.get("from").stringValue());
        assertEquals(bob, first.get("to").stringValue());
        assertEquals("m71", first.get("text").stringValue());

        JsonNode page2 = getJson(url + "&beforeSeq=71", bob);
        assertSeqs(page2, 21, 70);
        assertEquals(21, page2.get("nextBeforeSeq").asLong());

        JsonNode page3 = getJson(url + "&beforeSeq=21", bob);
        assertSeqs(page3, 1, 20);
        assertTrue(page3.get("nextBeforeSeq").isNull(), "no older messages");
    }

    @Test
    void conversationListIsNewestFirstWithLastMessage() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        String carol = user("carol");
        service.save(alice, bob, UUID.randomUUID(), "to bob");
        service.save(carol, alice, UUID.randomUUID(), "from carol");

        JsonNode list = getJson("/api/conversations", alice);
        assertEquals(2, list.size());
        assertEquals(carol, list.get(0).get("otherUser").stringValue());
        assertEquals("from carol", list.get(0).get("lastBody").stringValue());
        assertEquals(bob, list.get(1).get("otherUser").stringValue());
    }

    @Test
    void nonMemberGets404NotForbidden() throws Exception {
        String alice = user("alice");
        String bob = user("bob");
        String carol = user("carol");
        long conversationId = service.save(alice, bob, UUID.randomUUID(), "private").conversationId();

        // Same response for "not yours" and "doesn't exist", so ids can't be probed.
        mvc.perform(get("/api/conversations/" + conversationId + "/messages").header("X-Dev-User", carol))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations/999999999/messages").header("X-Dev-User", carol))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations/" + conversationId + "/messages"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private String user(String prefix) {
        String name = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        service.ensureUser(name);
        return name;
    }

    private long lastSeq(long conversationId) {
        return jdbc.sql("SELECT last_seq FROM conversations WHERE id = :id")
                .param("id", conversationId).query(Long.class).single();
    }

    private JsonNode getJson(String url, String user) throws Exception {
        String body = mvc.perform(get(url).header("X-Dev-User", user))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static void assertSeqs(JsonNode page, long from, long to) {
        List<Long> seqs = new ArrayList<>();
        page.get("messages").forEach(m -> seqs.add(m.get("seq").asLong()));
        assertEquals(LongStream.rangeClosed(from, to).boxed().toList(), seqs, "oldest first within the page");
    }
}
