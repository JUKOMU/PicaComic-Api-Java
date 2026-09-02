package io.github.jukomu.picacomic.api.exception;

import java.time.Duration;
import java.util.Objects;

/**
 * A structured failure from one Pica API operation.
 *
 * <p>The exception deliberately exposes only stable transport metadata. The
 * response body, request URL, headers and credentials are never retained or
 * interpolated into its message.</p>
 */
public class PicaApiException extends PicaComicException {

    /**
     * Stable machine-readable failure categories for API operations.
     */
    public enum Reason {
        HTTP_STATUS,
        PROVIDER,
        NETWORK,
        PARSE,
        CANCELLED,
        TIMEOUT,
        CLIENT_CLOSED,
        SESSION_REQUIRED,
        NOT_FOUND,
        STALE_RESOURCE,
        INTERNAL
    }

    private final Reason reason;
    private final Integer httpStatus;
    private final String providerCode;
    private final Duration retryAfter;

    public PicaApiException(Reason reason) {
        this(reason, null, null, null, null);
    }

    public PicaApiException(Reason reason, Throwable cause) {
        this(reason, null, null, null, cause);
    }

    public PicaApiException(Reason reason, Integer httpStatus) {
        this(reason, httpStatus, null, null, null);
    }

    public PicaApiException(Reason reason,
                            Integer httpStatus,
                            String providerCode,
                            Duration retryAfter) {
        this(reason, httpStatus, providerCode, retryAfter, null);
    }

    public PicaApiException(Reason reason,
                            Integer httpStatus,
                            String providerCode,
                            Duration retryAfter,
                            Throwable cause) {
        super(message(reason, httpStatus), safeCause(cause, reason));
        this.reason = Objects.requireNonNull(reason, "API failure reason cannot be null");
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 999)) {
            throw new IllegalArgumentException("HTTP status must be a valid three-digit status");
        }
        if (httpStatus != null && reason != Reason.HTTP_STATUS
                && reason != Reason.PROVIDER && reason != Reason.PARSE) {
            throw new IllegalArgumentException("HTTP status is not valid for this API failure reason");
        }
        if (providerCode != null && providerCode.isBlank()) {
            throw new IllegalArgumentException("Provider code cannot be blank");
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("Retry-After cannot be negative");
        }
        this.httpStatus = httpStatus;
        this.providerCode = providerCode;
        this.retryAfter = retryAfter;
    }

    public Reason getReason() {
        return reason;
    }

    /**
     * The final HTTP response status, or {@code null} when no response exists.
     */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /**
     * A scalar provider code from a known error envelope, when available.
     */
    public String getProviderCode() {
        return providerCode;
    }

    /**
     * The final response's valid Retry-After value, when available.
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /**
     * Reclassifies a failure without retaining a response body or URL.
     */
    public PicaApiException withReason(Reason replacement) {
        Objects.requireNonNull(replacement, "Replacement reason cannot be null");
        if (replacement == reason) {
            return this;
        }
        Integer status = replacement == Reason.HTTP_STATUS
                || replacement == Reason.PROVIDER || replacement == Reason.PARSE
                ? httpStatus : null;
        String code = replacement == Reason.HTTP_STATUS || replacement == Reason.PROVIDER
                ? providerCode : null;
        Duration retry = replacement == Reason.HTTP_STATUS ? retryAfter : null;
        return new PicaApiException(replacement, status, code, retry, getCause());
    }

    private static String message(Reason reason, Integer httpStatus) {
        if (reason == null) {
            return "Pica API request failed";
        }
        return httpStatus == null
                ? "Pica API request failed: " + reason
                : "Pica API request failed: " + reason + " (HTTP " + httpStatus + ")";
    }

    private static Throwable safeCause(Throwable cause, Reason reason) {
        if (cause == null) {
            return null;
        }
        if (cause instanceof SafeCause) {
            return cause;
        }
        return new SafeCause("Pica API request failed: " + reason);
    }

    private static final class SafeCause extends RuntimeException {
        private SafeCause(String message) {
            super(message);
        }
    }
}
