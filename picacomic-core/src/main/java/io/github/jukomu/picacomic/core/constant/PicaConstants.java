package io.github.jukomu.picacomic.core.constant;

import java.util.Collections;
import java.util.List;

/**
 * @author JUKOMU
 * @Description: 内部常量类
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class PicaConstants {

    private PicaConstants() {
        // 防止实例化
    }

    // == 分页 ==
    public static final int PAGE_SIZE_CONTENT_PAGE = 20;
    public static final int PAGE_SIZE_PHOTO_IMAGE = 40;

    // == 网络与协议 ==
    public static final String PROTOCOL_HTTPS = "https://";
    public static final String PLACEHOLDER_HOST = "pica-placeholder.domain.com";

    // == 请求头参数 ==
    public static final String APP_VERSION = "20251017";
    public static final String APP_UUID = "webUUIDv2";
    public static final String APP_PLATFORM = "android";
    public static final String APP_CHANNEL = "1";
    public static final String ACCEPT_TYPE = "application/vnd.picacomic.com.v1+json";

    // == 加解密和鉴权 ==
    public static final String SHUFFLE_SEED_KEY = "PicaWeb2025";
    public static final int XOR_KEY = 42;
    public static final String HMAC_ALGORITHM = "HmacSHA256";
    public static final String ENCRYPTED_API_KEY = "b397e2wXZHtgb2RvUBh7bnB+bnt8bEEfZ2xSQUFtY0F4G3h4bWhzeA==";
    public static final String ENCRYPTED_HMAC_KEY = "aGh+G0dwfHpGUGRmYGxrGUFsZmRyGUMZa19kfUxfRxMfXGAaGxNBbmBhZRpMQUFma20Bbn58YElIYGQTbGdsQkxrfEd8X3xueBocH1JQf2RpSG9B";

    // == 域名 ==
    public static List<String> DEFAULT_DOMAINS = Collections.unmodifiableList(List.of(
            "picaapi.go2778.com",
            "picaapi.acbbb.com"
    ));
}
