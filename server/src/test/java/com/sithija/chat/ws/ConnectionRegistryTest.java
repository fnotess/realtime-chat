package com.sithija.chat.ws;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionRegistryTest {

    @Test
    void concurrentRegisterAndUnregisterNeverLoseAConnection() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry();
        int threads = 8;
        int rounds = 50_000;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> results = new ArrayList<>();

        // Every thread churns the SAME user, so bob's set keeps going from empty to non-empty and
        // back, and the map entry is dropped and recreated constantly. That's the window where a
        // naive registry adds a connection to a set that was just removed from the map. Each
        // thread checks that its own connection is visible right after registering; no other
        // thread ever removes it, so a miss can only be a lost registration.
        for (int t = 0; t < threads; t++) {
            WsConnection mine = new WsConnection(new ByteArrayOutputStream(), () -> { }, System::nanoTime, "c" + t, 1);
            results.add(pool.submit(() -> {
                start.await();
                int lost = 0;
                for (int i = 0; i < rounds; i++) {
                    registry.register("bob", mine);
                    if (!registry.connectionsOf("bob").contains(mine)) {
                        lost++;
                    }
                    registry.unregister("bob", mine);
                }
                return lost;
            }));
        }
        start.countDown();
        int totalLost = 0;
        for (Future<Integer> f : results) {
            totalLost += f.get();
        }
        pool.shutdown();

        assertEquals(0, totalLost, "registrations lost to the add/remove race");
        assertTrue(registry.connectionsOf("bob").isEmpty(), "entry dropped once the last connection left");
    }
}
