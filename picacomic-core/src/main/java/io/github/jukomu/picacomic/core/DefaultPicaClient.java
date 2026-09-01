package io.github.jukomu.picacomic.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.client.PicaImageRequest;
import io.github.jukomu.picacomic.api.enums.ImageQuality;
import io.github.jukomu.picacomic.api.enums.TimeOption;
import io.github.jukomu.picacomic.api.exception.NetworkException;
import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.exception.ResponseException;
import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.model.PicaContentPage;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.api.model.PicaUserInfo;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import io.github.jukomu.picacomic.api.strategy.IAlbumPathGenerator;
import io.github.jukomu.picacomic.api.strategy.IImagePathGenerator;
import io.github.jukomu.picacomic.api.strategy.IPhotoPathGenerator;
import io.github.jukomu.picacomic.core.cache.CacheKey;
import io.github.jukomu.picacomic.core.cache.CachePool;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.constant.PicaConstants;
import io.github.jukomu.picacomic.core.crypto.PicaCryptoTool;
import io.github.jukomu.picacomic.core.net.model.PicaResponse;
import io.github.jukomu.picacomic.core.strategy.impl.DefaultAlbumPathGenerator;
import io.github.jukomu.picacomic.core.strategy.impl.DefaultImagePathGenerator;
import io.github.jukomu.picacomic.core.strategy.impl.DefaultPhotoPathGenerator;
import io.github.jukomu.picacomic.core.util.FileUtils;
import io.github.jukomu.picacomic.core.util.JsonUtils;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.jukomu.picacomic.core.constant.PicaConstants.ACCEPT_TYPE;
import static io.github.jukomu.picacomic.core.constant.PicaConstants.APP_CHANNEL;
import static io.github.jukomu.picacomic.core.constant.PicaConstants.APP_PLATFORM;
import static io.github.jukomu.picacomic.core.constant.PicaConstants.APP_UUID;
import static io.github.jukomu.picacomic.core.constant.PicaConstants.APP_VERSION;
import static io.github.jukomu.picacomic.core.parser.PicaParser.parserAlbum;
import static io.github.jukomu.picacomic.core.parser.PicaParser.parserContentPage;
import static io.github.jukomu.picacomic.core.parser.PicaParser.parserImageList;
import static io.github.jukomu.picacomic.core.parser.PicaParser.parserPhotoList;
import static io.github.jukomu.picacomic.core.parser.PicaParser.parserUserInfo;

/**
 * {@link IPicaClient} 的 package-private 实现。
 *
 * <p>生产代码只能通过 {@link PicaComic#newApiClient(PicaConfiguration)} 获得完整 runtime，
 * 避免调用者绕过 API/image client 的安全边界。</p>
 */
