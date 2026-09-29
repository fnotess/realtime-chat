package com.sithija.chat.auth;

import com.sithija.chat.TestcontainersConfig;
import com.sithija.chat.ws.WsServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth against the real stack: MockMvc through AuthFilter, a Testcontainers Postgres, and the
 * real WsServer on a free port. Every test uses fresh usernames and its own client IP, because
 * the context (and so the rate limiters) is shared by all tests.
 */
// 10.255.0.1 plays the reverse proxy. The per-test client IPs are 10.0.x.x, so they stay untrusted.
@SpringBootTest(properties = {"chat.ws.port=0", "chat.auth.bcrypt-cost=4", "chat.auth.trusted-proxies=10.255.0.1"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuthTest {

    private static final String ORIGIN = "http://localhost:8080";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WsServer wsServer;

    // A distinct fake client IP per test instance (JUnit creates one per test method).
    private final String ip = "10.0." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);

    @Test
    void registerLoginAndMe() throws Exception {
        String name = fresh("Alice"); // mixed case in, lowercase stored
        MockHttpServletResponse reg = send(post("/api/auth/register"), name, "correct horse");
        assertEquals(201, reg.getStatus());
        assertTrue(reg.getContentAsString().contains("\"" + name.toLowerCase() + "\""));

        String setCookie = reg.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie);
        assertTrue(setCookie.contains("Path=/"), setCookie);
        assertTrue(setCookie.contains("Max-Age=604800"), setCookie);

        MockHttpServletResponse login = send(post("/api/auth/login"), name.toLowerCase(), "correct horse");
        assertEquals(200, login.getStatus());
        Cookie cookie = login.getCookie("chat_session");
        // A new token on every login, never the old one (session fixation).
        assertTrue(!cookie.getValue().equals(reg.getCookie("chat_session").getValue()));

        mvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(r -> assertTrue(r.getResponse().getContentAsString().contains(name.toLowerCase())));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void registerRejectsBadInputAndTakenNames() throws Exception {
        String name = fresh("bob");
        assertEquals(201, send(post("/api/auth/register"), name, "password1").getStatus());
        assertEquals(409, send(post("/api/auth/register"), name.toUpperCase(), "password1").getStatus(), "case-insensitive");
        assertEquals(400, send(post("/api/auth/register"), "ab", "password1").getStatus());
        assertEquals(400, send(post("/api/auth/register"), "bad-name!", "password1").getStatus());
        assertEquals(400, send(post("/api/auth/register"), fresh("c"), "short").getStatus());
        // 37 two-byte characters = 74 bytes: over bcrypt's limit although only 37 characters.
        assertEquals(400, send(post("/api/auth/register"), fresh("d"), "é".repeat(37)).getStatus());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameResponse() throws Exception {
        String name = fresh("carol");
        send(post("/api/auth/register"), name, "password1");

        MockHttpServletResponse wrongPassword = send(post("/api/auth/login"), name, "password2");
        MockHttpServletResponse unknownUser = send(post("/api/auth/login"), fresh("nobody"), "password2");

        assertEquals(401, wrongPassword.getStatus());
        assertEquals("{\"error\":\"Invalid username or password\"}", wrongPassword.getContentAsString());
        assertEquals(wrongPassword.getStatus(), unknownUser.getStatus());
        assertEquals(wrongPassword.getContentAsString(), unknownUser.getContentAsString());
        assertEquals(wrongPassword.getHeader("Set-Cookie"), unknownUser.getHeader("Set-Cookie"));
    }

    @Test
    void passwordLongerThan72BytesNeverMatches() throws Exception {
        // bcrypt only reads 72 bytes, and Spring's matches() compares just that prefix. Without our
        // length check, the 72-byte password plus any suffix would log in.
        String name = fresh("dave");
        String password = "p".repeat(72);
        assertEquals(201, send(post("/api/auth/register"), name, password).getStatus());
        assertEquals(401, send(post("/api/auth/login"), name, password + "anything").getStatus());
        assertEquals(200, send(post("/api/auth/login"), name, password).getStatus());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void malformedBodyIsRejectedWithoutLoggingIt(CapturedOutput output) throws Exception {
        // A client bug: the credentials JSON encoded twice, so the body is one JSON string.
        String doubleEncoded = "\"{\\\"username\\\":\\\"x\\\",\\\"password\\\":\\\"hunter2-secret\\\"}\"";
        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(doubleEncoded))
                .andExpect(status().isBadRequest());
        assertFalse(output.getAll().contains("hunter2-secret"), "password reached the log");
    }

    @Test
    void loginIsRateLimitedPerUsername() throws Exception {
        String name = fresh("erin");
        send(post("/api/auth/register"), name, "password1");
        for (int i = 0; i < 5; i++) {
            assertEquals(401, send(post("/api/auth/login"), name, "wrong" + i).getStatus());
        }
        // Blocked even with the right password, and even from another IP (a botnet).
        assertEquals(429, sendFrom("10.9.9.9", post("/api/auth/login"), name, "password1").getStatus());
        // Unknown names are limited the same way, so a 429 doesn't reveal that a name exists.
        String ghost = fresh("ghost");
        for (int i = 0; i < 5; i++) {
            send(post("/api/auth/login"), ghost, "wrong");
        }
        assertEquals(429, sendFrom("10.9.9.8", post("/api/auth/login"), ghost, "wrong").getStatus());
    }

    @Test
    void loginIsRateLimitedPerIp() throws Exception {
        // Password spraying: one IP, a different username each time.
        for (int i = 0; i < 50; i++) {
            assertEquals(401, send(post("/api/auth/login"), fresh("spray"), "password1").getStatus());
        }
        String name = fresh("frank");
        send(post("/api/auth/register"), name, "password1");
        assertEquals(429, send(post("/api/auth/login"), name, "password1").getStatus());
        assertEquals(200, sendFrom("10.9.9.7", post("/api/auth/login"), name, "password1").getStatus());
    }

    @Test
    void loginIpLimitUsesForwardedClientOnlyBehindTrustedProxy() throws Exception {
        String proxy = "10.255.0.1";
        String sprayer = "203.0.113." + (int) (Math.random() * 250);
        for (int i = 0; i < 50; i++) {
            assertEquals(401, sendVia(proxy, sprayer, fresh("spray"), "password1").getStatus());
        }
        String name = fresh("lena");
        send(post("/api/auth/register"), name, "password1");
        // The sprayer is blocked, but another user behind the same proxy is not: the limit follows
        // the forwarded client, not nginx's address.
        assertEquals(429, sendVia(proxy, sprayer, name, "password1").getStatus());
        assertEquals(200, sendVia(proxy, "198.51.100.1", name, "password1").getStatus());
        // Direct from an untrusted peer, the header is ignored: the sprayer can't claim a fresh IP...
        for (int i = 0; i < 50; i++) {
            sendVia(ip, "198.51.100." + i, fresh("spray"), "password1");
        }
        assertEquals(429, sendVia(ip, "198.51.100.200", name, "password1").getStatus());
        // ...and the header didn't charge the forged addresses either.
        assertEquals(200, sendVia(proxy, "198.51.100.2", name, "password1").getStatus());
    }

    @Test
    void stateChangingRequestsNeedAnAllowedOrigin() throws Exception {
        Cookie cookie = register(fresh("gina"));
        String body = "{\"username\":\"" + fresh("h") + "\",\"password\":\"password1\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // A cross-origin logout (with the victim's cookie) must not work either.
        mvc.perform(post("/api/auth/logout").cookie(cookie).header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void expiredSessionIsRejected() throws Exception {
        Cookie cookie = register(fresh("ivan"));
        jdbc.sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE token_hash = :h")
                .param("h", SessionService.hash(cookie.getValue())).update();
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void rawTokenIsNeverStored() throws Exception {
        String token = register(fresh("judy")).getValue();
        assertEquals(43, token.length(), "32 bytes, base64url, no padding");
        assertEquals(1, jdbc.sql("SELECT count(*) FROM sessions WHERE token_hash = :h")
                .param("h", SessionService.hash(token)).query(Integer.class).single());
        // Nowhere in any column, in any encoding Postgres would render it as text.
        assertEquals(0, jdbc.sql("SELECT count(*) FROM sessions s WHERE s::text LIKE :t OR encode(token_hash, 'escape') LIKE :t")
                .param("t", "%" + token + "%").query(Integer.class).single());
    }

    @Test
    void logoutDeletesSessionAndClosesItsOpenSocket() throws Exception {
        Cookie cookie = register(fresh("kate"));
        CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", ORIGIN)
                .header("Cookie", "chat_session=" + cookie.getValue())
                .buildAsync(URI.create("ws://localhost:" + wsServer.localPort() + "/"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeCode.complete(statusCode);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        assertNotNull(ws);

        mvc.perform(post("/api/auth/logout").cookie(cookie).header("Origin", ORIGIN))
                .andExpect(status().isNoContent())
                .andExpect(r -> assertTrue(r.getResponse().getHeader("Set-Cookie").contains("Max-Age=0")));

        assertEquals(4001, closeCode.get(5, TimeUnit.SECONDS));
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private static String fresh(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Cookie register(String name) throws Exception {
        MockHttpServletResponse r = send(post("/api/auth/register"), name, "password1");
        assertEquals(201, r.getStatus());
        return r.getCookie("chat_session");
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder req, String username, String password) throws Exception {
        return sendFrom(ip, req, username, password);
    }

    private MockHttpServletResponse sendVia(String peer, String forwardedFor, String username, String password)
            throws Exception {
        return sendFrom(peer, post("/api/auth/login").header("X-Forwarded-For", forwardedFor), username, password);
    }

    private MockHttpServletResponse sendFrom(String clientIp, MockHttpServletRequestBuilder req, String username,
                                             String password) throws Exception {
        return mvc.perform(req.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                        .with(r -> {
                            r.setRemoteAddr(clientIp);
                            return r;
                        }))
                .andReturn().getResponse();
    }
}
