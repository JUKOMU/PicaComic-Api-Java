package io.github.jukomu.picacomic.core.internal.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Type;

/**
 * @author JUKOMU
 * @Description: JSON工具类
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class JsonUtils {
    private static final Gson GSON_INSTANCE = new Gson();

    private JsonUtils() {
    }

    public static Gson getGson() {
        return GSON_INSTANCE;
    }

    public static String toJsonString(Object object) {
        return GSON_INSTANCE.toJson(object);
    }

    public static JsonObject toJsonObject(String json) {
        JsonElement jsonElement = JsonParser.parseString(json);
        if (jsonElement.isJsonObject()) {
            return jsonElement.getAsJsonObject();
        }
        return null;
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON_INSTANCE.fromJson(json, clazz);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON_INSTANCE.fromJson(json, type);
    }
}
