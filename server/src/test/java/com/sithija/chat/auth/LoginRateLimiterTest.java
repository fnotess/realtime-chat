package com.sithija.chat.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    // Negative on purpose: only differences between nanoTime readings may be used.
    private final AtomicLong now = new AtomicLong(-1_000_000_000_000L);
    private final LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(15), now::get);

    @Test
    void blocksAtLimitAndUnblocksWhenWindowEnds() {
        for (int i = 0; i < 3; i++) {
            assertFalse(limiter.isBlocked("alice"));
            limiter.recordFailure("alice");
        }
        assertTrue(limiter.isBlocked("alice"));
        assertFalse(limiter.isBlocked("bob"), "keys are independent");

        now.addAndGet(Duration.ofMinutes(15).minusSeconds(1).toNanos());
        assertTrue(limiter.isBlocked("alice"));
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertFalse(limiter.isBlocked("alice"), "not a permanent lockout");

        // A new window starts from scratch.
        limiter.recordFailure("alice");
        assertFalse(limiter.isBlocked("alice"));
    }

    @Test
    void resetClearsFailures() {
        limiter.recordFailure("alice");
        limiter.recordFailure("alice");
        limiter.reset("alice");
        limiter.recordFailure("alice");
        limiter.recordFailure("alice");
        assertFalse(limiter.isBlocked("alice"));
    }
}
