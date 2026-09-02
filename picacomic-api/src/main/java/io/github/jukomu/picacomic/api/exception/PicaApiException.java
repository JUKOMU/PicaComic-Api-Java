package io.github.jukomu.picacomic.api.exception;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次 Pica API 操作返回的结构化失败。
 *
 * <p>异常只公开稳定的传输元数据，不保存也不将响应体、请求 URL、headers 或凭据
 * 插入异常消息。</p>
 */
public class PicaApiException extends PicaComicException {

    /**
     * API 操作可供调用方判断的稳定失败原因。
     */
    public enum Reason {
        /** HTTP 响应状态表示请求失败。 */
        HTTP_STATUS,
        /** 服务端业务 envelope 报告失败。 */
        PROVIDER,
        /** 网络传输失败。 */
        NETWORK,
        /** 响应内容无法解析。 */
        PARSE,
        /** 请求被调用方取消。 */
        CANCELLED,
        /** 请求因传输超时结束。 */
        TIMEOUT,
        /** 所属 client 已关闭。 */
        CLIENT_CLOSED,
        /** 操作需要已登录会话。 */
        SESSION_REQUIRED,
        /** 请求的资源不存在。 */
        NOT_FOUND,
        /** 资源定位信息已过期。 */
        STALE_RESOURCE,
        /** 未归类的 client 内部失败。 */
        INTERNAL
    }

    private final Reason reason;
    private final Integer httpStatus;
    private final String providerCode;
    private final Duration retryAfter;

    /**
     * 使用失败原因创建异常。
     *
     * @param reason 稳定失败原因
     */
    public PicaApiException(Reason reason) {
        this(reason, null, null, null, null);
    }

    /**
     * 使用失败原因和脱敏 cause 创建异常。
     *
     * @param reason 稳定失败原因
     * @param cause 原始失败；对外只保留安全的 cause 描述
     */
    public PicaApiException(Reason reason, Throwable cause) {
        this(reason, null, null, null, cause);
    }

    /**
     * 使用 HTTP 状态创建异常。
     *
     * @param reason 稳定失败原因
     * @param httpStatus 最终 HTTP 状态码
     */
    public PicaApiException(Reason reason, Integer httpStatus) {
        this(reason, httpStatus, null, null, null);
    }

    /**
     * 使用结构化响应元数据创建异常。
     *
     * @param reason 稳定失败原因
     * @param httpStatus 最终 HTTP 状态码
     * @param providerCode 服务端错误代码
     * @param retryAfter 服务端提供的有效重试等待时间
     */
    public PicaApiException(Reason reason,
                            Integer httpStatus,
                            String providerCode,
                            Duration retryAfter) {
        this(reason, httpStatus, providerCode, retryAfter, null);
    }

    /**
     * 使用结构化响应元数据和脱敏 cause 创建异常。
     *
     * @param reason 稳定失败原因
     * @param httpStatus 最终 HTTP 状态码
     * @param providerCode 服务端错误代码
     * @param retryAfter 服务端提供的有效重试等待时间
     * @param cause 原始失败；不会把敏感响应内容暴露给调用方
     */
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

    /**
     * 获取稳定失败原因。
     *
     * @return 失败原因
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * 获取最终 HTTP 响应状态码；没有响应时返回 {@code null}。
     *
     * @return HTTP 状态码
     */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /**
     * 获取已知错误 envelope 中的标量服务端错误代码（如果存在）。
     *
     * @return 服务端错误代码
     */
    public String getProviderCode() {
        return providerCode;
    }

    /**
     * 获取最终响应中的有效 Retry-After 值（如果存在）。
     *
     * @return 重试等待时间
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /**
     * 在不保留响应体或 URL 的前提下重新标记失败原因。
     *
     * @param replacement 新的失败原因
     * @return 使用新原因的异常；如果原因未变化则返回当前实例
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
