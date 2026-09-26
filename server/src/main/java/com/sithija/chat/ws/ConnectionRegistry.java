package com.sithija.chat.ws;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * userId → that user's open connections (one per tab or device).
 *
 * In-memory, so it only knows about connections on this node. Running several nodes would
 * need cross-node routing (Redis pub/sub or Postgres LISTEN/NOTIFY).
 */
public class ConnectionRegistry {

    private final ConcurrentHashMap<String, Set<WsConnection>> byUser = new ConcurrentHashMap<>();

    // Both methods use compute*, which runs atomically per key. The naive version races:
    //   A (unregister): set = get("bob"); set.remove(c1); set is empty → remove("bob")
    //   B (register):   set = get("bob") → the SAME set, fetched before A removed it; set.add(c2)
    // B has added c2 to a set that is no longer in the map, so bob's new tab silently receives
    // nothing. Inside compute, "check empty and drop" and "get or create, then add" can't interleave.
    void register(String userId, WsConnection conn) {
        byUser.compute(userId, (id, set) -> {
            // The inner set is concurrent too: fan-out iterates it outside compute.
            Set<WsConnection> conns = set != null ? set : ConcurrentHashMap.newKeySet();
            conns.add(conn);
            return conns;
        });
    }

    void unregister(String userId, WsConnection conn) {
        // Returning null removes the entry, so the map doesn't keep one empty set for every
        // user who ever connected.
        byUser.computeIfPresent(userId, (id, set) -> {
            set.remove(conn);
            return set.isEmpty() ? null : set;
        });
    }

    /** A live view: may change while the caller iterates, but never throws for that. */
    Set<WsConnection> connectionsOf(String userId) {
        return byUser.getOrDefault(userId, Set.of());
    }
}
