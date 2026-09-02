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
 * A detached API response decoder.
 *
 * <p>Successful endpoint data is decoded from bytes read before the OkHttp
 * response is closed. Error classification only retains status, scalar
 * provider code and Retry-After metadata; response text is never placed in an
 * exception.</p>
 */
public class PicaResponse {

    protected final Response rawResponse;
    private final Object contentLock = new Object();
    protected volatile byte[] cachedContent;

    public PicaResponse(Response rawResponse) {
        if (rawResponse == null) {
            throw new IllegalArgumentException("Raw OkHttp Response cannot be null.");
        }
        this.rawResponse = rawResponse;
    }

    public PicaResponse(PicaResponse other) {
        if (other == null || other.rawResponse == null) {
            throw new IllegalArgumentException("Raw OkHttp Response cannot be null.");
        }
        this.rawResponse = other.rawResponse;
        this.cachedContent = other.getContent();
    }

    /**
     * Returns whether the final response has a non-empty body and a 2xx code.
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
     * Validates the HTTP status and the provider envelope.
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
     * Returns a fixed diagnostic string without exposing provider text.
     */
    public String getErrorMessage() {
        return "Pica API response failed";
    }

    public int getHttpCode() {
        return rawResponse.code();
    }

    public boolean isNotSuccess() {
        return !isSuccess();
    }

    /**
     * Returns detached response text for explicit decoder use. It is not used
     * in public exception messages or logs.
     */
    public String getText() {
        return new String(getContent(), StandardCharsets.UTF_8);
    }

    public String getUrl() {
        return rawResponse.request().url().toString();
    }

    public Map<String, List<String>> getHeaders() {
        return rawResponse.headers().toMultimap();
    }

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

    public String getOriginUrl() {
        Response current = rawResponse;
        String originUrl = current.request().url().toString();
        while (current != null) {
            originUrl = current.request().url().toString();
            current = current.priorResponse();
        }
        return originUrl;
    }

    public String getRedirectUrl() {
        String location = rawResponse.header("Location");
        return location != null ? location : getUrl();
    }

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

    public JsonObject getJson() {
        return parseJson(getContent());
    }

    public String getData() {
        JsonObject json = getJson();
        if (json == null || !hasDataObject(json)) {
            throw ParseResponseException.withHttpStatus(getHttpCode(), null);
        }
        return JsonUtils.toJsonString(json.get("data").getAsJsonObject());
    }

    @SuppressWarnings("unchecked")
    public <T> T getRawResponse() {
        return (T) rawResponse;
    }

    public boolean isRedirect() {
        return getRedirectCount() > 0;
    }

    public int getRedirectCount() {
        int count = 0;
        Response current = rawResponse;
        while (current.priorResponse() != null) {
            count++;
            current = current.priorResponse();
        }
        return count;
    }

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
