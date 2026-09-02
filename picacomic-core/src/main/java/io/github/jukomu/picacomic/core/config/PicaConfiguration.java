package io.github.jukomu.picacomic.core.config;

import io.github.jukomu.picacomic.api.enums.ImageQuality;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * 创建 client 所需的不可变配置快照。
 *
 * <p>运行时状态（Cookie、缓存、域名健康度和关闭状态）不属于配置，
 * 因而同一个配置可以安全地创建多个相互隔离的 client。</p>
 */
public final class PicaConfiguration {

    private final List<String> domains;
    private final Proxy proxy;
    private final Duration timeout;
    private final int retryTimes;
    private final long domainProbeIntervalMs;
    private final long domainProbeTimeoutMs;
    private final Duration imageTimeout;
    private final long closeTimeoutMs;
    private final ExecutorService executor;
    private final int downloadThreadPoolSize;
    private final int cacheSize;
    private final int concurrentPhotoDownloads;
    private final int concurrentImageDownloads;
    private final ImageQuality imageQuality;
    private PicaConfiguration(Builder builder) {
        this.domains = normalizeDomains(builder.domains);
        this.proxy = builder.proxy;
        this.timeout = validateTimeout(builder.timeout);
        this.retryTimes = validateNonNegative(builder.retryTimes, "Retry times");
        this.domainProbeIntervalMs = validateNonNegative(builder.domainProbeIntervalMs,
                "Domain probe interval");
        this.domainProbeTimeoutMs = validatePositiveLong(builder.domainProbeTimeoutMs,
                "Domain probe timeout");
        this.imageTimeout = validateTimeout(builder.imageTimeout);
        this.closeTimeoutMs = validateNonNegative(builder.closeTimeoutMs, "Close timeout");
        this.executor = builder.executor;
        this.downloadThreadPoolSize = validatePositive(builder.downloadThreadPoolSize, "Download thread pool size");
        this.cacheSize = validateNonNegative(builder.cacheSize, "Cache size");
        this.concurrentPhotoDownloads = validatePositive(builder.concurrentPhotoDownloads, "Concurrent photo downloads");
        this.concurrentImageDownloads = validatePositive(builder.concurrentImageDownloads, "Concurrent image downloads");
        this.imageQuality = Objects.requireNonNull(builder.imageQuality, "Image quality cannot be null");
    }

    /**
     * 获取调用者显式提供的 API host 快照。空列表表示使用 core 的默认 API hosts。
     *
     * @return API host 的不可修改列表
     */
    public List<String> getDomains() {
        return domains;
    }

    /**
     * 获取调用者显式配置的传输代理。库不会在失败时自行切换代理。
     *
     * @return 传输代理；未配置时为 {@code null}
     */
    public Proxy getProxy() {
        return proxy;
    }

    public Duration getTimeout() {
        return timeout;
    }

    /**
     * 首次请求之后允许的额外 GET/HEAD 尝试次数。
     *
     * @return 额外尝试次数
     */
    public int getRetryTimes() {
        return retryTimes;
    }

    /**
     * 获取 API host 周期探测间隔；零值表示不启动周期探测。
     *
     * @return 探测间隔，单位为毫秒
     */
    public long getDomainProbeIntervalMs() {
        return domainProbeIntervalMs;
    }

    /**
     * 获取单个 API host 探测的网络超时。
     *
     * @return 探测超时，单位为毫秒
     */
    public long getDomainProbeTimeoutMs() {
        return domainProbeTimeoutMs;
    }

    /**
     * 获取图片响应体的读取超时。
     *
     * @return 图片读取超时
     */
    public Duration getImageTimeout() {
        return imageTimeout;
    }

    /**
     * 获取 client 关闭时等待自有批量任务的最长时间。
     *
     * @return 关闭等待时间，单位为毫秒
     */
    public long getCloseTimeoutMs() {
        return closeTimeoutMs;
    }

    /**
     * 外部下载 executor。该 executor 的生命周期始终由调用者负责。
     *
     * @return 外部 executor；未配置时为 {@code null}
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * 获取 client 自有下载线程池的大小。
     *
     * @return 下载线程数
     */
    public int getDownloadThreadPoolSize() {
        return downloadThreadPoolSize;
    }

    /**
     * 获取内存缓存容量。
     *
     * @return 缓存容量，单位为字节
     */
    public int getCacheSize() {
        return cacheSize;
    }

    /**
     * 获取同时处理的章节数量。
     *
     * @return 并发章节数
     */
    public int getConcurrentPhotoDownloads() {
        return concurrentPhotoDownloads;
    }

    /**
     * 获取同时读取图片的数量。
     *
     * @return 并发图片数
     */
    public int getConcurrentImageDownloads() {
        return concurrentImageDownloads;
    }

    /**
     * 获取 API 请求使用的图片质量参数。
     *
     * @return 图片质量
     */
    public ImageQuality getImageQuality() {
        return imageQuality;
    }

