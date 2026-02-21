package io.github.jukomu.picacomic.core.client;

import com.google.gson.JsonObject;
import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.enums.ImageQuality;
import io.github.jukomu.picacomic.api.exception.NetworkException;
import io.github.jukomu.picacomic.api.exception.ResponseException;
import io.github.jukomu.picacomic.api.model.*;
import io.github.jukomu.picacomic.api.strategy.IAlbumPathGenerator;
import io.github.jukomu.picacomic.api.strategy.IImagePathGenerator;
import io.github.jukomu.picacomic.api.strategy.IPhotoPathGenerator;
import io.github.jukomu.picacomic.core.cache.CacheKey;
import io.github.jukomu.picacomic.core.cache.CachePool;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.constant.PicaConstants;
import io.github.jukomu.picacomic.core.crypto.PicaCryptoTool;
import io.github.jukomu.picacomic.core.net.model.PicaResponse;
import io.github.jukomu.picacomic.core.net.provider.PicaDomainManager;
import io.github.jukomu.picacomic.core.strategy.impl.DefaultAlbumPathGenerator;
import io.github.jukomu.picacomic.core.strategy.impl.DefaultImagePathGenerator;
import io.github.jukomu.picacomic.core.strategy.impl.DefaultPhotoPathGenerator;
import io.github.jukomu.picacomic.core.util.JsonUtils;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

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
        this.loggedInUserName = null;
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
        PicaAlbum cachedPicaAlbum = getCachedPicaAlbum(albumId);
        if (cachedPicaAlbum != null) {
            return cachedPicaAlbum;
        }
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .build();
        List<PicaPhoto> photoList = getPhotoList(albumId);
        try {
            PicaResponse response = executeGetRequest(url);
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comic").getAsJsonObject();
            PicaAlbum album = parserAlbum(JsonUtils.toJsonString(jsonObject), photoList);
            cachePicaAlbum(album);
            return album;
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
        PicaPhoto cachedPicaPhoto = getCachedPicaPhoto(albumId, order);
        if (cachedPicaPhoto != null) {
            return cachedPicaPhoto;
        }
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
            PicaPhoto photo = new PicaPhoto(albumId, photoId, albumPhoto.getTitle(), albumPhoto.getUpdatedAt(), albumPhoto.getOrder(), images, album.isSingleAlbum());
            cachePicaPhoto(photo, albumId, order);
            return photo;
        } catch (Exception e) {
            logger.error("Failed to get photo with error message :{}", e.getMessage());
            throw e;
        }
    }

    @Override
    public byte[] fetchImageBytes(PicaImage image) {
        String imageUrl = image.getImageUrl();
        Request request = new Request.Builder()
                .url(imageUrl)
                .get()
                .build();

        try {
            PicaResponse response = executeRequest(request);
            return response.getContent();
        } catch (Exception e) {
            logger.error("Failed to fetch image with error message :{}", e.getMessage());
            throw e;
        }
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
        PicaContentPage cachedPicaFavoritePage = getCachedPicaFavoritePage(query);
        if (cachedPicaFavoritePage != null) {
            return cachedPicaFavoritePage;
        }
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("users")
                .addPathSegment("favourite")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .addQueryParameter("s", query.getOrderBy().getValue())
                .build();
        try {
            PicaResponse response = executeGetRequest(url);
            JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonObject();
            PicaContentPage picaContentPage = parserContentPage(JsonUtils.toJsonString(jsonObject));
            cachePicaFavoritePage(picaContentPage, query);
            return picaContentPage;
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

    // == 便利操作层实现 ==

    @Override
    public void downloadImage(PicaImage image) throws IOException {
        downloadImage(image, new DefaultImagePathGenerator().generatePath(image));
    }

    @Override
    public void downloadImage(String imageUrl, Path path) throws IOException {
        PicaImage picaImage = new PicaImage("", "", "", imageUrl);
        downloadImage(picaImage, path);
    }

    @Override
    public void downloadImage(PicaImage image, IImagePathGenerator imagePathGenerator) throws IOException {
        downloadImage(image, imagePathGenerator.generatePath(image));
    }

    @Override
    public void downloadImage(PicaImage image, Path path) throws IOException {
        logger.info("开始下载图片: {}", image.getImageUrl());
        if (Files.isDirectory(path)) {
            // 路径为目录则拼接文件名
            path = path.resolve(image.getOriginalName());
        }
        // 检查文件是否存在
        if (Files.exists(path)) {
            logger.info("图片 {} 已存在，跳过下载", image.getOriginalName());
            return;
        }
        byte[] imageBytes = fetchImageBytes(image);
        // 确保路径存在
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, imageBytes);
        logger.info("图片 {} 下载完成", image.getImageUrl());
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo) {
        return downloadPhoto(photo, new DefaultPhotoPathGenerator());
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo, IPhotoPathGenerator pathGenerator) {
        PicaAlbum album = getAlbum(photo.getAlbumId());
        // 拼接完整路径
        Path pathAlbum = new DefaultAlbumPathGenerator().generatePath(album);
        Path pathPhoto = pathGenerator.generatePath(photo);
        return downloadPhoto(photo, pathAlbum.resolve(pathPhoto));
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo, Path path) {
        return downloadPhoto(photo, path, this.internalExecutor);
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo, IPhotoPathGenerator pathGenerator, ExecutorService executor) {
        PicaAlbum album = getAlbum(photo.getAlbumId());
        // 拼接完整路径
        Path pathAlbum = new DefaultAlbumPathGenerator().generatePath(album);
        Path pathPhoto = pathGenerator.generatePath(photo);
        return downloadPhoto(photo, pathAlbum.resolve(pathPhoto), executor);
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo, Path path, ExecutorService executor) {
        logger.info("开始下载章节: {}", photo.getTitle());
        Semaphore semaphore = new Semaphore(concurrentImageDownloads);
        List<CompletableFuture<Path>> futures = new ArrayList<>();
        List<Path> successfulFiles = Collections.synchronizedList(new ArrayList<>());
        ConcurrentHashMap<PicaImage, Exception> failedTasks = new ConcurrentHashMap<>();

        for (PicaImage image : photo.images()) {
            try {
                semaphore.acquire();
                CompletableFuture<Path> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        Objects.requireNonNull(path, "Photo path generator returned null for photo " + photo.id());

                        Path destination = path.resolve(image.getOriginalName());

                        downloadImage(image, destination);
                        return destination;
                    } catch (Exception e) {
                        failedTasks.put(image, e);
                        throw new CompletionException(e);
                    }
                }, executor);
                future.whenComplete((result, throwable) -> semaphore.release());
                futures.add(future);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedTasks.put(image, e);
                break;
            }
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            logger.warn("下载章节 '{}' 时部分图片下载失败。", photo.getTitle());
        }

        for (CompletableFuture<Path> future : futures) {
            if (!future.isCompletedExceptionally()) {
                successfulFiles.add(future.join());
            }
        }

        DownloadResult downloadResult = new DownloadResult(successfulFiles, failedTasks);
        logger.info("章节 {} 下载完成. 成功: {}, 失败: {}", photo.getTitle(), downloadResult.getSuccessfulFiles().size(), downloadResult.getFailedTasks().size());
        return downloadResult;
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album) {
        return downloadAlbum(album, new DefaultAlbumPathGenerator());
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, IAlbumPathGenerator pathGenerator) {
        return downloadAlbum(album, pathGenerator, this.internalExecutor);
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, Path path) {
        return downloadAlbum(album, path, this.internalExecutor);
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, IAlbumPathGenerator pathGenerator, ExecutorService executor) {
        return downloadAlbum(album, pathGenerator.generatePath(album), executor);
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, Path path, ExecutorService executor) {
        logger.info("开始下载本子: {}", album.getTitle());
        Semaphore semaphore = new Semaphore(concurrentPhotoDownloads);
        // 有具体路径时直接下载章节直接无需拼接album路径
        List<CompletableFuture<DownloadResult>> photoFutures = new ArrayList<>();
        Objects.requireNonNull(path, "Album path generator returned null for album: " + album.id());

        for (PicaPhoto photo : album.getPhotos()) {
            try {
                semaphore.acquire();
                CompletableFuture<DownloadResult> future = CompletableFuture.supplyAsync(() -> {
                    PicaPhoto fullPhoto = getPhoto(photo.getAlbumId(), photo.getOrder());
                    return downloadPhoto(fullPhoto, path.resolve(new DefaultPhotoPathGenerator().generatePath(fullPhoto)), executor);
                }, executor);
                future.whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        logger.error("下载章节 '{}' (ID: {}) 失败: {}", photo.getTitle(), photo.id(), throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage());
                    }
                    semaphore.release();
                });
                photoFutures.add(future);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("下载本子 '{}' 的过程被中断", album.getTitle());
                break;
            }

        }

        try {
            CompletableFuture.allOf(photoFutures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            logger.warn("下载本子 '{}' 时部分章节下载失败。", album.getTitle());
        }
        List<Path> allSuccessfulFiles = Collections.synchronizedList(new ArrayList<>());
        ConcurrentHashMap<PicaImage, Exception> allFailedTasks = new ConcurrentHashMap<>();

        for (CompletableFuture<DownloadResult> future : photoFutures) {
            if (!future.isCompletedExceptionally()) {
                DownloadResult result = future.join();
                allSuccessfulFiles.addAll(result.getSuccessfulFiles());
                allFailedTasks.putAll(result.getFailedTasks());
            }
        }

        DownloadResult downloadResult = new DownloadResult(allSuccessfulFiles, allFailedTasks);
        logger.info("本子 {} 下载完成. 成功图片数: {}, 失败图片数: {}", album.getTitle(), downloadResult.getSuccessfulFiles().size(), downloadResult.getFailedTasks().size());
        return downloadResult;
    }

    // == 缓存辅助方法 ==

    /**
     * 获取本子缓存
     *
     * @param albumId 本子id
     * @return 本子详情
     */
    private PicaAlbum getCachedPicaAlbum(String albumId) {
        return (PicaAlbum) cachePool.get(CacheKey.of(PicaAlbum.class, albumId));
    }

    /**
     * 获取章节缓存
     *
     * @param albumId 本子id
     * @param order   顺序
     * @return 章节详情
     */
    private PicaPhoto getCachedPicaPhoto(String albumId, int order) {
        return (PicaPhoto) cachePool.get(CacheKey.of(PicaPhoto.class, albumId + "/" + order));
    }

    /**
     * 获取收藏夹缓存
     *
     * @return 收藏夹详情
     */
    private PicaContentPage getCachedPicaFavoritePage(SearchQuery query) {
        if (loggedInUserName == null) {
            return null;
        }
        String order = query.getOrderBy().getValue();
        int page = query.getPage();
        return (PicaContentPage) cachePool.get(CacheKey.of(PicaContentPage.class, loggedInUserName + "/" + order + "/" + page));
    }

    /**
     * 缓存本子详情
     *
     * @param album 本子详情
     */
    private void cachePicaAlbum(PicaAlbum album) {
        cachePool.put(CacheKey.of(PicaAlbum.class, album.id()), album);
    }

    /**
     * 缓存章节详情
     *
     * @param photo   章节详情
     * @param albumId 本子id
     * @param order   顺序
     */
    private void cachePicaPhoto(PicaPhoto photo, String albumId, int order) {
        cachePool.put(CacheKey.of(PicaPhoto.class, albumId + "/" + order), photo);
    }

    /**
     * 缓存用户收藏夹详情
     *
     * @param favoritePage 收藏夹详情
     * @param query        搜索参数
     */
    private void cachePicaFavoritePage(PicaContentPage favoritePage, SearchQuery query) {
        if (loggedInUserName == null) {
            return;
        }
        String order = query.getOrderBy().getValue();
        int page = query.getPage();
        cachePool.put(CacheKey.of(PicaContentPage.class, loggedInUserName + "/" + order + "/" + page), favoritePage);
    }

    // == 辅助方法 ==

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

    // == 资源管理实现 ==

    @Override
    public void close() {
        // 只有当 ExecutorService 是由本客户端内部创建时，才负责关闭它
        if (!isExternalExecutor && internalExecutor != null && !internalExecutor.isShutdown()) {
            internalExecutor.shutdown();
        }
        // OkHttpClient 内部有自己的连接池和线程池，也需要关闭
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        try (var cache = httpClient.cache()) {
            if (cache != null) {
                cache.close();
            }
        } catch (IOException e) {
            logger.error("I/O error in close()", e);
        }
    }
}
