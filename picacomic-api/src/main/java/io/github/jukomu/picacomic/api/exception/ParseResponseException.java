package io.github.jukomu.picacomic.api.exception;

/**
 * @author JUKOMU
 * @Description: 当解析服务器响应失败时抛出
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public class ParseResponseException extends PicaApiException {

    /**
     * 创建没有底层 cause 的响应解析异常。
     *
     * @param message 兼容参数；异常对外使用安全的标准消息
     */
    public ParseResponseException(String message) {
        super(Reason.PARSE);
    }

    /**
     * 使用底层 cause 创建响应解析异常。
     *
     * @param message 兼容参数；异常对外使用安全的标准消息
     * @param cause 底层解析失败
     */
    public ParseResponseException(String message, Throwable cause) {
        super(Reason.PARSE, cause);
    }

    /**
     * 创建同时保留最终 HTTP 状态码的响应解析异常。
     *
     * @param httpStatus 最终 HTTP 状态码
     * @param cause 底层解析失败
     * @return 结构化响应解析异常
     */
    public static ParseResponseException withHttpStatus(Integer httpStatus, Throwable cause) {
        return new ParseResponseException(httpStatus, cause, true);
    }

    private ParseResponseException(Integer httpStatus, Throwable cause, boolean statusAware) {
        super(Reason.PARSE, httpStatus, null, null, cause);
    }
}
