package com.sithija.chat.ws;

import org.springframework.beans.factory.annotation.Value;
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
    public WsServer wsServer(@Value("${chat.ws.port:8081}") int port) {
        return new WsServer(port);
    }
}
