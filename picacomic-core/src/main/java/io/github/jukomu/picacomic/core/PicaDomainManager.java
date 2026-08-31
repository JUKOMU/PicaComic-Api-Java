package io.github.jukomu.picacomic.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理一个 API client 自己的、固定授权 host 集合及其内存健康分数。
 *
 * <p>该类只负责在已授权集合内排序；它没有远程发现、可变 host 集合或代理状态。</p>
 */
final class PicaDomainManager {

    private final List<String> domains;
    private final Map<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

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
            failureCounts.put(domain, new AtomicInteger());
        }
        this.domains = Collections.unmodifiableList(snapshot);
    }

    /**
     * 获取当前失败分数最低的 host。相同分数保持配置顺序。
     */
    String getBestDomain() {
        return snapshotInPriorityOrder().get(0);
    }

    /**
     * 为一次逻辑请求生成稳定的 host 优先级快照。
     */
    List<String> snapshotInPriorityOrder() {
        List<String> ordered = new ArrayList<>(domains);
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String domain : domains) {
            scores.put(domain, failureCounts.get(domain).get());
        }
        Map<String, Integer> immutableScores = Map.copyOf(scores);
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
            count.updateAndGet(value -> value == Integer.MAX_VALUE ? value : value + 1);
        }
    }

    Map<String, Integer> getDomainStates() {
        Map<String, Integer> states = new LinkedHashMap<>();
        for (String domain : domains) {
            states.put(domain, failureCounts.get(domain).get());
        }
        return Collections.unmodifiableMap(states);
    }
}