    /**
     * 用于创建 {@link PicaConfiguration} 的 Builder。
     */
    public static class Builder {
        private List<String> domains = new ArrayList<>();
        private Proxy proxy;
        private Duration timeout = Duration.ofSeconds(30);
        private int retryTimes = 5;
        private long domainProbeIntervalMs = 10 * 60 * 1000L;
        private long domainProbeTimeoutMs = 3 * 1000L;
        private Duration imageTimeout = Duration.ofSeconds(60);
        private long closeTimeoutMs = 60 * 1000L;
        private ExecutorService executor;
        private int downloadThreadPoolSize = 12;
        private int cacheSize = 100 * 1024 * 1024;
        private int concurrentPhotoDownloads = 3;
        private int concurrentImageDownloads = 20;
        private ImageQuality imageQuality = ImageQuality.MEDIUM;

        /**
         * 设置 API host 列表。空列表表示使用 core 默认 host。
         *
         * @param domains API host 列表
         * @return 当前 builder
         */
        public Builder domains(List<String> domains) {
            this.domains = new ArrayList<>(Objects.requireNonNull(domains, "Domains cannot be null"));
            return this;
        }

        /**
         * 设置显式 HTTP 代理。
         *
         * @param host 代理 host
         * @param port 代理端口
         * @return 当前 builder
         */
        public Builder proxy(String host, int port) {
            this.proxy = createProxy(host, port);
            return this;
        }

        /**
         * 设置 API 请求的连接、读、写和调用总超时。
         *
         * @param timeout 请求超时
         * @return 当前 builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = validateTimeout(timeout);
            return this;
        }

        /**
         * 设置首次请求之后允许的额外 GET/HEAD 尝试次数。
         *
         * @param retryTimes 额外尝试次数
         * @return 当前 builder
         */
        public Builder retryTimes(int retryTimes) {
            this.retryTimes = validateNonNegative(retryTimes, "Retry times");
            return this;
        }

        /**
         * 设置 API host 周期探测间隔。
         *
         * @param intervalMs 探测间隔，单位为毫秒；非正数表示不启动周期探测
         * @return 当前 builder
         */
        public Builder domainProbeIntervalMs(long intervalMs) {
            this.domainProbeIntervalMs = validateNonNegative(intervalMs, "Domain probe interval");
            return this;
        }

        /**
         * 设置单个 API host 探测超时。
         *
         * @param timeoutMs 探测超时，单位为毫秒
         * @return 当前 builder
         */
        public Builder domainProbeTimeoutMs(long timeoutMs) {
            this.domainProbeTimeoutMs = validatePositiveLong(timeoutMs, "Domain probe timeout");
            return this;
        }

        /**
         * 设置图片响应体读取超时。
         *
         * @param imageTimeout 图片读取超时
         * @return 当前 builder
         */
        public Builder imageTimeout(Duration imageTimeout) {
            this.imageTimeout = validateTimeout(imageTimeout);
            return this;
        }

        /**
         * 设置 client 关闭时等待自有批量任务的最长时间。
         *
         * @param timeoutMs 关闭等待时间，单位为毫秒
         * @return 当前 builder
         */
        public Builder closeTimeoutMs(long timeoutMs) {
            this.closeTimeoutMs = validateNonNegative(timeoutMs, "Close timeout");
            return this;
        }

        /**
         * 设置 client 自有下载线程池大小。
         *
         * @param size 线程数
         * @return 当前 builder
         */
        public Builder downloadThreadPoolSize(int size) {
            this.downloadThreadPoolSize = validatePositive(size, "Download thread pool size");
            return this;
        }

        /**
         * 注入外部下载 executor。client 关闭时不会关闭它。
         *
         * @param executor 外部 executor；传入 {@code null} 时由 client 创建自有线程池
         * @return 当前 builder
         */
        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        /**
         * 设置内存缓存容量。
         *
         * @param size 缓存容量，单位为字节
         * @return 当前 builder
         */
        public Builder cacheSize(int size) {
            this.cacheSize = validateNonNegative(size, "Cache size");
            return this;
        }

        /**
         * 设置同时处理的章节数量。
         *
         * @param size 并发章节数
         * @return 当前 builder
         */
        public Builder concurrentPhotoDownloads(int size) {
            this.concurrentPhotoDownloads = validatePositive(size, "Concurrent photo downloads");
            return this;
        }

        /**
         * 设置同时读取图片的数量。
         *
         * @param size 并发图片数
         * @return 当前 builder
         */
        public Builder concurrentImageDownloads(int size) {
            this.concurrentImageDownloads = validatePositive(size, "Concurrent image downloads");
            return this;
        }

