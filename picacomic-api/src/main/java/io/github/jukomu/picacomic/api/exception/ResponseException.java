package io.github.jukomu.picacomic.api.exception;

/**
 * @author JUKOMU
 * @Description: 当PicaComic服务器返回业务逻辑错误时抛出
 * 例如：登录失败、权限不足、搜索关键词过短等
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public class ResponseException extends PicaApiException {

    private final int errorCode; // 可选的错误码

    /**
     * 使用服务端失败原因创建业务响应异常。
     *
     * @param message 兼容参数；异常对外使用安全的标准消息
     */
    public ResponseException(String message) {
        super(Reason.PROVIDER);
        this.errorCode = -1; // -1 表示没有特定的错误码
    }

    /**
     * 使用服务端失败原因和底层 cause 创建业务响应异常。
     *
     * @param message 兼容参数；异常对外使用安全的标准消息
     * @param cause 底层失败原因
     */
    public ResponseException(String message, Throwable cause) {
        super(Reason.PROVIDER, cause);
        this.errorCode = -1; // -1 表示没有特定的错误码
    }

    /**
     * 使用业务错误码创建业务响应异常。
     *
     * @param message 兼容参数；异常对外使用安全的标准消息
     * @param errorCode 服务端业务错误码
     */
    public ResponseException(String message, int errorCode) {
        super(Reason.PROVIDER, null, errorCode < 0 ? null : String.valueOf(errorCode), null);
        this.errorCode = errorCode;
    }

    /**
     * 使用结构化响应元数据创建业务响应异常。
     *
     * @param reason 稳定失败原因
     * @param httpStatus HTTP 状态码
     * @param providerCode 服务端错误代码
     * @param retryAfter 有效的重试等待时间
     */
    public ResponseException(Reason reason,
                             Integer httpStatus,
                             String providerCode,
                            java.time.Duration retryAfter) {
        super(reason, httpStatus, providerCode, retryAfter);
        this.errorCode = providerCode == null ? -1 : parseErrorCode(providerCode);
    }

    /**
     * 使用结构化响应元数据和底层 cause 创建业务响应异常。
     *
     * @param reason 稳定失败原因
     * @param httpStatus HTTP 状态码
     * @param providerCode 服务端错误代码
     * @param retryAfter 有效的重试等待时间
     * @param cause 底层失败原因
     */
    public ResponseException(Reason reason,
                             Integer httpStatus,
                             String providerCode,
                             java.time.Duration retryAfter,
                             Throwable cause) {
        super(reason, httpStatus, providerCode, retryAfter, cause);
        this.errorCode = providerCode == null ? -1 : parseErrorCode(providerCode);
    }

    /**
     * 获取业务错误码（如果有）
     *
     * @return 错误码，如果没有则为 -1
     */
    public int getErrorCode() {
        return errorCode;
    }

    private static int parseErrorCode(String providerCode) {
        try {
            return Integer.parseInt(providerCode);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
