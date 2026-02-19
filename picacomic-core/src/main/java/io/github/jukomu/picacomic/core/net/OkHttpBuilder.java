package io.github.jukomu.picacomic.core.net;

import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.constant.PicaConstants;
import io.github.jukomu.picacomic.core.net.interceptor.RetryAndDomainRedirectInterceptor;
import io.github.jukomu.picacomic.core.net.provider.PicaDomainManager;
import okhttp3.CookieJar;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;

import java.net.CookieManager;
import java.net.CookiePolicy;

/**
 * @author JUKOMU
 * @Description: 内部工厂类，负责根据 PicaConfiguration 构建和组装一个完整的 OkHttpClient 实例
 * 它封装了所有关于拦截器、Cookie管理、代理、超时等配置的细节
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class OkHttpBuilder {

    private OkHttpBuilder() {
    }

    /**
     * 根据给定的配置创建一个新的 OkHttpClient 实例
     *
     * @param config 用户的配置对象
     * @return 一个配置好的 OkHttpClient 实例
     */
    public static HttpClientContext build(PicaConfiguration config) {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieJar cookieJar = new JavaNetCookieJar(cookieManager);

        PicaDomainManager domainManager = new PicaDomainManager(PicaConstants.DEFAULT_DOMAINS);

        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        // 配置 proxy, timeout, cookieJar
        builder.proxy(config.getProxy());
        builder.connectTimeout(config.getTimeout());
        builder.readTimeout(config.getTimeout());
        builder.writeTimeout(config.getTimeout());
        builder.cookieJar(cookieJar);

        builder.addInterceptor(new RetryAndDomainRedirectInterceptor(config.getRetryTimes(), domainManager));
        builder.retryOnConnectionFailure(false);
        OkHttpClient client = builder.build();

        return new HttpClientContext(client, domainManager, cookieManager);
    }

    /**
     * 内部数据类，用于捆绑 OkHttpClient 及其关联的有状态组件
     */
    public static class HttpClientContext {
        private final OkHttpClient client;
        private final PicaDomainManager domainManager;
        private final CookieManager cookieManager;

        HttpClientContext(OkHttpClient client, PicaDomainManager domainManager, CookieManager cookieManager) {
            this.client = client;
            this.domainManager = domainManager;
            this.cookieManager = cookieManager;
        }

        public OkHttpClient getClient() {
            return client;
        }

        public PicaDomainManager getDomainManager() {
            return domainManager;
        }

        public CookieManager getCookieManager() {
            return cookieManager;
        }
    }
}
