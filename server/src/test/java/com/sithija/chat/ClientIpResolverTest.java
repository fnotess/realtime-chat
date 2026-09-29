package com.sithija.chat;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(List.of("172.28.5.0/24", "10.0.0.7", "fd00::/8"));

    @Test
    void untrustedPeerIsTheClientWhateverItClaims() {
        // The spoofing case: a client talking to us directly makes up a header to get a fresh bucket.
        assertEquals(ip("198.51.100.9"), resolver.resolve(ip("198.51.100.9"), "203.0.113.1"));
        assertEquals(ip("172.28.6.1"), resolver.resolve(ip("172.28.6.1"), "203.0.113.1"), "just outside the /24");
    }

    @Test
    void trustedProxyReportsTheClient() {
        assertEquals(ip("203.0.113.1"), resolver.resolve(ip("172.28.5.3"), "203.0.113.1"));
    }

    @Test
    void clientSuppliedPrefixIsIgnored() {
        // nginx appends the real peer to whatever the client sent; only the rightmost entry is ours.
        assertEquals(ip("203.0.113.1"), resolver.resolve(ip("172.28.5.3"), "1.1.1.1, 2.2.2.2,203.0.113.1"));
    }

    @Test
    void chainedTrustedProxiesAreSkipped() {
        // A load balancer (10.0.0.7) in front of nginx: skip both our hops, stop at the first untrusted one.
        assertEquals(ip("203.0.113.1"), resolver.resolve(ip("172.28.5.3"), "9.9.9.9, 203.0.113.1, 10.0.0.7"));
    }

    @Test
    void missingOrGarbageHeaderFallsBackToTheLastTrustedHop() {
        assertEquals(ip("172.28.5.3"), resolver.resolve(ip("172.28.5.3"), null));
        assertEquals(ip("172.28.5.3"), resolver.resolve(ip("172.28.5.3"), "unknown"));
        assertEquals(ip("10.0.0.7"), resolver.resolve(ip("172.28.5.3"), "not-an-ip, 10.0.0.7"));
    }

    @Test
    void ipv6RangesMatchOnlyIpv6() {
        assertTrue(resolver.isTrustedProxy(ip("fd12::1")));
        assertFalse(resolver.isTrustedProxy(ip("fe80::1")));
        assertEquals(ip("2001:db8::1"), resolver.resolve(ip("fd12::1"), "2001:db8::1"));
    }

    @Test
    void emptyConfigTrustsNobody() {
        ClientIpResolver none = new ClientIpResolver(List.of(""));
        assertEquals(ip("127.0.0.1"), none.resolve(ip("127.0.0.1"), "203.0.113.1"));
    }

    @Test
    void hostnamesAreNeverResolved() {
        // Would otherwise trigger a DNS lookup chosen by the client.
        assertNull(ClientIpResolver.parseLiteral("localhost"));
        assertNull(ClientIpResolver.parseLiteral("cafe"));
        assertNull(ClientIpResolver.parseLiteral("999.1.1.1"));
        assertNull(ClientIpResolver.parseLiteral("fe80::1%eth0"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(List.of("web")));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(List.of("10.0.0.0/33")));
    }

    private static InetAddress ip(String literal) {
        return ClientIpResolver.parseLiteral(literal);
    }
}
