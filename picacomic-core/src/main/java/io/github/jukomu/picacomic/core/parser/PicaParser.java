package io.github.jukomu.picacomic.core.parser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jukomu.picacomic.api.exception.ParseResponseException;
import io.github.jukomu.picacomic.api.model.PicaAvatar;
import io.github.jukomu.picacomic.api.model.PicaUserInfo;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * @author JUKOMU
 * @Description: 内部工具类，负责将PicaComic的JSON响应解析为Java数据模型
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public class PicaParser {

    private PicaParser() {
    }

    /**
     * 解析用户页的API JSON响应
     *
     * @param json API返回的JSON字符串
     * @return 一个 PicaUserInfo 对象
     */
    public static PicaUserInfo parserUserInfo(String json) {
        try {
            JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject().get("user").getAsJsonObject();

            String id = "";
            if (jsonObject.has("_id") && !jsonObject.get("_id").isJsonNull()) {
                id = StringUtils.defaultIfBlank(jsonObject.get("_id").getAsString(), "");
            }

            String name = "";
            if (jsonObject.has("name") && !jsonObject.get("name").isJsonNull()) {
                name = StringUtils.defaultIfBlank(jsonObject.get("name").getAsString(), "");
            }

            String email = "";
            if (jsonObject.has("email") && !jsonObject.get("email").isJsonNull()) {
                email = StringUtils.defaultIfBlank(jsonObject.get("email").getAsString(), "");
            }

            String slogan = "";
            if (jsonObject.has("slogan") && !jsonObject.get("slogan").isJsonNull()) {
                slogan = StringUtils.defaultIfBlank(jsonObject.get("slogan").getAsString(), "");
            }

            String birthday = "";
            if (jsonObject.has("birthday") && !jsonObject.get("birthday").isJsonNull()) {
                birthday = StringUtils.defaultIfBlank(jsonObject.get("birthday").getAsString(), "");
            }

            String gender = "";
            if (jsonObject.has("gender") && !jsonObject.get("gender").isJsonNull()) {
                gender = StringUtils.defaultIfBlank(jsonObject.get("gender").getAsString(), "");
            }

            String title = "";
            if (jsonObject.has("title") && !jsonObject.get("title").isJsonNull()) {
                title = StringUtils.defaultIfBlank(jsonObject.get("title").getAsString(), "");
            }

            boolean verified = false;
            if (jsonObject.has("verified") && !jsonObject.get("verified").isJsonNull()) {
                verified = jsonObject.get("verified").getAsBoolean();
            }

            int exp = 0;
            if (jsonObject.has("exp") && !jsonObject.get("exp").isJsonNull()) {
                exp = jsonObject.get("exp").getAsInt();
            }

            int level = 0;
            if (jsonObject.has("level") && !jsonObject.get("level").isJsonNull()) {
                level = jsonObject.get("level").getAsInt();
            }

            List<String> characters = new ArrayList<>();
            if (jsonObject.has("characters") && jsonObject.get("characters").isJsonArray()) {
                for (JsonElement element : jsonObject.getAsJsonArray("characters")) {
                    characters.add(element.getAsString());
                }
            }

            String createdAt = "";
            if (jsonObject.has("created_at") && !jsonObject.get("created_at").isJsonNull()) {
                createdAt = StringUtils.defaultIfBlank(jsonObject.get("created_at").getAsString(), "");
            }

            boolean isPunched = false;
            if (jsonObject.has("isPunched") && !jsonObject.get("isPunched").isJsonNull()) {
                isPunched = jsonObject.get("isPunched").getAsBoolean();
            }

            PicaAvatar picaAvatar = null;
            if (jsonObject.has("avatar") && !jsonObject.get("avatar").isJsonNull()) {
                JsonObject avatar = jsonObject.get("avatar").getAsJsonObject();
                String originalName = "";
                String path = "";
                String fileServer = "";
                if (avatar.has("originalName") && !avatar.get("originalName").isJsonNull()) {
                    originalName = StringUtils.defaultIfBlank(avatar.get("originalName").getAsString(), "");
                }
                if (avatar.has("path") && !avatar.get("path").isJsonNull()) {
                    path = StringUtils.defaultIfBlank(avatar.get("path").getAsString(), "");
                }
                if (avatar.has("fileServer") && !avatar.get("fileServer").isJsonNull()) {
                    fileServer = StringUtils.defaultIfBlank(avatar.get("fileServer").getAsString(), "");
                }
                picaAvatar = new PicaAvatar(originalName, path, fileServer);
            }

            return new PicaUserInfo(
                    id,
                    name,
                    email,
                    slogan,
                    birthday,
                    gender,
                    title,
                    verified,
                    exp,
                    level,
                    characters,
                    createdAt,
                    picaAvatar,
                    isPunched);

        } catch (Exception e) {
            throw new ParseResponseException("Failed to parse album API JSON", e);
        }
    }
}
