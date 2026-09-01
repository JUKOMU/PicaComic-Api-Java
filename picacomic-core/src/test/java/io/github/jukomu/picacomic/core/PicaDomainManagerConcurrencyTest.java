package io.github.jukomu.picacomic.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            manager.shutdown();
        }
    }

    @Test
    void cancelledInitialProbeWakesWaitersAndInstallsANewLatchForRecovery() throws Exception {
        PicaDomainManager manager = new PicaDomainManager(List.of("api-one.test"));
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicBoolean cancelOwner = new AtomicBoolean();
        CountDownLatch firstProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstProbe = new CountDownLatch(1);
        CountDownLatch secondProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondProbe = new CountDownLatch(1);
        manager.setProbe(domain -> {
            int call = probeCalls.incrementAndGet();
            if (call == 1) {
                firstProbeStarted.countDown();
                awaitProbeRelease(releaseFirstProbe);
                return false;
            }
            if (call == 2) {
                secondProbeStarted.countDown();
                awaitProbeRelease(releaseSecondProbe);
            }
            return true;
        });

        ExecutorService callers = Executors.newFixedThreadPool(4);
        Future<?> owner = callers.submit(() -> manager.ensureInitialized(cancelOwner::get));
        CountDownLatch waitersReady = new CountDownLatch(3);
        List<Future<?>> waiters = new ArrayList<>();
        try {
            assertTrue(firstProbeStarted.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) {
                waiters.add(callers.submit(() -> {
                    waitersReady.countDown();
                    manager.ensureInitialized(() -> false);
                }));
            }
            assertTrue(waitersReady.await(2, TimeUnit.SECONDS));
            cancelOwner.set(true);

            assertTrue(secondProbeStarted.await(2, TimeUnit.SECONDS));
            Field initialization = PicaDomainManager.class.getDeclaredField("initialization");
            initialization.setAccessible(true);
            CountDownLatch recoveryLatch = (CountDownLatch) initialization.get(manager);
            assertEquals(1, recoveryLatch.getCount(),
                    "a cancelled generation must publish a latch for the next generation");

            releaseSecondProbe.countDown();
            ExecutionException ownerFailure = assertThrows(ExecutionException.class,
                    () -> owner.get(2, TimeUnit.SECONDS));
            assertTrue(ownerFailure.getCause() instanceof CancellationException);
            for (Future<?> waiter : waiters) {
                waiter.get(2, TimeUnit.SECONDS);
            }
            assertTrue(probeCalls.get() >= 2);
        } finally {
            cancelOwner.set(true);
            releaseFirstProbe.countDown();
            releaseSecondProbe.countDown();
            callers.shutdownNow();
            manager.shutdown();
        }
    }

    private static void awaitProbeRelease(CountDownLatch release) {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertTrueWithin(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("reporter threads did not start");
        }
    }
}
