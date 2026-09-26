package com.sithija.chat.ws;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * WebSocket server settings, bound from chat.ws.* in application.properties.
 *
 * A dead peer is detected within about pingAfterIdle + pongTimeout + sweepInterval (~50s with
 * the defaults), because the sweeper only looks every sweepInterval. Shorter intervals find
 * dead peers faster but cost more pings, which matters for battery on mobile clients.
 */
@ConfigurationProperties("chat.ws")
public record WsProperties(
        @DefaultValue("8081") int port,
        @DefaultValue("30s") Duration pingAfterIdle,
        @DefaultValue("10s") Duration pongTimeout,
        @DefaultValue("10s") Duration writeTimeout,
        @DefaultValue("10s") Duration sweepInterval,
        @DefaultValue("2s") Duration shutdownGrace,
        @DefaultValue("20") int maxConnectionsPerIp,
        @DefaultValue("256") int sendQueueCapacity) {
}
