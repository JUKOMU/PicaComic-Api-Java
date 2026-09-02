package io.github.jukomu.picacomic.api.exception;

/**
 * @author JUKOMU
 * @Description: 当解析服务器响应失败时抛出
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public class ParseResponseException extends PicaApiException {

    public ParseResponseException(String message) {
        super(Reason.PARSE);
    }

    public ParseResponseException(String message, Throwable cause) {
        super(Reason.PARSE, cause);
    }

    public static ParseResponseException withHttpStatus(Integer httpStatus, Throwable cause) {
        return new ParseResponseException(httpStatus, cause, true);
    }

    private ParseResponseException(Integer httpStatus, Throwable cause, boolean statusAware) {
        super(Reason.PARSE, httpStatus, null, null, cause);
    }
}
