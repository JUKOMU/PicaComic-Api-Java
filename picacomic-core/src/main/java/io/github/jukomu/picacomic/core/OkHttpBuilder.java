package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.constant.PicaConstants;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.Dispatcher;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import javax.net.SocketFactory;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.List;

/**
 * 只负责组装 client-owned 网络资源。
 */
final class OkHttpBuilder {

    private OkHttpBuilder() {
    }

    /**
     * 生产环境构建一组完全隔离的 API/image OkHttp client。
     */
    static HttpClientContext build(PicaConfiguration config) {
        return build(config, null, null, null, null);
    }

    /**
     * 为本地 fixture 提供 DNS/TLS 注入点。该入口不被生产 factory 使用。
     */
    static HttpClientContext buildForTesting(PicaConfiguration config,
                                             Dns dns,
                                             SSLSocketFactory sslSocketFactory,
                                             X509TrustManager trustManager) {
        return build(config, dns, sslSocketFactory, trustManager, null);
    }

    /**
     * 额外允许本地 fixture 把逻辑 443 连接映射到非特权测试端口；生产 factory 不使用。
     */
    static HttpClientContext buildForTesting(PicaConfiguration config,
                                             Dns dns,
                                             SSLSocketFactory sslSocketFactory,
                                             X509TrustManager trustManager,
                                             SocketFactory socketFactory) {
        return build(config, dns, sslSocketFactory, trustManager, socketFactory);
    }

    private static HttpClientContext build(PicaConfiguration config,
                                           Dns dns,
                                           SSLSocketFactory sslSocketFactory,
                                           X509TrustManager trustManager,
                                           SocketFactory socketFactory) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        if ((sslSocketFactory == null) != (trustManager == null)) {
            throw new IllegalArgumentException("TLS socket factory and trust manager must be provided together");
        }

        List<String> apiDomains = config.getDomains().isEmpty()
                ? PicaConstants.DEFAULT_DOMAINS
                : config.getDomains();
        PicaDomainManager domainManager = new PicaDomainManager(apiDomains);

        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieJar apiCookieJar = new JavaNetCookieJar(cookieManager);

        OkHttpClient.Builder apiBuilder = baseBuilder(config, dns, sslSocketFactory, trustManager, socketFactory)
                .dispatcher(new Dispatcher())
                .connectionPool(new ConnectionPool())
                .cookieJar(apiCookieJar)
                .addInterceptor(new RetryAndDomainRedirectInterceptor(config.getRetryTimes(), domainManager))
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false);
        OkHttpClient apiClient = apiBuilder.build();

        OkHttpClient.Builder imageBuilder = baseBuilder(config, dns, sslSocketFactory, trustManager, socketFactory)
                .dispatcher(new Dispatcher())
                .connectionPool(new ConnectionPool())
                .cookieJar(CookieJar.NO_COOKIES)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false);
        OkHttpClient imageClient = imageBuilder.build();

        return new HttpClientContext(apiClient, imageClient, domainManager, cookieManager);
    }

    private static OkHttpClient.Builder baseBuilder(PicaConfiguration config,
                                                    Dns dns,
                                                    SSLSocketFactory sslSocketFactory,
                                                    X509TrustManager trustManager,
                                                    SocketFactory socketFactory) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout())
                .readTimeout(config.getTimeout())
                .writeTimeout(config.getTimeout())
                .callTimeout(config.getTimeout());
        if (config.getProxy() != null) {
            builder.proxy(config.getProxy());
        }
        if (dns != null) {
            builder.dns(dns);
        }
        if (sslSocketFactory != null) {
            builder.sslSocketFactory(sslSocketFactory, trustManager);
        }
        if (socketFactory != null) {
            builder.socketFactory(socketFactory);
        }
        return builder;
    }

    /**
     * 只在 core 内部传递的资源组。生产 factory 不把它返回给库调用者。
     */
    static final class HttpClientContext {
        private final OkHttpClient apiClient;
        private final OkHttpClient imageClient;
        private final PicaDomainManager domainManager;
        private final CookieManager cookieManager;

        HttpClientContext(OkHttpClient apiClient,
                          OkHttpClient imageClient,
                          PicaDomainManager domainManager,
                          CookieManager cookieManager) {
            this.apiClient = apiClient;
            this.imageClient = imageClient;
            this.domainManager = domainManager;
            this.cookieManager = cookieManager;
        }

        OkHttpClient getApiClient() {
            return apiClient;
        }

        OkHttpClient getImageClient() {
            return imageClient;
        }

        PicaDomainManager getDomainManager() {
            return domainManager;
        }

        CookieManager getCookieManager() {
            return cookieManager;
        }
    }
}
