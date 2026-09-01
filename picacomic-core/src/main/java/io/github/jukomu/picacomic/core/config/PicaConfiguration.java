package io.github.jukomu.picacomic.core.config;

import io.github.jukomu.picacomic.api.enums.ImageQuality;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 创建 client 所需的不可变 value snapshot。
 *
 * <p>运行时状态（Cookie、缓存、域名健康度和关闭状态）不属于配置，
 * 因而同一个配置可以安全地创建多个相互隔离的 client。</p>
 */
public final class PicaConfiguration {

    private final List<String> domains;
    private final Proxy proxy;
    private final Duration timeout;
    private final int retryTimes;
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
        this.executor = builder.executor;
        this.downloadThreadPoolSize = validatePositive(builder.downloadThreadPoolSize, "Download thread pool size");
        this.cacheSize = validateNonNegative(builder.cacheSize, "Cache size");
        this.concurrentPhotoDownloads = validatePositive(builder.concurrentPhotoDownloads, "Concurrent photo downloads");
        this.concurrentImageDownloads = validatePositive(builder.concurrentImageDownloads, "Concurrent image downloads");
        this.imageQuality = Objects.requireNonNull(builder.imageQuality, "Image quality cannot be null");
    }

    /**
     * 获取调用者显式提供的 API host 快照。空列表表示使用 core 的默认 API hosts。
     */
    public List<String> getDomains() {
        return domains;
    }

    /**
     * 获取调用者显式配置的传输代理。库不会在失败时自行切换代理。
     */
    public Proxy getProxy() {
        return proxy;
    }

    public Duration getTimeout() {
        return timeout;
    }

    /**
     * 首次请求之后允许的额外 GET/HEAD 尝试次数。
     */
    public int getRetryTimes() {
        return retryTimes;
    }

    /**
     * 外部下载 executor。该 executor 的生命周期始终由调用者负责。
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    public int getDownloadThreadPoolSize() {
        return downloadThreadPoolSize;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public int getConcurrentPhotoDownloads() {
        return concurrentPhotoDownloads;
    }

    public int getConcurrentImageDownloads() {
        return concurrentImageDownloads;
    }

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
        private ExecutorService executor;
        private int downloadThreadPoolSize = 12;
        private int cacheSize = 100 * 1024 * 1024;
        private int concurrentPhotoDownloads = 3;
        private int concurrentImageDownloads = 20;
        private ImageQuality imageQuality = ImageQuality.MEDIUM;

        public Builder domains(List<String> domains) {
            this.domains = new ArrayList<>(Objects.requireNonNull(domains, "Domains cannot be null"));
            return this;
        }

        public Builder proxy(String host, int port) {
            this.proxy = createProxy(host, port);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = validateTimeout(timeout);
            return this;
        }

        public Builder retryTimes(int retryTimes) {
            this.retryTimes = validateNonNegative(retryTimes, "Retry times");
            return this;
        }

        public Builder downloadThreadPoolSize(int size) {
            this.downloadThreadPoolSize = validatePositive(size, "Download thread pool size");
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder cacheSize(int size) {
            this.cacheSize = validateNonNegative(size, "Cache size");
            return this;
        }

        public Builder concurrentPhotoDownloads(int size) {
            this.concurrentPhotoDownloads = validatePositive(size, "Concurrent photo downloads");
            return this;
        }

        public Builder concurrentImageDownloads(int size) {
            this.concurrentImageDownloads = validatePositive(size, "Concurrent image downloads");
            return this;
        }

        public Builder imageQuality(ImageQuality imageQuality) {
            this.imageQuality = Objects.requireNonNull(imageQuality, "Image quality cannot be null");
            return this;
        }

        /**
         * 从 properties 读取仍属于 U1 的基础网络配置。
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

        public PicaConfiguration build() {
            // external executor 的 pool-size value 仍是 snapshot 的普通配置，不改写为哨兵值。
            return new PicaConfiguration(this);
        }
    }

    /**
     * 用于 core 对调用者配置和默认配置执行相同的 DNS host 语法检查。
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

    private static int validatePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

}
