package io.github.jukomu.picacomic.core.internal.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jukomu.picacomic.api.exception.ParseResponseException;
import io.github.jukomu.picacomic.api.model.*;
import io.github.jukomu.picacomic.core.internal.util.JsonUtils;
import io.github.jukomu.picacomic.core.internal.util.Strings;

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
     * 解析本子详情页的API JSON响应
     *
     * @param json API返回的JSON字符串
     * @param photos 章节摘要或详情列表；摘要本子可以传入 {@code null}
     * @return 一个 PicaAlbum 对象
     */
    public static PicaAlbum parserAlbum(String json, List<PicaPhoto> photos) {
        try {
            JsonObject jsonObject = JsonUtils.toJsonObject(json);

            String id = "";
            if (jsonObject.has("_id") && !jsonObject.get("_id").isJsonNull()) {
                id = Strings.defaultIfBlank(jsonObject.get("_id").getAsString(), "");
            }

            PicaUserInfo creator = null;
            if (jsonObject.has("_creator") && !jsonObject.get("_creator").isJsonNull()) {
                JsonObject creatorObj = jsonObject.get("_creator").getAsJsonObject();
                creator = parserUserInfo(JsonUtils.toJsonString(creatorObj));
            }

            String title = "";
            if (jsonObject.has("title") && !jsonObject.get("title").isJsonNull()) {
                title = Strings.defaultIfBlank(jsonObject.get("title").getAsString(), "");
            }

            String description = "";
            if (jsonObject.has("description") && !jsonObject.get("description").isJsonNull()) {
                description = Strings.defaultIfBlank(jsonObject.get("description").getAsString(), "");
            }

            PicaImage thumb = null;
            if (jsonObject.has("thumb") && !jsonObject.get("thumb").isJsonNull()) {
                JsonObject thumbObj = jsonObject.get("thumb").getAsJsonObject();
                String originalName = "";
                String path = "";
                String fileServer = "";
                if (thumbObj.has("originalName") && !thumbObj.get("originalName").isJsonNull()) {
                    originalName = Strings.defaultIfBlank(thumbObj.get("originalName").getAsString(), "");
                }
                if (thumbObj.has("path") && !thumbObj.get("path").isJsonNull()) {
                    path = Strings.defaultIfBlank(thumbObj.get("path").getAsString(), "");
                }
                if (thumbObj.has("fileServer") && !thumbObj.get("fileServer").isJsonNull()) {
                    fileServer = Strings.defaultIfBlank(thumbObj.get("fileServer").getAsString(), "");
                }
                thumb = new PicaImage(originalName, path, fileServer, null);
            }

            String author = "";
            if (jsonObject.has("author") && !jsonObject.get("author").isJsonNull()) {
                author = Strings.defaultIfBlank(jsonObject.get("author").getAsString(), "");
            }

            String chineseTeam = "";
            if (jsonObject.has("chineseTeam") && !jsonObject.get("chineseTeam").isJsonNull()) {
                chineseTeam = Strings.defaultIfBlank(jsonObject.get("chineseTeam").getAsString(), "");
            }

            List<String> categories = new ArrayList<>();
            if (jsonObject.has("categories") && jsonObject.get("categories").isJsonArray()) {
                for (JsonElement element : jsonObject.getAsJsonArray("categories")) {
                    categories.add(element.getAsString());
                }
            }

            List<String> tags = new ArrayList<>();
            if (jsonObject.has("tags") && jsonObject.get("tags").isJsonArray()) {
                for (JsonElement element : jsonObject.getAsJsonArray("tags")) {
                    tags.add(element.getAsString());
                }
            }

            int pagesCount = 0;
            if (jsonObject.has("pagesCount") && !jsonObject.get("pagesCount").isJsonNull()) {
                pagesCount = jsonObject.get("pagesCount").getAsInt();
            }

            int epsCount = 0;
            if (jsonObject.has("epsCount") && !jsonObject.get("epsCount").isJsonNull()) {
                epsCount = jsonObject.get("epsCount").getAsInt();
            }

            boolean finished = false;
            if (jsonObject.has("finished") && !jsonObject.get("finished").isJsonNull()) {
                finished = jsonObject.get("finished").getAsBoolean();
            }

            String updatedAt = "";
            if (jsonObject.has("updated_at") && !jsonObject.get("updated_at").isJsonNull()) {
                updatedAt = Strings.defaultIfBlank(jsonObject.get("updated_at").getAsString(), "");
            }

            String createdAt = "";
            if (jsonObject.has("created_at") && !jsonObject.get("created_at").isJsonNull()) {
                createdAt = Strings.defaultIfBlank(jsonObject.get("created_at").getAsString(), "");
            }

            boolean allowDownload = false;
            if (jsonObject.has("allowDownload") && !jsonObject.get("allowDownload").isJsonNull()) {
                allowDownload = jsonObject.get("allowDownload").getAsBoolean();
            }

            boolean allowComment = false;
            if (jsonObject.has("allowComment") && !jsonObject.get("allowComment").isJsonNull()) {
                allowComment = jsonObject.get("allowComment").getAsBoolean();
            }

            int totalLikes = 0;
            if (jsonObject.has("totalLikes") && !jsonObject.get("totalLikes").isJsonNull()) {
                totalLikes = jsonObject.get("totalLikes").getAsInt();
            }

            int totalViews = 0;
            if (jsonObject.has("totalViews") && !jsonObject.get("totalViews").isJsonNull()) {
                totalViews = jsonObject.get("totalViews").getAsInt();
            }

            int totalComments = 0;
            if (jsonObject.has("totalComments") && !jsonObject.get("totalComments").isJsonNull()) {
                totalComments = jsonObject.get("totalComments").getAsInt();
            }

            int viewsCount = 0;
            if (jsonObject.has("viewsCount") && !jsonObject.get("viewsCount").isJsonNull()) {
                viewsCount = jsonObject.get("viewsCount").getAsInt();
            }

            int likesCount = 0;
            if (jsonObject.has("likesCount") && !jsonObject.get("likesCount").isJsonNull()) {
                likesCount = jsonObject.get("likesCount").getAsInt();
            }

            int commentsCount = 0;
            if (jsonObject.has("commentsCount") && !jsonObject.get("commentsCount").isJsonNull()) {
                commentsCount = jsonObject.get("commentsCount").getAsInt();
            }

            boolean isFavourite = false;
            if (jsonObject.has("isFavourite") && !jsonObject.get("isFavourite").isJsonNull()) {
                isFavourite = jsonObject.get("isFavourite").getAsBoolean();
            }

            boolean isLiked = false;
            if (jsonObject.has("isLiked") && !jsonObject.get("isLiked").isJsonNull()) {
                isLiked = jsonObject.get("isLiked").getAsBoolean();
            }

            return new PicaAlbum(
                    id,
                    creator,
                    title,
                    description,
                    thumb,
                    author,
                    chineseTeam,
                    categories,
                    tags,
                    pagesCount,
                    epsCount,
                    finished,
                    updatedAt,
                    createdAt,
                    allowDownload,
                    allowComment,
                    totalLikes,
                    totalViews,
                    totalComments,
                    viewsCount,
                    likesCount,
                    commentsCount,
                    isFavourite,
                    isLiked,
                    photos);
        } catch (Exception e) {
            throw new ParseResponseException("Failed to parse album API JSON", e);
        }
    }

    /**
     * 解析本子章节索引的 API JSON 响应。
     *
     * <p>返回数组的第一个元素是 {@code List<PicaPhoto>}，第二个元素是下一页页码，
     * 没有下一页时为 {@code null}。</p>
     *
     * @param json API 返回的 JSON 字符串
     * @param albumId 所属本子 ID
     * @return 章节列表与下一页页码
     */
    public static Object[] parserPhotoList(String json, String albumId) {
        try {
            JsonObject jsonObject = JsonUtils.toJsonObject(json);

            int total = jsonObject.get("total").getAsInt();
            boolean isSingleAlbum = total == 1;

            List<PicaPhoto> photos = new ArrayList<>();
            JsonArray docs = jsonObject.getAsJsonArray("docs");
            for (JsonElement doc1 : docs) {
                JsonObject doc = doc1.getAsJsonObject();

                String id = "";
                if (doc.has("_id") && !doc.get("_id").isJsonNull()) {
                    id = Strings.defaultIfBlank(doc.get("_id").getAsString(), "");
                } else if (doc.has("id") && !doc.get("id").isJsonNull()) {
                    id = Strings.defaultIfBlank(doc.get("id").getAsString(), "");
                }

                int order = 1;
                if (doc.has("order") && !doc.get("order").isJsonNull()) {
                    order = doc.get("order").getAsInt();
                }

                String title = "";
                if (doc.has("title") && !doc.get("title").isJsonNull()) {
                    title = Strings.defaultIfBlank(doc.get("title").getAsString(), "");
                }

                String updatedAt = "";
                if (doc.has("updated_at") && !doc.get("updated_at").isJsonNull()) {
                    updatedAt = Strings.defaultIfBlank(doc.get("updated_at").getAsString(), "");
                }
                PicaPhoto photo = new PicaPhoto(albumId, id, title, updatedAt, order, null, isSingleAlbum);
                photos.add(photo);
            }

            int page = jsonObject.get("page").getAsInt();
            int pages = jsonObject.get("pages").getAsInt();
            if (page < pages) {
                return new Object[]{photos, page + 1};
            }
            return new Object[]{photos, null};
        } catch (Exception e) {
            throw new ParseResponseException("Failed to parse photo list API JSON", e);
        }
    }

    /**
     * 解析章节图片页的 API JSON 响应。
     *
     * <p>返回数组的第一个元素是 {@code List<PicaImage>}，第二个元素是下一页页码，
     * 没有下一页时为 {@code null}。</p>
     *
     * @param json API 返回的 JSON 字符串
     * @return 图片列表与下一页页码
     */
    public static Object[] parserImageList(String json) {
        try {
            JsonObject jsonObject = JsonUtils.toJsonObject(json);

            List<PicaImage> images = new ArrayList<>();
            JsonArray docs = jsonObject.getAsJsonArray("docs");
            for (JsonElement doc1 : docs) {
                JsonObject doc2 = doc1.getAsJsonObject();
                JsonObject doc = doc2.getAsJsonObject("media");

                String originalName = "";
                String path = "";
                String fileServer = "";
                if (doc.has("originalName") && !doc.get("originalName").isJsonNull()) {
                    originalName = Strings.defaultIfBlank(doc.get("originalName").getAsString(), "");
                }
                if (doc.has("path") && !doc.get("path").isJsonNull()) {
                    path = Strings.defaultIfBlank(doc.get("path").getAsString(), "");
                }
                if (doc.has("fileServer") && !doc.get("fileServer").isJsonNull()) {
                    fileServer = Strings.defaultIfBlank(doc.get("fileServer").getAsString(), "");
                }
                PicaImage picaImage = new PicaImage(originalName, path, fileServer, null);
                images.add(picaImage);
            }

            int page = jsonObject.get("page").getAsInt();
            int pages = jsonObject.get("pages").getAsInt();
            if (page < pages) {
                return new Object[]{images, page + 1};
            }
            return new Object[]{images, null};
        } catch (Exception e) {
            throw new ParseResponseException("Failed to parse image list API JSON", e);
        }
    }

    /**
     * 解析包含本子的页面的API JSON响应
     *
     * @param json API返回的JSON字符串
     * @return 一个 PicaContentPage 对象
     */
    public static PicaContentPage parserContentPage(String json) {
        try {
            JsonObject jsonObject = JsonUtils.toJsonObject(json);

            List<PicaAlbum> albums = new ArrayList<>();
            JsonArray docs = jsonObject.getAsJsonArray("docs");
            for (JsonElement doc : docs) {
                PicaAlbum album = parserAlbum(JsonUtils.toJsonString(doc), null);
                albums.add(album);
            }

            int limit = 20;
            if (jsonObject.has("limit") && !jsonObject.get("limit").isJsonNull()) {
                limit = jsonObject.get("limit").getAsInt();
            }

            int page = 1;
            if (jsonObject.has("page") && !jsonObject.get("page").isJsonNull()) {
                page = jsonObject.get("page").getAsInt();
            }

            int pages = 1;
            if (jsonObject.has("pages") && !jsonObject.get("pages").isJsonNull()) {
                pages = jsonObject.get("pages").getAsInt();
            }

            int total = 1;
            if (jsonObject.has("total") && !jsonObject.get("total").isJsonNull()) {
                total = jsonObject.get("total").getAsInt();
            }

            return new PicaContentPage(page, pages, total, limit, albums);
        } catch (Exception e) {
            throw new ParseResponseException("Failed to parse page API JSON", e);
        }
    }

    /**
     * 解析用户页的API JSON响应
     *
     * @param json API返回的JSON字符串
     * @return 一个 PicaUserInfo 对象
     */
    public static PicaUserInfo parserUserInfo(String json) {
        try {
            JsonObject jsonObject = JsonUtils.toJsonObject(json);

            String id = "";
            if (jsonObject.has("_id") && !jsonObject.get("_id").isJsonNull()) {
                id = Strings.defaultIfBlank(jsonObject.get("_id").getAsString(), "");
            }

            String name = "";
            if (jsonObject.has("name") && !jsonObject.get("name").isJsonNull()) {
                name = Strings.defaultIfBlank(jsonObject.get("name").getAsString(), "");
            }

            String email = "";
            if (jsonObject.has("email") && !jsonObject.get("email").isJsonNull()) {
                email = Strings.defaultIfBlank(jsonObject.get("email").getAsString(), "");
            }

            String slogan = "";
            if (jsonObject.has("slogan") && !jsonObject.get("slogan").isJsonNull()) {
                slogan = Strings.defaultIfBlank(jsonObject.get("slogan").getAsString(), "");
            }

            String birthday = "";
            if (jsonObject.has("birthday") && !jsonObject.get("birthday").isJsonNull()) {
                birthday = Strings.defaultIfBlank(jsonObject.get("birthday").getAsString(), "");
            }

            String gender = "";
            if (jsonObject.has("gender") && !jsonObject.get("gender").isJsonNull()) {
                gender = Strings.defaultIfBlank(jsonObject.get("gender").getAsString(), "");
            }

            String title = "";
            if (jsonObject.has("title") && !jsonObject.get("title").isJsonNull()) {
                title = Strings.defaultIfBlank(jsonObject.get("title").getAsString(), "");
            }

            boolean verified = false;
            if (jsonObject.has("verified") && !jsonObject.get("verified").isJsonNull()) {
                verified = jsonObject.get("verified").getAsBoolean();
            }

            long exp = 0;
            if (jsonObject.has("exp") && !jsonObject.get("exp").isJsonNull()) {
                exp = jsonObject.get("exp").getAsLong();
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
                createdAt = Strings.defaultIfBlank(jsonObject.get("created_at").getAsString(), "");
            }

            boolean isPunched = false;
            if (jsonObject.has("isPunched") && !jsonObject.get("isPunched").isJsonNull()) {
                isPunched = jsonObject.get("isPunched").getAsBoolean();
            }

            int comicsUploaded = 0;
            if (jsonObject.has("comicsUploaded") && !jsonObject.get("comicsUploaded").isJsonNull()) {
                comicsUploaded = jsonObject.get("comicsUploaded").getAsInt();
            }

            PicaImage picaImage = null;
            if (jsonObject.has("avatar") && !jsonObject.get("avatar").isJsonNull()) {
                JsonObject avatar = jsonObject.get("avatar").getAsJsonObject();
                String originalName = "";
                String path = "";
                String fileServer = "";
                if (avatar.has("originalName") && !avatar.get("originalName").isJsonNull()) {
                    originalName = Strings.defaultIfBlank(avatar.get("originalName").getAsString(), "");
                }
                if (avatar.has("path") && !avatar.get("path").isJsonNull()) {
                    path = Strings.defaultIfBlank(avatar.get("path").getAsString(), "");
                }
                if (avatar.has("fileServer") && !avatar.get("fileServer").isJsonNull()) {
                    fileServer = Strings.defaultIfBlank(avatar.get("fileServer").getAsString(), "");
                }
                picaImage = new PicaImage(originalName, path, fileServer, null);
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
                    picaImage,
                    isPunched,
                    comicsUploaded);

        } catch (Exception e) {
            throw new ParseResponseException("Failed to parse user API JSON", e);
        }
    }
}
