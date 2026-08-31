package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.Dns;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 测试源码中的 package bridge；生产代码不公开 DNS/TLS 注入构造器。
 */
final class LocalTlsClientContextFactory {

    private LocalTlsClientContextFactory() {
    }

    static OkHttpBuilder.HttpClientContext build(PicaConfiguration config,
                                                         Dns dns,
                                                         SSLSocketFactory sslSocketFactory,
                                                         X509TrustManager trustManager,
                                                         SocketFactory socketFactory) {
        return OkHttpBuilder.buildForTesting(config, dns, sslSocketFactory, trustManager, socketFactory);
    }
}
