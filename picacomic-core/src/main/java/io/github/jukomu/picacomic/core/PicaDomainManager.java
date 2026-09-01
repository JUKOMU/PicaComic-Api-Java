package io.github.jukomu.picacomic.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 管理一个 API client 的固定 host 集合、健康分数和可用性探测。
 */
final class PicaDomainManager {

    private static final int DEAD_MARK = Integer.MAX_VALUE / 2;

    private final List<String> domains;
    private final Map<String, AtomicInteger> failureCounts = new LinkedHashMap<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean probeRunning = new AtomicBoolean();
    private final ExecutorService probeExecutor;

    private volatile DomainProbe probe;
    private volatile CountDownLatch initialization = new CountDownLatch(0);
    private volatile boolean initialized = true;
    private volatile ScheduledExecutorService periodicProbe;
    private volatile boolean shutdown;

    PicaDomainManager(List<String> domains) {
        Objects.requireNonNull(domains, "Domains cannot be null");
        if (domains.isEmpty()) {
            throw new IllegalArgumentException("At least one API domain is required");
        }
        List<String> snapshot = new ArrayList<>(domains.size());
        for (String domain : domains) {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("Domain cannot be blank");
            }
            if (snapshot.contains(domain)) {
                throw new IllegalArgumentException("Duplicate domain");
            }
            snapshot.add(domain);
        }
        this.domains = Collections.unmodifiableList(snapshot);
        for (String domain : snapshot) {
            failureCounts.put(domain, new AtomicInteger());
        }
        this.probeExecutor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(snapshot.size(), 8)), daemonThreadFactory("pica-domain-probe"));
    }

    /**
     * Installs the client-owned probe and invalidates the initial availability snapshot.
     */
    void setProbe(DomainProbe probe) {
        Objects.requireNonNull(probe, "Domain probe cannot be null");
        synchronized (lifecycleLock) {
            if (shutdown) {
                throw new IllegalStateException("Domain manager is shut down");
            }
            this.probe = probe;
            this.initialized = false;
            this.initialization = new CountDownLatch(1);
        }
    }

    /**
     * Starts periodic checks for the configured host pool.
     */
    void startPeriodicProbe(long intervalMs) {
        if (intervalMs <= 0) {
            return;
        }
        synchronized (lifecycleLock) {
            if (shutdown || periodicProbe != null) {
                return;
            }
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                    daemonThreadFactory("pica-domain-reprobe"));
            periodicProbe = scheduler;
            scheduler.scheduleWithFixedDelay(() -> {
                DomainProbe current = probe;
                if (current == null || shutdown || !probeRunning.compareAndSet(false, true)) {
                    return;
                }
                try {
                    probeDomains(current, () -> shutdown);
                    markInitialized();
                } catch (RuntimeException ignored) {
                    // Background probe failures do not escape to request callers.
                    if (!shutdown) {
                        markInitialized();
                    }
                } finally {
                    probeRunning.set(false);
                }
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Ensures that the first request observes a completed availability probe.
     */
    void ensureInitialized(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "Cancellation check cannot be null");
        for (;;) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Domain probe cancelled");
            }
            CountDownLatch latch;
            DomainProbe current;
            boolean owner = false;
            synchronized (lifecycleLock) {
                if (shutdown) {
                    throw new CancellationException("Domain manager is shut down");
                }
                if (initialized) {
                    return;
                }
                current = probe;
                latch = initialization;
                if (current == null) {
                    initialized = true;
                    latch.countDown();
                    return;
                }
                if (probeRunning.compareAndSet(false, true)) {
                    owner = true;
                }
            }

            if (owner) {
                boolean completed = false;
                try {
                    probeDomains(current, cancelled);
                    completed = true;
                    return;
                } finally {
                    synchronized (lifecycleLock) {
                        if (completed) {
                            initialized = true;
                        } else if (initialization == latch) {
                            initialization = new CountDownLatch(1);
                        }
                        probeRunning.set(false);
                        latch.countDown();
                    }
                }
            }

            awaitInitialization(latch, cancelled);
        }
    }

    /**
     * Probes every configured host immediately. A caller may use this to request a fresh snapshot.
     */
    void probeAllDomains(DomainProbe probe) {
        Objects.requireNonNull(probe, "Domain probe cannot be null");
        if (shutdown || !probeRunning.compareAndSet(false, true)) {
            return;
        }
        boolean completed = false;
        try {
            probeDomains(probe, () -> shutdown);
            completed = true;
        } finally {
            if (completed) {
                markInitialized();
            }
            probeRunning.set(false);
        }
    }

    /**
     * 获取当前失败分数最低的 host。相同分数保持配置顺序。
     */
    String getBestDomain() {
        ensureInitialized(() -> false);
        return snapshotInPriorityOrder().get(0);
    }

    /**
     * 为一次逻辑请求生成不可变的 host 优先级快照。
     */
    List<String> snapshotInPriorityOrder() {
        List<String> ordered = new ArrayList<>(domains);
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String domain : domains) {
            scores.put(domain, failureCounts.get(domain).get());
        }
        Map<String, Integer> immutableScores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
        ordered.sort(Comparator.comparingInt(immutableScores::get));
        return Collections.unmodifiableList(ordered);
    }

    boolean contains(String domain) {
        return domains.contains(domain);
    }

    void reportSuccess(String domain) {
        AtomicInteger count = failureCounts.get(domain);
        if (count != null) {
            count.set(0);
        }
    }

    void reportFailure(String domain) {
        AtomicInteger count = failureCounts.get(domain);
        if (count != null) {
            count.updateAndGet(value -> value >= DEAD_MARK ? DEAD_MARK : value + 1);
        }
    }

    Map<String, Integer> getDomainStates() {
        Map<String, Integer> states = new LinkedHashMap<>();
        for (String domain : domains) {
            states.put(domain, failureCounts.get(domain).get());
        }
        return Collections.unmodifiableMap(states);
    }

    void shutdown() {
        ScheduledExecutorService scheduler;
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            scheduler = periodicProbe;
            periodicProbe = null;
            initialization.countDown();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        probeExecutor.shutdownNow();
    }

    private void probeDomains(DomainProbe domainProbe, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Domain probe cancelled");
        }
        List<Future<Boolean>> futures = new ArrayList<>(domains.size());
        try {
            for (String domain : domains) {
                futures.add(probeExecutor.submit(() -> {
                    if (cancelled.getAsBoolean()) {
                        return false;
                    }
                    try {
                        return domainProbe.isReachable(domain);
                    } catch (RuntimeException exception) {
                        return false;
                    }
                }));
            }

            boolean anyReachable = false;
            boolean[] reachable = new boolean[futures.size()];
            for (int i = 0; i < futures.size(); i++) {
                Future<Boolean> future = futures.get(i);
                for (;;) {
                    if (cancelled.getAsBoolean()) {
                        throw new CancellationException("Domain probe cancelled");
                    }
                    try {
                        reachable[i] = Boolean.TRUE.equals(future.get(50, TimeUnit.MILLISECONDS));
                        anyReachable |= reachable[i];
                        break;
                    } catch (java.util.concurrent.TimeoutException ignored) {
                        // Recheck cancellation while a network probe is in progress.
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Domain probe interrupted");
                    } catch (ExecutionException exception) {
                        reachable[i] = false;
                        break;
                    }
                }
            }

            if (!anyReachable) {
                for (String domain : domains) {
                    failureCounts.get(domain).set(0);
                }
            } else {
                for (int i = 0; i < domains.size(); i++) {
                    failureCounts.get(domains.get(i)).set(reachable[i] ? 0 : DEAD_MARK);
                }
            }
        } finally {
            for (Future<Boolean> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }

    private static void awaitInitialization(CountDownLatch latch, BooleanSupplier cancelled) {
        for (;;) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Domain probe cancelled");
            }
            try {
                if (latch.await(50, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Domain probe interrupted");
            }
        }
    }

    private void markInitialized() {
        synchronized (lifecycleLock) {
            initialized = true;
            initialization.countDown();
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }
}
