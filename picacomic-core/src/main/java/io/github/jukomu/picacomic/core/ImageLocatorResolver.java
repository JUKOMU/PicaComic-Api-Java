package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.model.PicaImage;
import okhttp3.HttpUrl;

import java.util.Locale;

/**
 * 把图片模型中的 locator 归一化为经过同一策略校验的 HTTPS URL。
 */
final class ImageLocatorResolver {

    private static final String STATIC_SEGMENT = "static";
    private final ImageHostPolicy policy;

    ImageLocatorResolver(ImageHostPolicy policy) {
        this.policy = policy;
    }

    ImageHostPolicy getPolicy() {
        return policy;
    }

    /**
     * 解析图片的直接 URL 或 fileServer/path 组合。
     */
    HttpUrl resolve(PicaImage image) {
        if (image == null) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        String directUrl = image.imageUrl();
        if (directUrl != null && !directUrl.isBlank()) {
            return policy.validateDirect(directUrl);
        }

        String fileServer = image.getFileServer();
        String path = image.getPath();
        if (fileServer == null || fileServer.isBlank() || path == null || path.isBlank()) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        if (ImageHostPolicy.containsWhitespaceOrControl(fileServer)
                || ImageHostPolicy.containsWhitespaceOrControl(path)
                || ImageHostPolicy.containsUnsafePathSyntax(fileServer)
                || !isSafeRelativePath(path)) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        if (ImageHostPolicy.containsUserInfo(fileServer)) {
            throw new ImageFetchException(ImageFetchException.Reason.DISALLOWED_HOST);
        }

        HttpUrl origin;
        try {
            origin = HttpUrl.parse(fileServer);
        } catch (RuntimeException exception) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE, exception);
        }
        if (origin == null) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        if (!"https".equalsIgnoreCase(origin.scheme())
                || origin.port() != 443
                || !origin.username().isEmpty()
                || !origin.password().isEmpty()) {
            throw new ImageFetchException(ImageFetchException.Reason.DISALLOWED_HOST);
        }
        if (origin.query() != null
                || origin.fragment() != null
                || !("".equals(origin.encodedPath()) || "/".equals(origin.encodedPath()))) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        if (!policy.isAllowedHost(origin.host())) {
            throw new ImageFetchException(ImageFetchException.Reason.DISALLOWED_HOST);
        }

        HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme("https")
                .host(origin.host())
                .port(443)
                .addPathSegment(STATIC_SEGMENT);
        for (String segment : path.split("/", -1)) {
            builder.addPathSegment(segment);
        }
        return policy.validateDirect(builder.build().toString());
    }

    /**
     * 基于当前 URL 解析并校验 Location；策略失败都映射到 REDIRECT_REJECTED。
     */
    HttpUrl resolveRedirect(HttpUrl current, String location) {
        if (current == null || location == null || location.isBlank()
                || ImageHostPolicy.containsWhitespaceOrControl(location)
                || ImageHostPolicy.containsUnsafePathSyntax(location)) {
            throw new ImageFetchException(ImageFetchException.Reason.REDIRECT_REJECTED);
        }
        try {
            HttpUrl target = current.resolve(location);
            return policy.validateRedirect(target, location);
        } catch (ImageFetchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ImageFetchException(ImageFetchException.Reason.REDIRECT_REJECTED, exception);
        }
    }

    private static boolean isSafeRelativePath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\")
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.toLowerCase(Locale.ROOT).contains("%2f")
                || path.toLowerCase(Locale.ROOT).contains("%5c")) {
            return false;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ImageHostPolicy.isDotSegment(segment)
                    || segment.indexOf('\\') >= 0) {
                return false;
            }
        }
        return true;
    }
}
