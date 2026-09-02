package io.github.jukomu.picacomic.core.net.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import io.github.jukomu.picacomic.api.exception.ParseResponseException;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.exception.ResponseException;
import io.github.jukomu.picacomic.core.util.JsonUtils;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * @author JUKOMU
 * @Description: 通用哔咔响应类
 * @Project: PicaComic-Api-Java
 * @Date: 2025/10/31
 *
 * 脱离 OkHttp 生命周期的 API 响应解码器。
 *
 * <p>响应关闭前读取的字节会被用于后续 endpoint 解码。错误分类只保留状态码、
 * 标量服务端错误代码和 Retry-After 元数据，响应正文不会写入异常。</p>
 */
public class PicaResponse {

    protected final Response rawResponse;
    private final Object contentLock = new Object();
    protected volatile byte[] cachedContent;

    /**
     * 从 OkHttp 响应创建解码器。
     *
     * @param rawResponse 原始 OkHttp 响应
     */
    public PicaResponse(Response rawResponse) {
        if (rawResponse == null) {
            throw new IllegalArgumentException("Raw OkHttp Response cannot be null.");
        }
        this.rawResponse = rawResponse;
    }

    /**
     * 创建一个复用原始响应和已读取内容的包装器。
     *
     * @param other 需要复制的响应包装器
     */
    public PicaResponse(PicaResponse other) {
        if (other == null || other.rawResponse == null) {
            throw new IllegalArgumentException("Raw OkHttp Response cannot be null.");
        }
        this.rawResponse = other.rawResponse;
        this.cachedContent = other.getContent();
    }

    /**
     * 判断最终响应是否为 2xx 且包含有效的成功 envelope。
     *
     * @return 响应是否成功
     */
    public boolean isSuccess() {
        if (!rawResponse.isSuccessful()) {
            return false;
        }
        try {
            JsonObject json = parseJson(getContent());
            return json != null && hasDataObject(json) && !isProviderFailure(json);
        } catch (PicaApiException exception) {
            return false;
        }
    }

    /**
     * 校验 HTTP 状态码和服务端 envelope。
     *
     * @throws ResponseException 响应状态码或服务端业务结果表示失败时抛出
     * @throws PicaApiException 响应正文无法解析时抛出
     */
    public void requireSuccess() throws ResponseException {
        if (!rawResponse.isSuccessful()) {
            JsonObject errorEnvelope = parseJsonQuietly(getContentQuietly());
            throw new ResponseException(
                    PicaApiException.Reason.HTTP_STATUS,
                    getHttpCode(),
                    providerCode(errorEnvelope),
                    retryAfter(),
                    null);
        }

        JsonObject json = parseJson(getContent());
        if (isProviderFailure(json)) {
            throw new ResponseException(
                    PicaApiException.Reason.PROVIDER,
                    getHttpCode(),
                    providerCode(json),
                    retryAfter(),
                    null);
        }
        if (json == null || !hasDataObject(json)) {
            throw ParseResponseException.withHttpStatus(getHttpCode(), null);
        }
    }

    /**
     * 返回不暴露服务端文本的固定诊断信息。
     *
     * @return 安全的响应失败描述
     */
    public String getErrorMessage() {
        return "Pica API response failed";
    }

    /**
     * 获取最终 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int getHttpCode() {
        return rawResponse.code();
    }

    /**
     * 判断响应是否未通过成功校验。
     *
     * @return 响应是否失败
     */
    public boolean isNotSuccess() {
        return !isSuccess();
    }

    /**
     * 获取脱离响应生命周期的正文文本，供显式解码使用。
     *
     * <p>该文本不会用于公开异常消息或日志。</p>
     *
     * @return UTF-8 解码后的响应正文
     */
    public String getText() {
        return new String(getContent(), StandardCharsets.UTF_8);
    }

    /**
     * 获取最终请求 URL。
     *
     * @return 最终请求 URL
     */
    public String getUrl() {
        return rawResponse.request().url().toString();
    }

    /**
     * 获取最终响应 headers 的多值映射。
     *
     * @return 响应 headers
     */
    public Map<String, List<String>> getHeaders() {
        return rawResponse.headers().toMultimap();
    }

    /**
     * 获取响应正文的字节快照。
     *
     * <p>首次读取后会复用已缓存的正文；gzip 正文会先解压。</p>
     *
     * @return 响应正文字节
     */
    public byte[] getContent() {
        if (cachedContent == null) {
            synchronized (contentLock) {
                if (cachedContent == null) {
                    ResponseBody body = rawResponse.body();
                    if (body == null) {
                        cachedContent = new byte[0];
                    } else {
                        try {
                            byte[] rawBytes = body.bytes();
                            if ("gzip".equalsIgnoreCase(rawResponse.header("Content-Encoding"))) {
                                try {
                                    cachedContent = decompressGzip(rawBytes);
                                } catch (IOException exception) {
                                    throw ParseResponseException.withHttpStatus(getHttpCode(), exception);
                                }
                            } else {
                                cachedContent = rawBytes;
                            }
                        } catch (IllegalStateException exception) {
                            cachedContent = new byte[0];
                        } catch (IOException exception) {
                            throw new io.github.jukomu.picacomic.api.exception.NetworkException(
                                    PicaApiException.Reason.NETWORK, exception);
                        }
                    }
                }
            }
        }
        return cachedContent;
    }

