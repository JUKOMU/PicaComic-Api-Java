package io.github.jukomu.picacomic.core.net;

import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.model.PicaImage;
import okhttp3.HttpUrl;

/**
 * 将图片模型中的 locator 解析为 OkHttp URL。
 */
public final class ImageLocatorResolver {

    /**
     * 解析图片的直接 URL 或 fileServer/path 组合。
     */
    HttpUrl resolve(PicaImage image) {
        if (image == null) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        String source;
        try {
            source = image.getImageUrl();
        } catch (IllegalStateException exception) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE, exception);
        }
        if (source == null || source.isBlank()) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        try {
            HttpUrl url = HttpUrl.parse(source);
            if (url == null) {
                throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
            }
            return url;
        } catch (RuntimeException exception) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE, exception);
        }
    }
}
