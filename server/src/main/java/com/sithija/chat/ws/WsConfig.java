package com.sithija.chat.ws;

import com.sithija.chat.auth.AuthProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the hand-written WebSocket server into Spring's lifecycle.
 *
 * Spring doesn't handle any WebSocket traffic; it only starts our server when the app
 * boots and stops it on shutdown, so the port is released cleanly on Ctrl+C or a
 * container stop (otherwise restarts can fail with "port already in use").
 */
@Configuration
public class WsConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public WsServer wsServer(WsProperties properties, MessageStore store, SessionAuthenticator sessions,
                             AuthProperties auth) {
        // The clock is injected so tests can move time forward instead of sleeping. The Origin
        // allowlist is the same one the REST CSRF check uses, so the two can't drift apart.
        return new WsServer(properties, System::nanoTime, store, sessions, auth.allowedOrigins());
    }
}
