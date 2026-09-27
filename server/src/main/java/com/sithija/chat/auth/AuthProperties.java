package com.sithija.chat.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Auth settings, bound from chat.auth.* in application.properties.
 *
 * allowedOrigins is shared by the REST CSRF check and the WebSocket handshake, so the two can't
 * drift apart. Entries are exact origins (scheme://host:port), compared as strings.
 */
@ConfigurationProperties("chat.auth")
public record AuthProperties(
        @DefaultValue("7d") Duration sessionTtl,
        // Off by default only because local dev is plain http; must be on behind HTTPS, or the
        // cookie can leak over any http:// request to the same host.
        @DefaultValue("false") boolean cookieSecure,
        @DefaultValue("http://localhost:8080") List<String> allowedOrigins,
        // Each +1 doubles the work. 12 is ~250ms on a laptop core: slow enough to make offline
        // guessing expensive, fast enough that login still feels instant. Tests use 4.
        @DefaultValue("12") int bcryptCost,
        @DefaultValue("5") int loginMaxFailuresPerUser,
        @DefaultValue("50") int loginMaxFailuresPerIp,
        @DefaultValue("15m") Duration loginWindow) {
}
