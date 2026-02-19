package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.core.client.PicaClient;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.net.OkHttpBuilder;

/**
 * @author JUKOMU
 * @Description: 入口工厂类
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/18
 */
public class PicaComic {

    private PicaComic() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * 根据配置创建一个新的 PicaClient 实例
     *
     * @param config 客户端的配置对象
     * @return PicaClient
     */
    public static PicaClient newApiClient(PicaConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null.");
        }
        OkHttpBuilder.HttpClientContext context = OkHttpBuilder.build(config);
        return new PicaClient(config, context.getClient(), context.getDomainManager());
    }
}
