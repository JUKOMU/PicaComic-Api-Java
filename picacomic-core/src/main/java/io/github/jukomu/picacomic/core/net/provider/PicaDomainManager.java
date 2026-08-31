package io.github.jukomu.picacomic.core.net.provider;

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
public final class PicaDomainManager {

    private final List<String> domains;
    private final Map<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

    public PicaDomainManager(List<String> domains) {
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
    public String getBestDomain() {
        return snapshotInPriorityOrder().get(0);
    }

    /**
     * 为一次逻辑请求生成稳定的 host 优先级快照。
     */
    public List<String> snapshotInPriorityOrder() {
        List<String> ordered = new ArrayList<>(domains);
        ordered.sort(Comparator.comparingInt(domain -> failureCounts.get(domain).get()));
        return Collections.unmodifiableList(ordered);
    }

    public boolean contains(String domain) {
        return domains.contains(domain);
    }

    public void reportSuccess(String domain) {
        AtomicInteger count = failureCounts.get(domain);
        if (count != null) {
            count.set(0);
        }
    }

    public void reportFailure(String domain) {
        AtomicInteger count = failureCounts.get(domain);
        if (count != null) {
            count.updateAndGet(value -> value == Integer.MAX_VALUE ? value : value + 1);
        }
    }

    public Map<String, Integer> getDomainStates() {
        Map<String, Integer> states = new LinkedHashMap<>();
        for (String domain : domains) {
            states.put(domain, failureCounts.get(domain).get());
        }
        return Collections.unmodifiableMap(states);
    }
}
