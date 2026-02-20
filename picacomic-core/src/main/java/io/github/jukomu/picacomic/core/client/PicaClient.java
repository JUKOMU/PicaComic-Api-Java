package io.github.jukomu.picacomic.core.client;

import com.google.gson.JsonObject;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.enums.ImageQuality;
import io.github.jukomu.picacomic.api.exception.NetworkException;
import io.github.jukomu.picacomic.api.exception.ResponseException;
import io.github.jukomu.picacomic.api.model.*;
import io.github.jukomu.picacomic.core.cache.CacheKey;
import io.github.jukomu.picacomic.core.cache.CachePool;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.constant.PicaConstants;
import io.github.jukomu.picacomic.core.crypto.PicaCryptoTool;
import io.github.jukomu.picacomic.core.net.model.PicaResponse;
import io.github.jukomu.picacomic.core.net.provider.PicaDomainManager;
import io.github.jukomu.picacomic.core.util.JsonUtils;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.github.jukomu.picacomic.core.constant.PicaConstants.*;
import static io.github.jukomu.picacomic.core.parser.PicaParser.*;

/**
 * @author JUKOMU
 * @Description: IPicaClient 接口的实现
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class PicaClient implements IPicaClient {

    private static final Logger logger = LoggerFactory.getLogger(PicaClient.class);
    private final PicaConfiguration config;
    private final OkHttpClient httpClient;
    private final ExecutorService internalExecutor;
    private final boolean isExternalExecutor;
    private volatile String loggedInUserName;
    private volatile String token;
    private final PicaDomainManager domainManager;
    private final CachePool<CacheKey, Object> cachePool;
    private final int concurrentPhotoDownloads;
    private final int concurrentImageDownloads;
    private final ImageQuality imageQuality;

    public PicaClient(PicaConfiguration config, OkHttpClient httpClient, PicaDomainManager domainManager) {
        this.token = null;
        this.config = Objects.requireNonNull(config);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.domainManager = Objects.requireNonNull(domainManager);
        this.domainManager.setInitialized(false);

        // 根据配置决定 ExecutorService
        if (config.getExecutor() != null) {
            this.internalExecutor = config.getExecutor();
            this.isExternalExecutor = true;
        } else {
            int poolSize = (config.getDownloadThreadPoolSize() > 0)
                    ? config.getDownloadThreadPoolSize()
                    : Runtime.getRuntime().availableProcessors();
            this.internalExecutor = Executors.newFixedThreadPool(poolSize);
            this.isExternalExecutor = false;
        }
        // 根据配置决定 CachePool
        this.cachePool = config.getCachePool();
        // 同时下载的章节数
        this.concurrentPhotoDownloads = config.getConcurrentPhotoDownloads();
        // 同时下载的图片数
        this.concurrentImageDownloads = config.getConcurrentImageDownloads();
        this.internalExecutor.submit(() -> {
            this.updateDomains();
            this.domainManager.setInitialized(true);
            this.initialize();
        });
        // 图片质量
        this.imageQuality = config.getImageQuality();
    }

    /**
     * 客户端初始化方法
     */
    private void initialize() {

    }

    /**
     * 更新域名列表
     */
    private void updateDomains() {

    }

    // == 核心数据获取层 ==

    @Override
    public PicaAlbum getAlbum(String albumId) {
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .build();
        List<PicaPhoto> photoList = getPhotoList(albumId);
        try {
            PicaResponse response = executeGetRequest(url);
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comic").getAsJsonObject();
            return parserAlbum(JsonUtils.toJsonString(jsonObject), photoList);
        } catch (Exception e) {
            logger.error("Failed to get album with error message :{}", e.getMessage());
            throw e;
        }
    }

    public List<PicaPhoto> getPhotoList(String albumId) {
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .addPathSegment("eps")
                .addQueryParameter("page", "1")
                .build();
        try {
            PicaResponse response = executeGetRequest(url);
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("eps").getAsJsonObject();
            Object[] parsered = parserPhotoList(JsonUtils.toJsonString(jsonObject), albumId);
            List<PicaPhoto> photos = new ArrayList<>();
            photos.addAll((Collection<? extends PicaPhoto>) parsered[0]);
            while (parsered[1] != null) {
                HttpUrl url2 = newHttpUrlBuilder()
                        .addPathSegment("comics")
                        .addPathSegment(albumId)
                        .addPathSegment("eps")
                        .addQueryParameter("page", String.valueOf(parsered[1]))
                        .build();
                PicaResponse response2 = executeGetRequest(url2);
                JsonObject jsonObject2 = JsonUtils.toJsonObject(response2.getData()).get("eps").getAsJsonObject();
                parsered = parserPhotoList(JsonUtils.toJsonString(jsonObject2), albumId);
                photos.addAll((Collection<? extends PicaPhoto>) parsered[0]);
            }
            return photos;
        } catch (Exception e) {
            logger.error("Failed to get photo list with error message :{}", e.getMessage());
            throw e;
        }
    }

    @Override
    public PicaPhoto getPhoto(String albumId, int order) {
        PicaAlbum album = getAlbum(albumId);
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .addPathSegment("order")
                .addPathSegment(String.valueOf(order))
                .addPathSegment("pages")
                .addQueryParameter("page", "1")
                .build();
        try {
            PicaResponse response = executeGetRequest(url);
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("pages").getAsJsonObject();
            Object[] parsered = parserImageList(JsonUtils.toJsonString(jsonObject));
            List<PicaImage> images = new ArrayList<>();
            images.addAll((Collection<? extends PicaImage>) parsered[0]);
            while (parsered[1] != null) {
                HttpUrl url2 = newHttpUrlBuilder()
                        .addPathSegment("comics")
                        .addPathSegment(albumId)
                        .addPathSegment("order")
                        .addPathSegment(String.valueOf(order))
                        .addPathSegment("pages")
                        .addQueryParameter("page", String.valueOf(parsered[1]))
                        .build();
                PicaResponse response2 = executeGetRequest(url2);
                JsonObject jsonObject2 = JsonUtils.toJsonObject(response2.getData()).get("pages").getAsJsonObject();
                parsered = parserImageList(JsonUtils.toJsonString(jsonObject2));
                images.addAll((Collection<? extends PicaImage>) parsered[0]);
            }

            String photoId = JsonUtils.toJsonObject(response.getData()).getAsJsonObject("ep").get("_id").getAsString();
            PicaPhoto albumPhoto = album.getPhoto(photoId);
            return new PicaPhoto(albumId, photoId, albumPhoto.getTitle(), albumPhoto.getUpdatedAt(), albumPhoto.getOrder(), images);
        } catch (Exception e) {
            logger.error("Failed to get photo with error message :{}", e.getMessage());
            throw e;
        }
    }

    @Override
    public byte[] fetchImageBytes(PicaImage image) {
        return new byte[0];
    }

    @Override
    public PicaContentPage search(SearchQuery query) {
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment("advanced-search")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .build();
        Objects.requireNonNull(query.getKeyword(), "Keyword cannot be null");

        Map<String, Object> params = new HashMap<>();
        params.put("keyword", query.getKeyword());
        params.put("sort", query.getOrderBy().getValue());
        if (query.getCategories() != null && !query.getCategories().isEmpty()) {
            params.put("categories", query.getCategories());
        }

        String jsonBody = JsonUtils.toJsonString(params);

        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.get("application/json; charset=UTF-8")
        );

        try {
            PicaResponse response = executePostRequest(url, body);
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonObject();
            return parserContentPage(JsonUtils.toJsonString(jsonObject));
        } catch (Exception e) {
            logger.error("Failed to search with error message :{}", e.getMessage());
            throw e;
        }
    }

    @Override
    public PicaContentPage getFavorites(SearchQuery query) {
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("users")
                .addPathSegment("favourite")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .addQueryParameter("s", query.getOrderBy().getValue())
                .build();
        try {
            PicaResponse response = executeGetRequest(url);
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonObject();
            return parserContentPage(JsonUtils.toJsonString(jsonObject));
        } catch (Exception e) {
            logger.error("Failed to get favorites with error message :{}", e.getMessage());
            throw e;
        }
    }

    @Override
    public PicaContentPage getCategories(SearchQuery query) {
        HttpUrl.Builder url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .addQueryParameter("s", query.getOrderBy().getValue());
        // 可选参数
        if (query.getCategories() != null && !query.getCategories().isEmpty()) {
            // 只选第一个分类
            url.addQueryParameter("c", query.getCategories().get(0).getValue());
        }
        if (StringUtils.isNotBlank(query.getTag())) {
            url.addQueryParameter("t", query.getTag());
        }
        if (StringUtils.isNotBlank(query.getAuthor())) {
            url.addQueryParameter("a", query.getAuthor());
        }
        if (StringUtils.isNotBlank(query.getTranslator())) {
            url.addQueryParameter("ct", query.getTag());
        }
        if (StringUtils.isNotBlank(query.getCreator())) {
            url.addQueryParameter("ca", query.getCreator());
        }

        try {
            PicaResponse response = executeGetRequest(url.build());
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonObject();
            return parserContentPage(JsonUtils.toJsonString(jsonObject));
        } catch (Exception e) {
            logger.error("Failed to get category with error message :{}", e.getMessage());
            throw e;
        }
    }

    @Override
    public PicaUserInfo getUserInfo() {
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("users")
                .addPathSegment("profile")
                .build();
        try {
            PicaResponse response = executeGetRequest(url);
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("user").getAsJsonObject();
            return parserUserInfo(JsonUtils.toJsonString(jsonObject));
        } catch (Exception e) {
            logger.error("Failed to get profile with error message :{}", e.getMessage());
            throw e;
        }
    }


    // == 会话管理层实现 ==

    @Override
    public PicaUserInfo login(String userNameOrEmail, String password) {
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("auth")
                .addPathSegment("sign-in")
                .build();

        Map<String, String> params = new HashMap<>();
        params.put("email", userNameOrEmail);
        params.put("password", password);

        String jsonBody = JsonUtils.toJsonString(params);

        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.get("application/json; charset=UTF-8")
        );

        try {
            PicaResponse response = executePostRequest(url, body);
            loggedInUserName = userNameOrEmail;
            token = response.getJson().get("data").getAsJsonObject().get("token").getAsString();
            return PicaUserInfo.partial(userNameOrEmail);
        } catch (Exception e) {
            logger.error("Failed to login with error message :{}", e.getMessage());
            throw e;
        }
    }

    private PicaResponse executeGetRequest(HttpUrl url) {
        Map<String, String> headers = buildHeaders(url, "GET", token, imageQuality.getValue());
        Request request = addAppHeader(getGetRequestBuilder(url), headers).build();
        PicaResponse response = executeRequest(request);
        response.requireSuccess();
        return response;
    }

    private PicaResponse executePostRequest(HttpUrl url, RequestBody requestBody) {
        Map<String, String> headers = buildHeaders(url, "POST", token, imageQuality.getValue());
        Request request = addAppHeader(getPostRequestBuilder(url, requestBody), headers).build();
        PicaResponse response = executeRequest(request);
        response.requireSuccess();
        return response;
    }

    /**
     * 通用请求执行方法
     *
     * @param request 请求对象
     * @return 通用禁漫响应类
     */
    private PicaResponse executeRequest(Request request) throws ResponseException, NetworkException {
        try (Response response = httpClient.newCall(request).execute()) {
            PicaResponse picaResponse = new PicaResponse(response);
            picaResponse.requireSuccess();
            return picaResponse;
        } catch (IOException e) {
            throw new NetworkException("Request failed due to I/O error", e);
        }
    }

    private Request.Builder addAppHeader(Request.Builder builder, Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    /**
     * 构建基础URL构建器
     *
     * @return HttpUrl Builder
     */
    private HttpUrl.Builder newHttpUrlBuilder() {
        // 这个方法很重要，它确保了所有请求都指向一个有效的、由DomainManager管理的域名
        // 我们只需要提供一个占位符域名，它将被拦截器替换
        return new HttpUrl.Builder()
                .scheme("https")
                .host(PicaConstants.PLACEHOLDER_HOST);
    }

    private Request.Builder getGetRequestBuilder(HttpUrl url) {
        return new Request.Builder().url(url).get();
    }

    private Request.Builder getPostRequestBuilder(HttpUrl url, RequestBody requestBody) {
        return new Request.Builder().url(url).post(requestBody);
    }

    /**
     * 构建所有 API 请求所需的 Headers
     *
     * @param url          请求url
     * @param method       请求方法 ("GET" 或 "POST")
     * @param authToken    登录后的 Token (Bearer token)，如果未登录传 null
     * @param imageQuality 图片质量 ("low", "medium", "high", "original")，默认 medium
     * @return Header Map
     */
    public static Map<String, String> buildHeaders(HttpUrl url, String method, String authToken, String imageQuality) {
        // 处理url路径
        String path = url.encodedPath().substring(1);
        String urlPath;
        if (url.query() != null) {
            urlPath = path + "?" + url.query();
        } else {
            urlPath = path;
        }

        Map<String, String> headers = new HashMap<>();

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = PicaCryptoTool.generateNonce();

        // 生成签名
        String signature = PicaCryptoTool.generateSignature(urlPath, timestamp, nonce, method);

        // 填充固定 Headers
        headers.put("app-channel", APP_CHANNEL);
        headers.put("app-uuid", APP_UUID);
        headers.put("app-version", APP_VERSION);
        headers.put("app-platform", APP_PLATFORM);
        headers.put("accept", ACCEPT_TYPE);
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("image-quality", imageQuality != null ? imageQuality : "medium");

        // 填充动态 Headers
        headers.put("time", timestamp);
        headers.put("nonce", nonce);
        headers.put("signature", signature);

        // 填充鉴权 Token
        if (authToken != null && !authToken.isEmpty()) {
            headers.put("authorization", authToken);
        }
        return headers;
    }
}
