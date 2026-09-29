package com.sithija.chat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Works out the real client IP when requests arrive through a reverse proxy (nginx in compose).
 *
 * Behind a proxy the TCP peer is the proxy for every client, so per-IP limits (login failures,
 * WebSocket connections) would lump all users into one bucket. The proxy reports the real
 * client in X-Forwarded-For, but any client can send that header too. So it is only believed
 * when the direct peer is a configured trusted proxy; otherwise the peer address is used and the
 * header ignored. With no trusted proxies configured (local dev), headers are never read.
 *
 * Shared by the REST filter and the WebSocket handshake so both key their limits the same way.
 */
public final class ClientIpResolver {

    // Literals only. InetAddress.getByName on anything else would do a DNS lookup, so a header
    // like "X-Forwarded-For: attacker.example" would make us query DNS on the attacker's behalf
    // (and block the thread on it). IPv6 literals are recognised by the colon, which getByName
    // then parses without any lookup; zone ids ("%eth0") are rejected by the character set.
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    private final List<Cidr> trusted;

    /** Entries are IP literals or CIDR ranges, e.g. "172.28.5.0/24", "10.0.0.7", "::1". */
    public ClientIpResolver(List<String> trustedProxies) {
        this.trusted = trustedProxies.stream().map(String::trim).filter(s -> !s.isEmpty()).map(Cidr::parse).toList();
    }

    public boolean isTrustedProxy(InetAddress peer) {
        for (Cidr c : trusted) {
            if (c.contains(peer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The client IP for a request whose TCP peer is {@code peer}. Walks X-Forwarded-For from the
     * right: each trusted hop appends the address it received from, so the rightmost entries are
     * the ones our own proxies wrote. The first address that isn't a trusted proxy is the client.
     * Entries further left were supplied by the client itself and are never looked at, which is
     * what makes spoofing useless ("X-Forwarded-For: 1.2.3.4" just becomes an ignored prefix).
     */
    public InetAddress resolve(InetAddress peer, String forwardedFor) {
        if (!isTrustedProxy(peer) || forwardedFor == null) {
            // A trusted proxy that sends no header: fall back to the proxy's address. That is the
            // safe direction (one shared, stricter bucket), never a looser one.
            return peer;
        }
        InetAddress client = peer;
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            InetAddress hop = parseLiteral(hops[i].trim());
            if (hop == null) {
                // Garbage where an address should be: stop at the last hop a trusted proxy vouched for.
                return client;
            }
            client = hop;
            if (!isTrustedProxy(hop)) {
                return hop;
            }
        }
        return client;
    }

    static InetAddress parseLiteral(String s) {
        if (!IPV4.matcher(s).matches() && !IPV6.matcher(s).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null; // e.g. "999.1.1.1" or a malformed IPv6 literal
        }
    }

    private record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String spec) {
            int slash = spec.indexOf('/');
            String addr = slash < 0 ? spec : spec.substring(0, slash);
            InetAddress ip = parseLiteral(addr);
            if (ip == null) {
                // Fail at startup: a typo here would otherwise silently trust nothing (or, worse,
                // someone "fixes" it by trusting everything).
                throw new IllegalArgumentException("Trusted proxy must be an IP or CIDR literal: " + spec);
            }
            byte[] bytes = ip.getAddress();
            int bits = slash < 0 ? bytes.length * 8 : Integer.parseInt(spec.substring(slash + 1));
            if (bits < 0 || bits > bytes.length * 8) {
                throw new IllegalArgumentException("Bad prefix length in trusted proxy: " + spec);
            }
            return new Cidr(bytes, bits);
        }

        boolean contains(InetAddress candidate) {
            byte[] c = candidate.getAddress();
            // Different families never match: an IPv4 range doesn't cover IPv6 peers or vice versa.
            if (c.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (c[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixBits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (c[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
