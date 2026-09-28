package com.sithija.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A browser-like WebSocket client for Spring tests (the JDK's built-in one), sending the session
 * cookie and an allowed Origin. Every JSON frame received lands in a queue, in arrival order.
 */
public final class TestWsClient implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    private final WebSocket ws;

    private TestWsClient(int port, String sessionToken) throws Exception {
        ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:8080")
                .header("Cookie", "chat_session=" + sessionToken)
                .buildAsync(URI.create("ws://localhost:" + port + "/"), new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        // The JDK may hand one frame over in pieces.
                        partial.append(data);
                        if (last) {
                            frames.add(JSON.readTree(partial.toString()));
                            partial.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
    }

    /** Connects and waits for "ready": from then on, every new message reaches this client live. */
    public static TestWsClient connect(int port, String sessionToken) throws Exception {
        TestWsClient c = new TestWsClient(port, sessionToken);
        assertEquals("ready", c.next().get("type").stringValue());
        return c;
    }

    public void send(String json) {
        ws.sendText(json, true).join();
    }

    public JsonNode next() throws InterruptedException {
        JsonNode frame = frames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame, "no frame within 5s");
        return frame;
    }

    /** Skips frames of other types (e.g. receipts) until one of this type arrives. */
    public JsonNode next(String type) throws InterruptedException {
        JsonNode frame;
        do {
            frame = next();
        } while (!type.equals(frame.get("type").stringValue()));
        return frame;
    }

    /** Frames that have already arrived, without waiting. */
    public java.util.List<JsonNode> drain() {
        java.util.List<JsonNode> out = new java.util.ArrayList<>();
        frames.drainTo(out);
        return out;
    }

    @Override
    public void close() {
        ws.abort();
    }
}
