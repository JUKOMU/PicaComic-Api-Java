package io.github.jukomu.picacomic.api.exception;

/**
 * @author JUKOMU
 * @Description: 当解析服务器响应失败时抛出
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public class ParseResponseException extends PicaComicException {

    public ParseResponseException(String message) {
        super(message);
    }

    public ParseResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
