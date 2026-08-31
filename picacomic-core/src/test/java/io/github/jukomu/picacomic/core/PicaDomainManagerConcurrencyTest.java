package io.github.jukomu.picacomic.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class PicaDomainManagerConcurrencyTest {

    @Test
    void prioritySnapshotsRemainSortableDuringConcurrentHealthUpdates() throws Exception {
        List<String> domains = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            domains.add("api-" + i + ".test");
        }
        PicaDomainManager manager = new PicaDomainManager(domains);
        CountDownLatch started = new CountDownLatch(8);
        ExecutorService reporters = Executors.newFixedThreadPool(8);
        List<Future<?>> updates = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                updates.add(reporters.submit(() -> {
                    started.countDown();
                    for (int iteration = 0; iteration < 100_000; iteration++) {
                        String domain = domains.get(ThreadLocalRandom.current().nextInt(domains.size()));
                        if ((iteration & 1) == 0) {
                            manager.reportFailure(domain);
                        } else {
                            manager.reportSuccess(domain);
                        }
                    }
                }));
            }
            assertTrueWithin(started);

            Set<String> expected = Set.copyOf(domains);
            for (int iteration = 0; iteration < 20_000; iteration++) {
                List<String> ordered;
                try {
                    ordered = manager.snapshotInPriorityOrder();
                } catch (RuntimeException exception) {
                    fail("concurrent priority snapshot must not violate comparator contract", exception);
                    return;
                }
                assertEquals(domains.size(), ordered.size());
                assertEquals(expected, new HashSet<>(ordered));
                assertFalse(ordered.stream().anyMatch(domain -> domain == null));
            }
            for (Future<?> update : updates) {
                update.get(10, TimeUnit.SECONDS);
            }
        } finally {
            reporters.shutdownNow();
        }
    }

    private static void assertTrueWithin(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("reporter threads did not start");
        }
    }
}