        /**
         * 设置 API 请求使用的图片质量参数。
         *
         * @param imageQuality 图片质量
         * @return 当前 builder
         */
        public Builder imageQuality(ImageQuality imageQuality) {
            this.imageQuality = Objects.requireNonNull(imageQuality, "Image quality cannot be null");
            return this;
        }

        /**
         * 从 properties 流加载支持的配置项。
         *
         * @param inputStream properties 输入流
         * @return 当前 builder
         * @throws IOException 读取输入流失败时抛出
         */
        public Builder loadFromProperties(InputStream inputStream) throws IOException {
            Properties props = new Properties();
            props.load(Objects.requireNonNull(inputStream, "Input stream cannot be null"));

            if (props.containsKey("proxy.host") && props.containsKey("proxy.port")) {
                proxy(props.getProperty("proxy.host"), Integer.parseInt(props.getProperty("proxy.port")));
            }
            if (props.containsKey("retry.times")) {
                retryTimes(Integer.parseInt(props.getProperty("retry.times")));
            }
            if (props.containsKey("domain.probe.interval.ms")) {
                domainProbeIntervalMs(Long.parseLong(props.getProperty("domain.probe.interval.ms")));
            }
            if (props.containsKey("domain.probe.timeout.ms")) {
                domainProbeTimeoutMs(Long.parseLong(props.getProperty("domain.probe.timeout.ms")));
            }
            if (props.containsKey("image.timeout.seconds")) {
                imageTimeout(Duration.ofSeconds(Long.parseLong(props.getProperty("image.timeout.seconds"))));
            }
            if (props.containsKey("close.timeout.ms")) {
                closeTimeoutMs(Long.parseLong(props.getProperty("close.timeout.ms")));
            }
            if (props.containsKey("download.thread.pool.size")) {
                downloadThreadPoolSize(Integer.parseInt(props.getProperty("download.thread.pool.size")));
            }
            if (props.containsKey("concurrent.photo.downloads")) {
                concurrentPhotoDownloads(Integer.parseInt(props.getProperty("concurrent.photo.downloads")));
            }
            if (props.containsKey("concurrent.image.downloads")) {
                concurrentImageDownloads(Integer.parseInt(props.getProperty("concurrent.image.downloads")));
            }
            return this;
        }

        /**
         * 创建并校验不可变配置快照。
         *
         * @return 新配置
         */
        public PicaConfiguration build() {
            return new PicaConfiguration(this);
        }
    }

    /**
     * 用于 core 对调用者配置和默认配置执行相同的 DNS host 语法检查。
     *
     * @param raw 原始 host 字符串
     * @return 规范化后的 host
     * @throws IllegalArgumentException host 语法不合法时抛出
     */
    public static String normalizeDomain(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Domain cannot be blank");
        }
        if (!raw.equals(raw.trim())) {
            throw new IllegalArgumentException("Domain cannot contain surrounding whitespace");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) > 0x7f || Character.isWhitespace(raw.charAt(i))) {
                throw new IllegalArgumentException("Domain must be ASCII without whitespace");
            }
        }
        String domain = raw.toLowerCase(Locale.ROOT);
        if (domain.length() > 253 || domain.startsWith(".") || domain.endsWith(".")) {
            throw new IllegalArgumentException("Invalid DNS domain");
        }
        if (isIpv4Literal(domain) || domain.contains(":")) {
            throw new IllegalArgumentException("IP literal is not an allowed DNS domain");
        }

        String[] labels = domain.split("\\.", -1);
        if (labels.length == 0) {
            throw new IllegalArgumentException("Invalid DNS domain");
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalArgumentException("Invalid DNS label");
            }
            for (int i = 0; i < label.length(); i++) {
                char ch = label.charAt(i);
                if (!(ch >= 'a' && ch <= 'z') && !(ch >= '0' && ch <= '9') && ch != '-') {
                    throw new IllegalArgumentException("Invalid DNS label");
                }
            }
        }
        return domain;
    }

    private static List<String> normalizeDomains(List<String> rawDomains) {
        Objects.requireNonNull(rawDomains, "Domains cannot be null");
        Set<String> unique = new LinkedHashSet<>();
        for (String domain : rawDomains) {
            if (!unique.add(normalizeDomain(domain))) {
                throw new IllegalArgumentException("Duplicate domain");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    private static Proxy createProxy(String host, int port) {
        if (host == null || host.isBlank() || !host.equals(host.trim())) {
            throw new IllegalArgumentException("Proxy host cannot be blank");
        }
        for (int i = 0; i < host.length(); i++) {
            if (Character.isISOControl(host.charAt(i)) || Character.isWhitespace(host.charAt(i))) {
                throw new IllegalArgumentException("Proxy host cannot contain whitespace or control characters");
            }
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Proxy port must be between 1 and 65535");
        }
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    }

    private static boolean isIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            try {
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private static Duration validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "Timeout cannot be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return timeout;
    }

    private static int validateNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static long validateNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static int validatePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long validatePositiveLong(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

}
