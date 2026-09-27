package com.sithija.chat.auth;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts failed logins per key (an IP or a username) in a fixed window, and blocks the key once
 * it reaches the limit, until the window ends.
 *
 * In memory, so it's per node and resets on restart; a multi-node deployment would keep these
 * counters in Redis. Fixed windows allow a burst of up to 2× the limit around a window boundary,
 * which is fine for slowing down password guessing.
 */
class LoginRateLimiter {

    // Beyond this many keys, expired windows are purged, so a flood of random usernames can't
    // grow the map without bound over time.
    private static final int PURGE_THRESHOLD = 10_000;

    private record Window(long startedAt, int failures) { }

    private final int maxFailures;
    private final long windowNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    LoginRateLimiter(int maxFailures, Duration window, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.windowNanos = window.toNanos();
        this.clock = clock;
    }

    boolean isBlocked(String key) {
        Window w = windows.get(key);
        return w != null && !expired(w, clock.getAsLong()) && w.failures() >= maxFailures;
    }

    // compute() is atomic per key: concurrent failures can't overwrite each other's count.
    void recordFailure(String key) {
        long now = clock.getAsLong();
        windows.compute(key, (k, w) -> w == null || expired(w, now) ? new Window(now, 1) : new Window(w.startedAt(), w.failures() + 1));
        if (windows.size() > PURGE_THRESHOLD) {
            windows.values().removeIf(w -> expired(w, now));
        }
    }

    void reset(String key) {
        windows.remove(key);
    }

    private boolean expired(Window w, long now) {
        return now - w.startedAt() >= windowNanos;
    }
}
