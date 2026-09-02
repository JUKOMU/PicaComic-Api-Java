package io.github.jukomu.picacomic.api.exception;

/**
 * @author JUKOMU
 * @Description: 封装所有与网络通信相关的底层问题，例如多次重试后仍然失败
 * 通常包装了一个底层的 IOException
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public class NetworkException extends PicaApiException {

    /**
     * 创建没有底层 cause 的网络异常。
     *
     * @param message 兼容参数；异常对外使用安全的标准消息
     */
    public NetworkException(String message) {
        super(Reason.NETWORK);
    }

    /**
     * 使用底层 cause 创建网络异常。
     *
     * @param message 兼容参数；异常对外使用安全的标准消息
     * @param cause 底层网络失败
     */
    public NetworkException(String message, Throwable cause) {
        super(Reason.NETWORK, cause);
    }

    /**
     * 使用指定稳定原因和底层 cause 创建网络异常。
     *
     * @param reason 稳定失败原因
     * @param cause 底层网络失败
     */
    public NetworkException(Reason reason, Throwable cause) {
        super(reason, cause);
    }
}
