package com.sithija.chat.ws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(WsProperties.class)
public class WsConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public WsServer wsServer(WsProperties properties, MessageStore store) {
        // The clock is injected so tests can move time forward instead of sleeping.
        return new WsServer(properties, System::nanoTime, store);
    }
}