final class DefaultPicaClient implements IPicaClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPicaClient.class);

    @FunctionalInterface
    interface ImageFileWriter {
        void write(Path path, byte[] bytes) throws IOException;
    }

    @FunctionalInterface
    interface ImageFileMover {
        void move(Path temporary, Path target) throws IOException;
    }

    private final PicaConfiguration config;
    private final OkHttpClient apiClient;
    private final OkHttpClient imageClient;
    private final OkHttpClient domainProbeClient;
    private final PicaDomainManager domainManager;
    private final java.net.CookieManager cookieManager;
    private final ExecutorService downloadExecutor;
    private final boolean ownsDownloadExecutor;
    private final CachePool<CacheKey, Object> cachePool;
    private final int concurrentPhotoDownloads;
    private final int concurrentImageDownloads;
    private final ImageQuality imageQuality;
    private final ImageLocatorResolver imageLocatorResolver;
    private final Semaphore readerSlots;
    private final ImageFileWriter imageFileWriter;
    private final ImageFileMover imageFileMover;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<OkHttpPicaImageRequest> imageRequests = ConcurrentHashMap.newKeySet();
    private final Set<Call> apiCalls = ConcurrentHashMap.newKeySet();
    private final Set<Call> imageCalls = ConcurrentHashMap.newKeySet();
    private final Set<Future<?>> batchFutures = ConcurrentHashMap.newKeySet();

    private volatile String loggedInUserName;
    private volatile String token;

    DefaultPicaClient(PicaConfiguration config, OkHttpBuilder.HttpClientContext context) {
        this(config, context, DefaultPicaClient::writeImageFile, FileUtils::moveAtomically);
    }

    DefaultPicaClient(PicaConfiguration config,
                      OkHttpBuilder.HttpClientContext context,
                      ImageFileWriter imageFileWriter,
                      ImageFileMover imageFileMover) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        Objects.requireNonNull(context, "HTTP client context cannot be null");
        this.imageFileWriter = Objects.requireNonNull(imageFileWriter, "Image file writer cannot be null");
        this.imageFileMover = Objects.requireNonNull(imageFileMover, "Image file mover cannot be null");
        this.apiClient = Objects.requireNonNull(context.getApiClient(), "API client cannot be null");
        this.imageClient = Objects.requireNonNull(context.getImageClient(), "Image client cannot be null");
        this.domainProbeClient = createDomainProbeClient(this.apiClient, config.getDomainProbeTimeoutMs());
        this.domainManager = Objects.requireNonNull(context.getDomainManager(), "Domain manager cannot be null");
        this.cookieManager = Objects.requireNonNull(context.getCookieManager(), "Cookie manager cannot be null");

        if (config.getExecutor() == null) {
            this.downloadExecutor = Executors.newFixedThreadPool(config.getDownloadThreadPoolSize());
            this.ownsDownloadExecutor = true;
        } else {
            this.downloadExecutor = config.getExecutor();
            this.ownsDownloadExecutor = false;
        }
        this.cachePool = new CachePool<>(config.getCacheSize());
        this.concurrentPhotoDownloads = config.getConcurrentPhotoDownloads();
        this.concurrentImageDownloads = config.getConcurrentImageDownloads();
        this.imageQuality = config.getImageQuality();
        this.imageLocatorResolver = new ImageLocatorResolver();
        this.readerSlots = new Semaphore(concurrentImageDownloads, true);
        this.domainManager.setProbe(this::probeDomain);
        this.domainManager.startPeriodicProbe(config.getDomainProbeIntervalMs());
    }

    // == 核心数据获取层 ==

    @Override
    public PicaAlbum getAlbum(String albumId) {
        ensureOpen();
        PicaAlbum cached = getCachedPicaAlbum(albumId);
        if (cached != null) {
            return cached;
        }
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .build();
        List<PicaPhoto> photoList = getPhotoList(albumId);
        PicaResponse response = executeGetRequest(url);
        JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comic").getAsJsonObject();
        PicaAlbum album = parserAlbum(JsonUtils.toJsonString(jsonObject), photoList);
        cachePicaAlbum(album);
        return album;
    }

    @SuppressWarnings("unchecked")
    List<PicaPhoto> getPhotoList(String albumId) {
        ensureOpen();
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .addPathSegment("eps")
                .addQueryParameter("page", "1")
                .build();
        PicaResponse response = executeGetRequest(url);
        JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("eps").getAsJsonObject();
        Object[] parsed = parserPhotoList(JsonUtils.toJsonString(jsonObject), albumId);
        List<PicaPhoto> photos = new ArrayList<>();
        photos.addAll((List<PicaPhoto>) parsed[0]);
        while (parsed[1] != null) {
            HttpUrl pageUrl = newHttpUrlBuilder()
                    .addPathSegment("comics")
                    .addPathSegment(albumId)
                    .addPathSegment("eps")
                    .addQueryParameter("page", String.valueOf(parsed[1]))
                    .build();
            PicaResponse pageResponse = executeGetRequest(pageUrl);
            JsonObject pageObject = JsonUtils.toJsonObject(pageResponse.getData()).get("eps").getAsJsonObject();
            parsed = parserPhotoList(JsonUtils.toJsonString(pageObject), albumId);
            photos.addAll((List<PicaPhoto>) parsed[0]);
        }
        return photos;
    }

    @SuppressWarnings("unchecked")
    @Override
    public PicaPhoto getPhoto(String albumId, int order) {
        ensureOpen();
        PicaPhoto cached = getCachedPicaPhoto(albumId, order);
        if (cached != null) {
            return cached;
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
        PicaResponse response = executeGetRequest(url);
        JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("pages").getAsJsonObject();
        Object[] parsed = parserImageList(JsonUtils.toJsonString(jsonObject));
        List<PicaImage> images = new ArrayList<>();
        images.addAll((List<PicaImage>) parsed[0]);
        while (parsed[1] != null) {
            HttpUrl pageUrl = newHttpUrlBuilder()
                    .addPathSegment("comics")
                    .addPathSegment(albumId)
                    .addPathSegment("order")
                    .addPathSegment(String.valueOf(order))
                    .addPathSegment("pages")
                    .addQueryParameter("page", String.valueOf(parsed[1]))
                    .build();
            PicaResponse pageResponse = executeGetRequest(pageUrl);
            JsonObject pageObject = JsonUtils.toJsonObject(pageResponse.getData()).get("pages").getAsJsonObject();
            parsed = parserImageList(JsonUtils.toJsonString(pageObject));
            images.addAll((List<PicaImage>) parsed[0]);
        }

        String photoId = JsonUtils.toJsonObject(response.getData())
                .getAsJsonObject("ep").get("_id").getAsString();
        PicaPhoto albumPhoto = album.getPhoto(photoId);
        PicaPhoto photo = new PicaPhoto(albumId, photoId, albumPhoto.getTitle(), albumPhoto.getUpdatedAt(),
                albumPhoto.getOrder(), images, album.isSingleAlbum());
        cachePicaPhoto(photo, albumId, order);
        return photo;
    }

    @Override
    public PicaContentPage search(SearchQuery query) {
        ensureOpen();
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(query.getKeyword(), "Keyword cannot be null");
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment("advanced-search")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .build();
        Map<String, Object> params = new HashMap<>();
        params.put("keyword", query.getKeyword());
        params.put("sort", query.getOrderBy().getValue());
        if (query.getCategories() != null && !query.getCategories().isEmpty()) {
            params.put("categories", query.getCategories());
        }
        RequestBody body = RequestBody.create(JsonUtils.toJsonString(params),
                MediaType.get("application/json; charset=UTF-8"));
        PicaResponse response = executePostRequest(url, body);
        JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonObject();
        return parserContentPage(JsonUtils.toJsonString(jsonObject));
    }

    @Override
    public PicaContentPage getFavorites(SearchQuery query) {
        ensureOpen();
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(loggedInUserName, "Need login before getting favorites");
        PicaContentPage cached = getCachedPicaFavoritePage(query);
        if (cached != null) {
            return cached;
        }
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("users")
                .addPathSegment("favourite")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .addQueryParameter("s", query.getOrderBy().getValue())
                .build();
        PicaResponse response = executeGetRequest(url);
        JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonObject();
        PicaContentPage page = parserContentPage(JsonUtils.toJsonString(jsonObject));
        cachePicaFavoritePage(page, query);
        return page;
    }

    @Override
    public PicaContentPage getCategories(SearchQuery query) {
        ensureOpen();
        Objects.requireNonNull(query, "Query cannot be null");
        HttpUrl.Builder url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .addQueryParameter("s", query.getOrderBy().getValue());
        if (query.getCategories() != null && !query.getCategories().isEmpty()) {
            url.addQueryParameter("c", query.getCategories().get(0).getValue());
        }
        if (StringUtils.isNotBlank(query.getTag())) {
            url.addQueryParameter("t", query.getTag());
        }
        if (StringUtils.isNotBlank(query.getAuthor())) {
            url.addQueryParameter("a", query.getAuthor());
        }
        if (StringUtils.isNotBlank(query.getTranslator())) {
            url.addQueryParameter("ct", query.getTranslator());
        }
        if (StringUtils.isNotBlank(query.getCreator())) {
            url.addQueryParameter("ca", query.getCreator());
        }
        PicaResponse response = executeGetRequest(url.build());
        JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonObject();
        return parserContentPage(JsonUtils.toJsonString(jsonObject));
    }

    @Override
    public PicaContentPage getLeaderboard(TimeOption timeOption) {
        ensureOpen();
        Objects.requireNonNull(timeOption, "Time option cannot be null");
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment("leaderboard")
                .addQueryParameter("tt", timeOption.getValue())
                .addQueryParameter("ct", "VC")
                .build();
        PicaResponse response = executeGetRequest(url);
        JsonArray jsonArray = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonArray();
        List<PicaAlbum> albums = new ArrayList<>();
        for (JsonElement element : jsonArray) {
            albums.add(parserAlbum(JsonUtils.toJsonString(element), null));
        }
        return new PicaContentPage(1, 1, 40, 40, albums);
    }

    @Override
    public List<PicaUserInfo> getKnightLeaderboard() {
        ensureOpen();
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment("knight-leaderboard")
                .build();
        PicaResponse response = executeGetRequest(url);
        JsonArray jsonArray = JsonUtils.toJsonObject(response.getData()).get("users").getAsJsonArray();
        List<PicaUserInfo> users = new ArrayList<>();
        for (JsonElement element : jsonArray) {
            users.add(parserUserInfo(JsonUtils.toJsonString(element)));
        }
        return users;
    }

    @Override
    public PicaContentPage getRandomAlbums() {
        ensureOpen();
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment("random")
                .build();
        PicaResponse response = executeGetRequest(url);
        JsonArray jsonArray = JsonUtils.toJsonObject(response.getData()).get("comics").getAsJsonArray();
        List<PicaAlbum> albums = new ArrayList<>();
        for (JsonElement element : jsonArray) {
            albums.add(parserAlbum(JsonUtils.toJsonString(element), null));
        }
        return new PicaContentPage(1, 1, 20, 20, albums);
    }

    @Override
    public PicaUserInfo getUserInfo() {
        ensureOpen();
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("users")
                .addPathSegment("profile")
                .build();
        PicaResponse response = executeGetRequest(url);
        JsonObject jsonObject = JsonUtils.toJsonObject(response.getData()).get("user").getAsJsonObject();
        return parserUserInfo(JsonUtils.toJsonString(jsonObject));
    }

    // == 会话管理层 ==

    @Override
    public PicaUserInfo login(String userNameOrEmail, String password) {
        ensureOpen();
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("auth")
                .addPathSegment("sign-in")
                .build();
        Map<String, String> params = new HashMap<>();
        params.put("email", userNameOrEmail);
        params.put("password", password);
        RequestBody body = RequestBody.create(JsonUtils.toJsonString(params),
                MediaType.get("application/json; charset=UTF-8"));
        PicaResponse response = executePostRequest(url, body);
        loggedInUserName = userNameOrEmail;
        token = response.getJson().get("data").getAsJsonObject().get("token").getAsString();
        return PicaUserInfo.partial(userNameOrEmail);
    }

    // == 图片请求与便利下载 ==

    @Override
    public PicaImageRequest newImageRequest(PicaImage image) {
        if (closed.get()) {
            throw new ImageFetchException(ImageFetchException.Reason.CLIENT_CLOSED);
        }
        AtomicReference<OkHttpPicaImageRequest> reference = new AtomicReference<>();
        OkHttpPicaImageRequest request = new OkHttpPicaImageRequest(
                image,
                imageClient,
                imageLocatorResolver,
                readerSlots,
                closed::get,
                this::registerImageCall,
                this::unregisterImageCall,
                () -> imageRequests.remove(reference.get()));
        reference.set(request);
        imageRequests.add(request);
        if (closed.get()) {
            request.closeFromClient();
        }
        return request;
    }

    @Override
    public void downloadImage(PicaImage image) throws IOException {
        Objects.requireNonNull(image, "Image cannot be null");
        downloadImage(image, new DefaultImagePathGenerator().generatePath(image));
    }

    @Override
    public void downloadImage(String imageUrl, Path path) throws IOException {
        PicaImage image = new PicaImage("", "", "", imageUrl);
        downloadImage(image, path);
    }

    @Override
    public void downloadImage(PicaImage image, IImagePathGenerator imagePathGenerator) throws IOException {
        Objects.requireNonNull(imagePathGenerator, "Image path generator cannot be null");
        downloadImage(image, Objects.requireNonNull(imagePathGenerator.generatePath(image),
                "Image path generator returned null"));
    }

    @Override
    public void downloadImage(PicaImage image, Path path) throws IOException {
        ensureOpen();
        Objects.requireNonNull(image, "Image cannot be null");
        Path logicalTarget = resolveImageTarget(image, Objects.requireNonNull(path, "Image path cannot be null"));
        Path target = FileUtils.normalizeAbsolute(logicalTarget);
        if (Files.exists(target)) {
            return;
        }

        Path temporary = null;
        try (PicaImageRequest request = newImageRequest(image)) {
            byte[] imageBytes = request.execute();
            ensureDownloadOpen();
            temporary = FileUtils.createAtomicTemp(target);
            imageFileWriter.write(temporary, imageBytes);
            ensureDownloadOpen();
            try {
                imageFileMover.move(temporary, target);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } finally {
            FileUtils.deleteQuietly(temporary);
        }
    }

    private void ensureDownloadOpen() {
        if (closed.get()) {
            throw new ImageFetchException(ImageFetchException.Reason.CLIENT_CLOSED);
        }
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo) {
        ensureOpen();
        return downloadPhoto(photo, new DefaultPhotoPathGenerator());
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo, IPhotoPathGenerator pathGenerator) {
        ensureOpen();
        Objects.requireNonNull(photo, "Photo cannot be null");
        Objects.requireNonNull(pathGenerator, "Photo path generator cannot be null");
        PicaAlbum album = getAlbum(photo.getAlbumId());
        Path albumPath = new DefaultAlbumPathGenerator().generatePath(album);
        Path photoPath = Objects.requireNonNull(pathGenerator.generatePath(photo),
                "Photo path generator returned null");
        return downloadPhoto(photo, albumPath.resolve(photoPath), downloadExecutor);
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo, Path path) {
        return downloadPhoto(photo, path, downloadExecutor);
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo,
                                        IPhotoPathGenerator pathGenerator,
                                        ExecutorService executor) {
        ensureOpen();
        Objects.requireNonNull(photo, "Photo cannot be null");
        Objects.requireNonNull(pathGenerator, "Photo path generator cannot be null");
        PicaAlbum album = getAlbum(photo.getAlbumId());
        Path albumPath = new DefaultAlbumPathGenerator().generatePath(album);
        Path photoPath = Objects.requireNonNull(pathGenerator.generatePath(photo),
                "Photo path generator returned null");
        return downloadPhoto(photo, albumPath.resolve(photoPath), executor);
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo, Path path, ExecutorService executor) {
        ensureOpen();
        Objects.requireNonNull(photo, "Photo cannot be null");
        Objects.requireNonNull(path, "Photo path cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");
        ImageBatch batch = submitImageBatch(photo, path, executor);
        return awaitImageBatch(batch);
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album) {
        ensureOpen();
        return downloadAlbum(album, new DefaultAlbumPathGenerator());
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, IAlbumPathGenerator pathGenerator) {
        ensureOpen();
        Objects.requireNonNull(pathGenerator, "Album path generator cannot be null");
        Path path = Objects.requireNonNull(pathGenerator.generatePath(album),
                "Album path generator returned null");
        return downloadAlbum(album, path, downloadExecutor);
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, Path path) {
        return downloadAlbum(album, path, downloadExecutor);
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album,
                                        IAlbumPathGenerator pathGenerator,
                                        ExecutorService executor) {
        ensureOpen();
        Objects.requireNonNull(pathGenerator, "Album path generator cannot be null");
        Path path = Objects.requireNonNull(pathGenerator.generatePath(album),
                "Album path generator returned null");
        return downloadAlbum(album, path, executor);
    }

    /**
     * 在调用线程协调章节组；executor 中只有直接的图片叶子任务。
     */
    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, Path path, ExecutorService executor) {
        ensureOpen();
        Objects.requireNonNull(album, "Album cannot be null");
        Objects.requireNonNull(path, "Album path cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");

        List<ImageBatch> active = new ArrayList<>();
        List<Path> successful = new ArrayList<>();
        Map<PicaImage, Exception> failed = new HashMap<>();
        List<PicaPhoto> summaries = album.getPhotos() == null ? List.of() : album.getPhotos();

        for (PicaPhoto summary : summaries) {
            ensureOpen();
            PicaPhoto fullPhoto = summary;
            if (summary.getImages() == null || summary.getImages().isEmpty()) {
                fullPhoto = getPhoto(summary.getAlbumId(), summary.getOrder());
            }
            Path photoPath = new DefaultPhotoPathGenerator().generatePath(fullPhoto);
            ImageBatch batch = submitImageBatch(fullPhoto, path.resolve(photoPath), executor);
            active.add(batch);
            if (active.size() >= concurrentPhotoDownloads) {
                collectBatch(active.remove(0), successful, failed);
            }
        }
        for (ImageBatch batch : active) {
            collectBatch(batch, successful, failed);
        }
        return new DownloadResult(successful, failed);
    }

    private Path resolveImageTarget(PicaImage image, Path path) {
        if (Files.isDirectory(path)) {
            return path.resolve(FileUtils.safePathSegment(image.getOriginalName()));
        }
        return path;
    }

    private ImageBatch submitImageBatch(PicaPhoto photo, Path base, ExecutorService executor) {
        Objects.requireNonNull(photo, "Photo cannot be null");
        Objects.requireNonNull(base, "Photo path cannot be null");
        ImageBatch batch = new ImageBatch();
        List<PicaImage> images = photo.getImages() == null ? List.of() : photo.getImages();
        Path normalizedBase = FileUtils.normalizeAbsolute(base);
        for (PicaImage image : images) {
            if (image == null) {
                continue;
            }
            String safeName = FileUtils.safePathSegment(image.getOriginalName());
            Path target = base.resolve(safeName).normalize();
            Path absoluteTarget = normalizedBase.resolve(safeName).normalize();
            if (!absoluteTarget.startsWith(normalizedBase)) {
                batch.failed.put(image, new IOException("Image target escaped photo directory"));
                continue;
            }
            try {
                Future<Path> future = executor.submit(() -> {
                    if (closed.get()) {
                        throw new io.github.jukomu.picacomic.api.exception.ImageFetchException(
                                io.github.jukomu.picacomic.api.exception.ImageFetchException.Reason.CLIENT_CLOSED);
                    }
                    try {
                        downloadImage(image, target);
                        return target;
                    } catch (Exception exception) {
                        batch.failed.put(image, closed.get()
                                ? new io.github.jukomu.picacomic.api.exception.ImageFetchException(
                                io.github.jukomu.picacomic.api.exception.ImageFetchException.Reason.CLIENT_CLOSED)
                                : exception);
                        return null;
                    }
                });
                batch.tasks.add(new ImageTask(image, future));
                batchFutures.add(future);
                if (closed.get()) {
                    cancelBatchFuture(future);
                }
            } catch (RejectedExecutionException exception) {
                batch.failed.put(image, exception);
            }
        }
        return batch;
    }

    private DownloadResult awaitImageBatch(ImageBatch batch) {
        List<Path> successful = new ArrayList<>();
        Map<PicaImage, Exception> failed = new HashMap<>();
        collectBatch(batch, successful, failed);
        return new DownloadResult(successful, failed);
    }

    private void collectBatch(ImageBatch batch, List<Path> successful, Map<PicaImage, Exception> failed) {
        for (ImageTask task : batch.tasks) {
            try {
                Path result = task.future.get();
                if (result != null && !batch.failed.containsKey(task.image)) {
                    successful.add(result);
                }
            } catch (CancellationException exception) {
                batch.failed.putIfAbsent(task.image, closed.get()
                        ? new io.github.jukomu.picacomic.api.exception.ImageFetchException(
                        io.github.jukomu.picacomic.api.exception.ImageFetchException.Reason.CLIENT_CLOSED)
                        : new io.github.jukomu.picacomic.api.exception.ImageFetchException(
                        io.github.jukomu.picacomic.api.exception.ImageFetchException.Reason.CANCELLED));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                batch.failed.putIfAbsent(task.image, exception);
                for (ImageTask remaining : batch.tasks) {
                    cancelBatchFuture(remaining.future);
                    batch.failed.putIfAbsent(remaining.image, closed.get()
                            ? new ImageFetchException(ImageFetchException.Reason.CLIENT_CLOSED)
                            : exception);
                }
                break;
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                batch.failed.putIfAbsent(task.image, cause instanceof Exception
                        ? (Exception) cause : new RuntimeException(cause));
            } finally {
                batchFutures.remove(task.future);
            }
        }
        failed.putAll(batch.failed);
    }

    private void cancelBatchFuture(Future<?> future) {
        future.cancel(true);
        batchFutures.remove(future);
    }

    private static final class ImageTask {
        private final PicaImage image;
        private final Future<Path> future;

        private ImageTask(PicaImage image, Future<Path> future) {
            this.image = image;
            this.future = future;
        }
    }

    private static final class ImageBatch {
        private final List<ImageTask> tasks = new ArrayList<>();
        private final Map<PicaImage, Exception> failed = new ConcurrentHashMap<>();

    }

    // == 缓存辅助方法 ==

    private PicaAlbum getCachedPicaAlbum(String albumId) {
        return (PicaAlbum) cachePool.get(CacheKey.of(PicaAlbum.class, albumId));
    }

    private PicaPhoto getCachedPicaPhoto(String albumId, int order) {
        return (PicaPhoto) cachePool.get(CacheKey.of(PicaPhoto.class, albumId + "/" + order));
    }

    private PicaContentPage getCachedPicaFavoritePage(SearchQuery query) {
        if (loggedInUserName == null) {
            return null;
        }
        String order = query.getOrderBy().getValue();
        return (PicaContentPage) cachePool.get(CacheKey.of(PicaContentPage.class,
                loggedInUserName + "/" + order + "/" + query.getPage()));
    }

    private void cachePicaAlbum(PicaAlbum album) {
        cachePool.put(CacheKey.of(PicaAlbum.class, album.id()), album);
    }

    private void cachePicaPhoto(PicaPhoto photo, String albumId, int order) {
        cachePool.put(CacheKey.of(PicaPhoto.class, albumId + "/" + order), photo);
    }

    private void cachePicaFavoritePage(PicaContentPage page, SearchQuery query) {
        if (loggedInUserName == null) {
            return;
        }
        String order = query.getOrderBy().getValue();
        cachePool.put(CacheKey.of(PicaContentPage.class,
                loggedInUserName + "/" + order + "/" + query.getPage()), page);
    }

    // == API 请求组装与执行 ==

    private PicaResponse executeGetRequest(HttpUrl url) {
        Map<String, String> headers = buildHeaders(url, "GET", token, imageQuality.getValue());
        Request request = addAppHeaders(new Request.Builder().url(url).get(), headers).build();
        return executeRequest(request);
    }

    private PicaResponse executePostRequest(HttpUrl url, RequestBody body) {
        Map<String, String> headers = buildHeaders(url, "POST", token, imageQuality.getValue());
        Request request = addAppHeaders(new Request.Builder().url(url).post(body), headers).build();
        return executeRequest(request);
    }

    private PicaResponse executeRequest(Request request) {
        ensureOpen();
        Call call = apiClient.newCall(request);
        registerApiCall(call);
        try (Response response = call.execute()) {
            // PicaResponse 在 response 关闭前完成首次读取；返回对象只依赖其缓存的 detached bytes。
            PicaResponse picaResponse = new PicaResponse(response);
            picaResponse.requireSuccess();
            return picaResponse;
        } catch (ResponseException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new NetworkException("Request failed due to I/O error", exception);
        } finally {
            apiCalls.remove(call);
        }
    }

    private Request.Builder addAppHeaders(Request.Builder builder, Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    private HttpUrl.Builder newHttpUrlBuilder() {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(PicaConstants.PLACEHOLDER_HOST);
    }

    void reprobeDomains() {
        if (!closed.get()) {
            domainManager.probeAllDomains(this::probeDomain);
        }
    }

    private boolean probeDomain(String domain) {
        if (closed.get()) {
            return false;
        }
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host(domain)
                .build();
        Call call = domainProbeClient.newCall(new Request.Builder().url(url).head().build());
        registerApiCall(call);
        try (Response response = call.execute()) {
            return response.code() < 500;
        } catch (IOException exception) {
            return false;
        } finally {
            apiCalls.remove(call);
        }
    }

    private static OkHttpClient createDomainProbeClient(OkHttpClient apiClient, long timeoutMs) {
        OkHttpClient.Builder builder = apiClient.newBuilder();
        builder.interceptors().clear();
        builder.networkInterceptors().clear();
        builder.cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false);
        return builder.build();
    }

    /**
     * 构建 Pica API 使用的签名与 application headers。
     */
    static Map<String, String> buildHeaders(HttpUrl url,
                                            String method,
                                            String authToken,
                                            String imageQuality) {
        String pathPart = url.encodedPath().substring(1);
        String urlPath = url.query() == null
                ? pathPart
                : pathPart + "?" + url.encodedQuery();
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = PicaCryptoTool.generateNonce();
        String signature = PicaCryptoTool.generateSignature(urlPath, timestamp, nonce, method);

        Map<String, String> headers = new HashMap<>();
        headers.put("app-channel", APP_CHANNEL);
        headers.put("app-uuid", APP_UUID);
        headers.put("app-version", APP_VERSION);
        headers.put("app-platform", APP_PLATFORM);
        headers.put("accept", ACCEPT_TYPE);
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("image-quality", imageQuality == null ? "medium" : imageQuality);
        headers.put("time", timestamp);
        headers.put("nonce", nonce);
        headers.put("signature", signature);
        if (authToken != null && !authToken.isEmpty()) {
            headers.put("authorization", authToken);
        }
        return headers;
    }

    private void registerApiCall(Call call) {
        if (closed.get()) {
            call.cancel();
            throw new IllegalStateException("Client is closed");
        }
        apiCalls.add(call);
        if (closed.get()) {
            apiCalls.remove(call);
            call.cancel();
            throw new IllegalStateException("Client is closed");
        }
    }

    private void registerImageCall(Call call) {
        imageCalls.add(call);
        if (closed.get()) {
            imageCalls.remove(call);
            call.cancel();
        }
    }

    private void unregisterImageCall(Call call) {
        imageCalls.remove(call);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }

    // == 资源管理 ==

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        for (OkHttpPicaImageRequest request : imageRequests) {
            request.closeFromClient();
        }
        for (Call call : apiCalls) {
            call.cancel();
        }
        for (Call call : imageCalls) {
            call.cancel();
        }
        for (Future<?> future : batchFutures) {
            cancelBatchFuture(future);
        }
        apiClient.dispatcher().cancelAll();
        imageClient.dispatcher().cancelAll();
        domainManager.shutdown();

        token = null;
        loggedInUserName = null;
        cookieManager.getCookieStore().removeAll();
        cachePool.clear();
        if (ownsDownloadExecutor) {
            downloadExecutor.shutdown();
            try {
                if (!downloadExecutor.awaitTermination(config.getCloseTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    downloadExecutor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                downloadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        closeHttpClient(domainProbeClient);
        closeHttpClient(apiClient);
        closeHttpClient(imageClient);
    }

    private static void closeHttpClient(OkHttpClient client) {
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
        if (client.cache() != null) {
            try {
                client.cache().close();
            } catch (IOException exception) {
                LOGGER.debug("Failed to close HTTP cache: {}", exception.getClass().getSimpleName());
            }
        }
    }

    private static void writeImageFile(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes, StandardOpenOption.WRITE);
    }
}
