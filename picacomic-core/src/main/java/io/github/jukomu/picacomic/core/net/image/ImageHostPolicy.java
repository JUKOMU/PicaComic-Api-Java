package io.github.jukomu.picacomic.core.net.image;

import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.core.constant.PicaConstants;
import okhttp3.HttpUrl;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图片 URL 的固定、精确 host 与路径策略。
 */
public final class ImageHostPolicy {

    private static final String STATIC_PREFIX = "/static/";
    private final Set<String> allowedHosts;

    public ImageHostPolicy() {
        this(PicaConstants.IMAGE_HOST_ALLOWLIST);
    }

    ImageHostPolicy(Set<String> allowedHosts) {
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public boolean isAllowedHost(String host) {
        return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * 校验一个直接来源 URL。
     */
    public HttpUrl validateDirect(String source) {
        if (source == null || source.isBlank() || containsWhitespaceOrControl(source)) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        if (containsUserInfo(source)) {
            throw new ImageFetchException(ImageFetchException.Reason.DISALLOWED_HOST);
        }
        if (containsUnsafePathSyntax(source)) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        HttpUrl url;
        try {
            url = HttpUrl.parse(source);
        } catch (RuntimeException exception) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE, exception);
        }
        if (url == null) {
            throw new ImageFetchException(ImageFetchException.Reason.INVALID_SOURCE);
        }
        return validate(url, ImageFetchException.Reason.DISALLOWED_HOST,
                ImageFetchException.Reason.INVALID_SOURCE);
    }

    /**
     * 校验一次手动重定向的目标。任何策略失败都归入 redirect reason，且不会发出下一跳。
     */
    public HttpUrl validateRedirect(HttpUrl url, String rawLocation) {
        if (url == null || rawLocation == null || rawLocation.isBlank()
                || containsWhitespaceOrControl(rawLocation)
                || containsUnsafePathSyntax(rawLocation)
                || containsUserInfo(rawLocation)) {
            throw new ImageFetchException(ImageFetchException.Reason.REDIRECT_REJECTED);
        }
        return validate(url, ImageFetchException.Reason.REDIRECT_REJECTED,
                ImageFetchException.Reason.REDIRECT_REJECTED);
    }

    private HttpUrl validate(HttpUrl url,
                             ImageFetchException.Reason hostFailure,
                             ImageFetchException.Reason pathFailure) {
        if (!"https".equalsIgnoreCase(url.scheme())
                || url.port() != 443
                || !url.username().isEmpty()
                || !url.password().isEmpty()
                || !isAllowedHost(url.host())) {
            throw new ImageFetchException(hostFailure);
        }
        if (url.fragment() != null || !isStaticPath(url)) {
            throw new ImageFetchException(pathFailure);
        }
        return url;
    }

    private static boolean isStaticPath(HttpUrl url) {
        String encodedPath = url.encodedPath();
        if (!encodedPath.startsWith(STATIC_PREFIX)) {
            return false;
        }
        if (encodedPath.length() == STATIC_PREFIX.length()) {
            return false;
        }

        List<String> encodedSegments = url.encodedPathSegments();
        List<String> decodedSegments = url.pathSegments();
        if (encodedSegments.size() != decodedSegments.size() || decodedSegments.size() < 2) {
            return false;
        }
        for (int i = 1; i < decodedSegments.size(); i++) {
            String encoded = encodedSegments.get(i);
            String decoded = decodedSegments.get(i);
            if (encoded.isEmpty() || decoded.isEmpty()
                    || isDotSegment(encoded) || isDotSegment(decoded)
                    || decoded.indexOf('\\') >= 0
                    || containsControl(decoded)
                    || containsEncodedSlash(encoded)
                    || containsEncodedSlash(decoded)) {
                return false;
            }
        }
        return true;
    }

    static boolean containsWhitespaceOrControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsUnsafePathSyntax(String value) {
        String path = value;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || path.indexOf('\\') >= 0) {
            return true;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (isDotSegment(segment)) {
                return true;
            }
        }
        return value.indexOf('#') >= 0;
    }

    static boolean containsUserInfo(String value) {
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        int authorityStart = scheme + 3;
        int authorityEnd = value.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = value.indexOf(delimiter, authorityStart);
            if (index >= 0) {
                authorityEnd = Math.min(authorityEnd, index);
            }
        }
        return value.substring(authorityStart, authorityEnd).indexOf('@') >= 0;
    }

    private static boolean containsEncodedSlash(String segment) {
        String lower = segment.toLowerCase(Locale.ROOT);
        return lower.contains("%2f") || lower.contains("%5c");
    }

    static boolean isDotSegment(String segment) {
        String decoded = segment.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 3; i++) {
            if (".".equals(decoded) || "..".equals(decoded)) {
                return true;
            }
            decoded = decoded.replace("%25", "%").replace("%2e", ".");
        }
        return ".".equals(decoded) || "..".equals(decoded);
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
