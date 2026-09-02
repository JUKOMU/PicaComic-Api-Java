package io.github.jukomu.picacomic.core.net.provider;

/**
 * 探测单个 API host 是否可用的内部回调。
 */
@FunctionalInterface
public interface DomainProbe {

    /**
     * 探测指定 host。
     *
     * @param domain 待探测的 host
     * @return host 是否可用
     */
    boolean isReachable(String domain);
}
