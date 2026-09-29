package com.sithija.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness probe for the compose healthcheck, so nginx only starts once the server can serve.
 *
 * Hand-written rather than Actuator: one endpoint doesn't justify the dependency and its default
 * endpoint set. Outside /api/, so AuthFilter doesn't require a session, and nginx doesn't route it,
 * so it's reachable only from inside the compose network.
 *
 * Answering at all implies the WebSocket port is up too: WsServer binds in its bean's init, and
 * Tomcat accepts requests only after every bean has initialised. The query adds the database.
 */
@RestController
class HealthController {

    private final JdbcClient jdbc;

    HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/healthz")
    ResponseEntity<String> health() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return ResponseEntity.ok("ok");
        } catch (RuntimeException e) {
            // 503 without details: the reason is in the server log, not in a probe response.
            return ResponseEntity.status(503).body("db unavailable");
        }
    }
}
