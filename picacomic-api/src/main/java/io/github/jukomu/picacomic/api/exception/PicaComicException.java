package io.github.jukomu.picacomic.api.exception;

/**
 * @author JUKOMU
 * @Description: 异常基类
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public class PicaComicException extends RuntimeException {

    public PicaComicException(String message) {
        super(message);
    }

    public PicaComicException(String message, Throwable cause) {
        super(message, cause);
    }
}
