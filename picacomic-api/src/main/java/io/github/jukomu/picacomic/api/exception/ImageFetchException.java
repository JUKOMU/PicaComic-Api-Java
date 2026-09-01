package io.github.jukomu.picacomic.api.exception;

/**
 * 图片请求在安全边界、响应内容或生命周期上失败时抛出的稳定异常。
 */
public final class ImageFetchException extends PicaComicException {

    /**
     * 图片失败的机器可判定原因。
     */
    public enum Reason {
        INVALID_SOURCE,
        DISALLOWED_HOST,
        REDIRECT_REJECTED,
        HTTP_STATUS,
        UNSUPPORTED_MEDIA_TYPE,
        UNSUPPORTED_CONTENT_ENCODING,
        INVALID_CONTENT,
        TRUNCATED_BODY,
        TIMEOUT,
        CANCELLED,
        NETWORK,
        CLIENT_CLOSED
    }

    private final Reason reason;
    private final Integer httpStatus;

    public ImageFetchException(Reason reason) {
        this(reason, null, null);
    }

    public ImageFetchException(Reason reason, Throwable cause) {
        this(reason, null, cause);
    }

    public ImageFetchException(Reason reason, Integer httpStatus) {
        this(reason, httpStatus, null);
    }

    public ImageFetchException(Reason reason, Integer httpStatus, Throwable cause) {
        super(message(reason, httpStatus), cause);
        if (reason == null) {
            throw new IllegalArgumentException("Image failure reason cannot be null");
        }
        if (httpStatus != null && reason != Reason.HTTP_STATUS) {
            throw new IllegalArgumentException("HTTP status is only valid for HTTP_STATUS failures");
        }
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public Reason getReason() {
        return reason;
    }

    /**
     * 获取 HTTP 状态码。只有 {@link Reason#HTTP_STATUS} 才会返回非 null 值。
     */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    private static String message(Reason reason, Integer httpStatus) {
        if (reason == null) {
            return "Image request failed";
        }
        return httpStatus == null
                ? "Image request failed: " + reason
                : "Image request failed: " + reason + " (HTTP " + httpStatus + ")";
    }
}