    private byte[] getContentQuietly() {
        try {
            return getContent();
        } catch (PicaApiException exception) {
            return new byte[0];
        }
    }

    private byte[] decompressGzip(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return new byte[0];
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(compressedData);
             GZIPInputStream gzip = new GZIPInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = gzip.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    /**
     * 获取重定向链最初的请求 URL。
     *
     * @return 重定向链起始 URL
     */
    public String getOriginUrl() {
        Response current = rawResponse;
        String originUrl = current.request().url().toString();
        while (current != null) {
            originUrl = current.request().url().toString();
            current = current.priorResponse();
        }
        return originUrl;
    }

    /**
     * 获取响应的 Location 值；没有 Location 时返回最终请求 URL。
     *
     * @return 重定向目标或最终请求 URL
     */
    public String getRedirectUrl() {
        String location = rawResponse.header("Location");
        return location != null ? location : getUrl();
    }

    /**
     * 将正文解析为 JSON 对象映射。
     *
     * @return JSON 对象映射；空正文返回空映射
     * @throws ParseResponseException 正文不是有效 JSON 时抛出
     */
    public Map<String, Object> getMap() {
        String text = getText();
        if (text.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return JsonUtils.getGson().fromJson(text, new TypeToken<Map<String, Object>>() {
            }.getType());
        } catch (JsonParseException exception) {
            throw ParseResponseException.withHttpStatus(getHttpCode(), exception);
        }
    }

    /**
     * 将正文解析为 JSON 对象。
     *
     * @return JSON 对象
     * @throws ParseResponseException 正文不是有效 JSON 时抛出
     */
    public JsonObject getJson() {
        return parseJson(getContent());
    }

    /**
     * 获取成功 envelope 中的 data 对象 JSON 文本。
     *
     * @return data 对象的 JSON 文本
     * @throws ParseResponseException 响应缺少对象形式的 data 时抛出
     */
    public String getData() {
        JsonObject json = getJson();
        if (json == null || !hasDataObject(json)) {
            throw ParseResponseException.withHttpStatus(getHttpCode(), null);
        }
        return JsonUtils.toJsonString(json.get("data").getAsJsonObject());
    }

    /**
     * 获取原始 OkHttp 响应。
     *
     * @param <T> 调用方期望的响应类型
     * @return 原始响应对象
     */
    @SuppressWarnings("unchecked")
    public <T> T getRawResponse() {
        return (T) rawResponse;
    }

    /**
     * 判断响应是否经过至少一次重定向。
     *
     * @return 是否发生重定向
     */
    public boolean isRedirect() {
        return getRedirectCount() > 0;
    }

    /**
     * 获取响应链中的重定向次数。
     *
     * @return 重定向次数
     */
    public int getRedirectCount() {
        int count = 0;
        Response current = rawResponse;
        while (current.priorResponse() != null) {
            count++;
            current = current.priorResponse();
        }
        return count;
    }

    /**
     * 返回包含状态码和成功标记的安全摘要。
     *
     * @return 响应摘要
     */
    public String toString() {
        return "PicaResponse{" +
                "httpCode=" + getHttpCode() +
                ", success=" + isSuccess() +
                '}';
    }

    private JsonObject parseJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw ParseResponseException.withHttpStatus(getHttpCode(), null);
        }
        try {
            return JsonUtils.toJsonObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw ParseResponseException.withHttpStatus(getHttpCode(), exception);
        }
    }

    private JsonObject parseJsonQuietly(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return JsonUtils.toJsonObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean hasDataObject(JsonObject json) {
        JsonElement data = json == null ? null : json.get("data");
        return data != null && !data.isJsonNull() && data.isJsonObject();
    }

    private static boolean isProviderFailure(JsonObject json) {
        if (json == null) {
            return false;
        }
        JsonElement error = json.get("error");
        if (error != null && !error.isJsonNull()) {
            return true;
        }
        String code = scalarCode(json.get("code"));
        if (code != null && !isSuccessCode(code)) {
            return true;
        }
        JsonElement data = json.get("data");
        if (data != null && data.isJsonObject()) {
            JsonElement nestedError = data.getAsJsonObject().get("error");
            return nestedError != null && !nestedError.isJsonNull();
        }
        return false;
    }

    private static boolean isSuccessCode(String code) {
        return "0".equals(code) || "200".equals(code) || "OK".equalsIgnoreCase(code);
    }

    private static String providerCode(JsonObject json) {
        if (json == null) {
            return null;
        }
        String error = scalarCode(json.get("error"));
        return error != null ? error : scalarCode(json.get("code"));
    }

    private static String scalarCode(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || value.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        try {
            String code = value.getAsString();
            return code == null || code.isBlank() ? null : code;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Duration retryAfter() {
        String value = rawResponse.header("Retry-After");
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(trimmed));
            } catch (ArithmeticException | NumberFormatException ignored) {
                return null;
            }
        }
        try {
            Instant target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(Instant.now(), target);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return null;
        }
    }
}
