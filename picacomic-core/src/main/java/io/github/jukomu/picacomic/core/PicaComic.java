package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.core.client.DefaultPicaClient;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.net.OkHttpBuilder;

/**
 * PicaComic client 工厂。
 */
public final class PicaComic {

    private PicaComic() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * 创建一个拥有独立 API/image 网络资源和运行时状态的 client。
     *
     * @param config 不可变配置快照
     * @return PicaComic API client
     */
    public static IPicaClient newApiClient(PicaConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        return new DefaultPicaClient(config, OkHttpBuilder.build(config));
    }
}
