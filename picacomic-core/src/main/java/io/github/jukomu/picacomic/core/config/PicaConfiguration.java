package io.github.jukomu.picacomic.core.config;

import io.github.jukomu.picacomic.api.enums.ImageQuality;
import io.github.jukomu.picacomic.core.cache.CacheKey;
import io.github.jukomu.picacomic.core.cache.CachePool;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * @author JUKOMU
 * @Description: PicaClient 的不可变配置对象
 * 使用 {@link Builder} 模式进行构建
 * 此对象封装了所有用于创建和定制 PicaClient 实例所需的信息
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class PicaConfiguration {
    // 存储的域名
    private final List<String> domains;
    // 代理设置
    private final Proxy proxy;
    // 请求头
    private final Map<String, String> headers;
    // 超时时间
    private final Duration timeout;
    // 重试次数
    private final int retryTimes;
    // 代理回退阈值
    private final int proxyFallbackThreshold;
    // 请求的线程池
    private final ExecutorService executor;
    // 线程池大小
    private final int downloadThreadPoolSize;
    // 缓存大小, 单位: Byte
    private final CachePool<CacheKey, Object> cachePool;
    // 同时下载的章节数
    private final int concurrentPhotoDownloads;
    // 同时下载的图片数
    private final int concurrentImageDownloads;
    private final ImageQuality imageQuality;

    private PicaConfiguration(Builder builder) {
        this.domains = Collections.unmodifiableList(builder.domains);
        this.proxy = builder.proxy;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.timeout = builder.timeout;
        this.retryTimes = builder.retryTimes;
        this.proxyFallbackThreshold = builder.proxyFallbackThreshold;
        this.executor = builder.executor;
        this.downloadThreadPoolSize = builder.downloadThreadPoolSize;
        this.cachePool = new CachePool<>(builder.cacheSize);
        this.concurrentPhotoDownloads = builder.concurrentPhotoDownloads;
        this.concurrentImageDownloads = builder.concurrentImageDownloads;
        this.imageQuality = builder.imageQuality;
    }


    public List<String> getDomains() {
        return domains;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getRetryTimes() {
        return retryTimes;
    }

    public int getProxyFallbackThreshold() {
        return proxyFallbackThreshold;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public int getDownloadThreadPoolSize() {
        return downloadThreadPoolSize;
    }

    public CachePool<CacheKey, Object> getCachePool() {
        return cachePool;
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
     * 用于创建 PicaConfiguration 实例的 Builder
     */
    public static class Builder {
        private List<String> domains = new java.util.ArrayList<>();
        private Proxy proxy;
        private final Map<String, String> headers = new HashMap<>();
        private Duration timeout = Duration.ofSeconds(30);
        private int retryTimes = 5;
        private int proxyFallbackThreshold = 2;
        private ExecutorService executor = null;
        private int downloadThreadPoolSize = 12; // -1 表示使用默认值 (CPU核心数)
        private int cacheSize = 100 * 1024 * 1024;
        private int concurrentPhotoDownloads = 3;
        private int concurrentImageDownloads = 20;
        private ImageQuality imageQuality = ImageQuality.MEDIUM;


        public Builder domains(List<String> domains) {
            this.domains = new java.util.ArrayList<>(Objects.requireNonNull(domains));
            return this;
        }

        public Builder proxy(String host, int port) {
            this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
            return this;
        }

        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout);
            return this;
        }

        public Builder retryTimes(int retryTimes) {
            if (retryTimes < 0) throw new IllegalArgumentException("Retry times must be non-negative.");
            this.retryTimes = retryTimes;
            return this;
        }

        public Builder proxyFallbackThreshold(int proxyFallbackThreshold) {
            if (proxyFallbackThreshold < 0)
                throw new IllegalArgumentException("Proxy fallback threshold must be non-negative.");
            this.proxyFallbackThreshold = proxyFallbackThreshold;
            return this;
        }

        public Builder downloadThreadPoolSize(int size) {
            if (size <= 0) throw new IllegalArgumentException("Thread pool size must be positive.");
            this.downloadThreadPoolSize = size;
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder cacheSize(int size) {
            if (size < 0) throw new IllegalArgumentException("Cache size must be non-negative.");
            this.cacheSize = size;
            return this;
        }

        public Builder concurrentPhotoDownloads(int size) {
            if (size < 0) throw new IllegalArgumentException("Concurrent photo uploads must be non-negative.");
            this.concurrentPhotoDownloads = size;
            return this;
        }

        public Builder concurrentImageDownloads(int size) {
            if (size < 0) throw new IllegalArgumentException("Concurrent image uploads must be non-negative.");
            this.concurrentImageDownloads = size;
            return this;
        }

        public Builder imageQuality(ImageQuality imageQuality) {
            this.imageQuality = imageQuality;
            return this;
        }

        public Builder loadFromProperties(InputStream inputStream) throws IOException {
            Properties props = new Properties();
            props.load(inputStream);

            if (props.containsKey("proxy.host") && props.containsKey("proxy.port")) {
                this.proxy(props.getProperty("proxy.host"), Integer.parseInt(props.getProperty("proxy.port")));
            }
            if (props.containsKey("retry.times")) {
                this.retryTimes(Integer.parseInt(props.getProperty("retry.times")));
            }
            // 可以根据需要添加更多从 properties 加载的配置项

            return this;
        }

        public PicaConfiguration build() {
            if (this.executor != null) {
                this.downloadThreadPoolSize = -1;
            }
            return new PicaConfiguration(this);
        }
    }
}
