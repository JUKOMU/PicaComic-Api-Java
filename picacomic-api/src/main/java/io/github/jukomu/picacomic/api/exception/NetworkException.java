package io.github.jukomu.picacomic.api.exception;

/**
 * @author JUKOMU
 * @Description: 封装所有与网络通信相关的底层问题，例如多次重试后仍然失败
 * 通常包装了一个底层的 IOException
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public class NetworkException extends PicaComicException {

    public NetworkException(String message) {
        super(message);
    }

    public NetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
