package io.github.jukomu.picacomic.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.client.PicaImageRequest;
import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.enums.ImageQuality;
import io.github.jukomu.picacomic.api.enums.PicaSessionState;
import io.github.jukomu.picacomic.api.enums.TimeOption;
import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.exception.ParseResponseException;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.*;
import io.github.jukomu.picacomic.api.strategy.IAlbumPathGenerator;
import io.github.jukomu.picacomic.api.strategy.IImagePathGenerator;
import io.github.jukomu.picacomic.api.strategy.IPhotoPathGenerator;
import io.github.jukomu.picacomic.core.internal.cache.CacheKey;
import io.github.jukomu.picacomic.core.internal.cache.CachePool;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.internal.constant.PicaConstants;
import io.github.jukomu.picacomic.core.internal.crypto.PicaCryptoTool;
import io.github.jukomu.picacomic.core.internal.net.ImageLocatorResolver;
import io.github.jukomu.picacomic.core.internal.net.OkHttpPicaImageRequest;
import io.github.jukomu.picacomic.core.internal.net.model.PicaResponse;
import io.github.jukomu.picacomic.core.internal.net.provider.PicaDomainManager;
import io.github.jukomu.picacomic.core.internal.strategy.impl.DefaultAlbumPathGenerator;
import io.github.jukomu.picacomic.core.internal.strategy.impl.DefaultImagePathGenerator;
import io.github.jukomu.picacomic.core.internal.strategy.impl.DefaultPhotoPathGenerator;
import io.github.jukomu.picacomic.core.internal.util.FileUtils;
import io.github.jukomu.picacomic.core.internal.util.JsonUtils;
import okhttp3.*;
import io.github.jukomu.picacomic.core.internal.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static io.github.jukomu.picacomic.core.internal.constant.PicaConstants.*;
import static io.github.jukomu.picacomic.core.internal.parser.PicaParser.*;

/**
 * Pica 公开 client 契约的包内实现。
 *
 * <p>该实现负责协调 API、图片请求、会话状态、缓存和下载资源；对外只通过
 * {@link IPicaClient} 暴露能力。</p>
 */
final class DefaultPicaClient implements IPicaClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPicaClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=UTF-8");

    @FunctionalInterface
    interface ImageFileWriter {
        /**
         * 将已下载的图片字节写入临时文件。
         *
         * @param path 临时文件路径
         * @param bytes 图片字节
         * @throws IOException 写入失败时抛出
         */
        void write(Path path, byte[] bytes) throws IOException;
    }

    @FunctionalInterface
    interface ImageFileMover {
        /**
         * 将临时文件移动到最终目标路径。
         *
         * @param temporary 临时文件路径
         * @param target 最终文件路径
         * @throws IOException 移动失败时抛出
         */
        void move(Path temporary, Path target) throws IOException;
    }

    /**
     * 请求生命周期传递的认证快照；该类型不属于公开 API。
     *
     * @param token 当前请求使用的 token
     * @param generation 会话代数
     * @param authenticated 请求是否使用已认证会话
     */
    record AuthContext(String token, long generation, boolean authenticated) {
    }

    private record AlbumCacheId(String albumId) {
    }

    private record PhotoCacheId(String albumId, String chapterId) {
    }

    private record FavoriteCacheId(long generation, String orderBy, int page) {
    }

    private record LoginContext(PicaSessionState previousState,
                                PicaUserInfo previousUser,
                                long generation,
                                CookieManager candidateCookies) {
    }

    private record LoginCommit(LoginContext context, long generation) {
    }

    private static final class LoginAttempt {
        private final AtomicReference<LoginCommit> committed = new AtomicReference<>();
    }

    /**
     * 一页章节图片及其稳定 ID、下一页页码。
     */
    private record PageData(String chapterId, List<PicaImage> images, Integer nextPage) {
    }

    /**
     * 完整章节页集合及其稳定章节 ID。
     */
    private record PhotoPages(String chapterId, List<PicaImage> images) {
    }

    /**
     * 一次本子刷新得到的快照及其代数，供后续定位和缓存提交沿用同一版本。
     */
    private record AlbumRefresh(PicaAlbum album, long generation) {
    }

    private static final class FirstPageFailure extends RuntimeException {
        private final PicaApiException failure;

        private FirstPageFailure(PicaApiException failure) {
            super(null, null, false, false);
            this.failure = failure;
        }
    }

    private static final class LoginInput {
        private String userNameOrEmail;
        private String password;

        private LoginInput(String userNameOrEmail, String password) {
            this.userNameOrEmail = userNameOrEmail;
            this.password = password;
        }

        private void clear() {
            userNameOrEmail = null;
            password = null;
        }
    }

    private <T> PicaRequestImpl<T> newRequest(PicaRequestImpl.Operation<T> operation) {
        return newRequest(operation, () -> {
        }, ignored -> {
        });
    }

    private <T> PicaRequestImpl<T> newRequest(PicaRequestImpl.Operation<T> operation,
                                              Runnable onReleased) {
        return newRequest(operation, onReleased, ignored -> {
        });
    }

    private <T> PicaRequestImpl<T> newRequest(PicaRequestImpl.Operation<T> operation,
                                              Runnable onReleased,
                                              Consumer<PicaApiException> onTerminalFailure) {
        AtomicReference<PicaRequestImpl<T>> reference = new AtomicReference<>();
        PicaRequestImpl<T> request = new PicaRequestImpl<>(operation, closed::get,
                startedRequest -> activeRequests.add(startedRequest),
                finishedRequest -> activeRequests.remove(finishedRequest),
                () -> {
                    requestHandles.remove(reference.get());
                    onReleased.run();
                }, onTerminalFailure);
        reference.set(request);
        requestHandles.add(request);
        return request;
    }

    private final PicaConfiguration config;
    private final OkHttpClient apiClient;
    private final OkHttpClient imageClient;
    private final OkHttpClient domainProbeClient;
    private final PicaDomainManager domainManager;
    private final CookieManager cookieManager;
    private final ExecutorService downloadExecutor;
    private final boolean ownsDownloadExecutor;
    private final CachePool<CacheKey, Object> cachePool;
    private final int concurrentPhotoDownloads;
    private final ImageQuality imageQuality;
    private final ImageLocatorResolver imageLocatorResolver;
    private final Semaphore readerSlots;
    private final ImageFileWriter imageFileWriter;
    private final ImageFileMover imageFileMover;
    private final Runnable afterLoginCommit;
    private final Runnable beforeCacheCommit;
    /** 用于测量 client 关闭总预算的单调时间源。 */
    private final LongSupplier nanoTime;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<PicaRequestImpl<?>> requestHandles = ConcurrentHashMap.newKeySet();
    private final Set<PicaRequestImpl<?>> activeRequests = ConcurrentHashMap.newKeySet();
    private final Set<OkHttpPicaImageRequest> imageRequests = ConcurrentHashMap.newKeySet();
    private final Set<Call> apiCalls = ConcurrentHashMap.newKeySet();
    private final Set<Call> imageCalls = ConcurrentHashMap.newKeySet();
    private final Set<Future<?>> batchFutures = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicLong> albumGenerations = new ConcurrentHashMap<>();
    private final Object sessionLock = new Object();

    private PicaSessionState sessionState = PicaSessionState.SIGNED_OUT;
    private PicaUserInfo sessionUser;
    private String token;
    private long authGeneration;

    DefaultPicaClient(PicaConfiguration config, OkHttpBuilder.HttpClientContext context) {
        this(config, context, DefaultPicaClient::writeImageFile, FileUtils::moveAtomically,
                () -> { }, () -> { }, System::nanoTime);
    }

    DefaultPicaClient(PicaConfiguration config,
                             OkHttpBuilder.HttpClientContext context,
                             ImageFileWriter imageFileWriter,
                             ImageFileMover imageFileMover) {
        this(config, context, imageFileWriter, imageFileMover, () -> { }, () -> { }, System::nanoTime);
    }

    DefaultPicaClient(PicaConfiguration config,
                             OkHttpBuilder.HttpClientContext context,
                             ImageFileWriter imageFileWriter,
                             ImageFileMover imageFileMover,
                             Runnable afterLoginCommit,
                             Runnable beforeCacheCommit) {
        this(config, context, imageFileWriter, imageFileMover, afterLoginCommit, beforeCacheCommit,
                System::nanoTime);
    }

    /**
     * 使用指定的单调时间源构造 client；其余构造器使用系统单调时钟。
     *
     * <p>时间源只用于测量关闭流程的已用时间，不参与 API 请求时间戳或签名。</p>
     */
    DefaultPicaClient(PicaConfiguration config,
                             OkHttpBuilder.HttpClientContext context,
                             ImageFileWriter imageFileWriter,
                             ImageFileMover imageFileMover,
                             Runnable afterLoginCommit,
                             Runnable beforeCacheCommit,
                             LongSupplier nanoTime) {
        this.config = Objects.requireNonNull(config, "Configuration cannot be null");
        Objects.requireNonNull(context, "HTTP client context cannot be null");
        this.imageFileWriter = Objects.requireNonNull(imageFileWriter, "Image file writer cannot be null");
        this.imageFileMover = Objects.requireNonNull(imageFileMover, "Image file mover cannot be null");
        this.afterLoginCommit = Objects.requireNonNull(afterLoginCommit, "Login commit callback cannot be null");
        this.beforeCacheCommit = Objects.requireNonNull(beforeCacheCommit, "Cache commit callback cannot be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "Nano time supplier cannot be null");
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
        this.imageQuality = config.getImageQuality();
        this.imageLocatorResolver = new ImageLocatorResolver();
        this.readerSlots = new Semaphore(config.getConcurrentImageDownloads(), true);
        this.domainManager.setProbe(this::probeDomain);
        this.domainManager.startPeriodicProbe(config.getDomainProbeIntervalMs());
    }

    // == 请求句柄工厂与同步便利方法 ==
    @Override
    public PicaRequest<PicaAlbum> newAlbumRequest(String albumId) {
        requireId(albumId, "Album ID");
        return newRequest(request -> executeAlbum(request, albumId, false));
    }

    @Override
    public PicaRequest<PicaAlbum> newAlbumRefreshRequest(String albumId) {
        requireId(albumId, "Album ID");
        return newRequest(request -> executeAlbum(request, albumId, true));
    }

    @Override
    public PicaRequest<PicaPhoto> newPhotoRequest(String albumId, String chapterId) {
        requireId(albumId, "Album ID");
        requireId(chapterId, "Chapter ID");
        return newRequest(request -> executePhotoById(request, albumId, chapterId, false));
    }

    @Override
    public PicaRequest<PicaPhoto> newPhotoRefreshRequest(String albumId, String chapterId) {
        requireId(albumId, "Album ID");
        requireId(chapterId, "Chapter ID");
        return newRequest(request -> executePhotoById(request, albumId, chapterId, true));
    }

    @Override
    public PicaRequest<PicaPhoto> newPhotoByOrderRequest(String albumId, int order) {
        requireId(albumId, "Album ID");
        if (order < 1) {
            throw new IllegalArgumentException("Chapter order must be positive");
        }
        return newRequest(request -> executePhotoByOrder(request, albumId, order));
    }

    @Override
    public PicaRequest<PicaContentPage> newSearchRequest(SearchQuery query) {
        validateSearchQuery(query, true);
        return newRequest(request -> executeSearch(request, query));
    }

    @Override
    public PicaRequest<PicaContentPage> newFavoritesRequest(SearchQuery query) {
        validateSearchQuery(query, false);
        return newRequest(request -> executeFavorites(request, query));
    }

    @Override
    public PicaRequest<PicaContentPage> newCategoriesRequest(SearchQuery query) {
        Objects.requireNonNull(query, "Query cannot be null");
        return newRequest(request -> executeCategories(request, query));
    }

    @Override
    public PicaRequest<PicaContentPage> newLeaderboardRequest(TimeOption timeOption) {
        Objects.requireNonNull(timeOption, "Time option cannot be null");
        return newRequest(request -> executeLeaderboard(request, timeOption));
    }

    @Override
    public PicaRequest<List<PicaUserInfo>> newKnightLeaderboardRequest() {
        return newRequest(this::executeKnightLeaderboard);
    }

    @Override
    public PicaRequest<PicaContentPage> newRandomAlbumsRequest() {
        return newRequest(this::executeRandomAlbums);
    }

    @Override
    public PicaRequest<PicaUserInfo> newUserInfoRequest() {
        return newRequest(this::executeUserInfo);
    }

    @Override
    public PicaRequest<PicaUserInfo> newLoginRequest(String userNameOrEmail, String password) {
        Objects.requireNonNull(userNameOrEmail, "Username or email cannot be null");
        Objects.requireNonNull(password, "Password cannot be null");
        LoginInput input = new LoginInput(userNameOrEmail, password);
        LoginAttempt attempt = new LoginAttempt();
        return newRequest(request -> {
            try {
                return executeLogin(request, input.userNameOrEmail, input.password, attempt);
            } finally {
                input.clear();
            }
        }, () -> {
            input.clear();
            attempt.committed.set(null);
        }, ignored -> rollbackCommittedLogin(attempt));
    }

    @Override
    public PicaAlbum getAlbum(String albumId) {
        return newAlbumRequest(albumId).execute();
    }

    @Override
    public PicaAlbum refreshAlbum(String albumId) {
        return newAlbumRefreshRequest(albumId).execute();
    }

    @Override
    public PicaPhoto getPhoto(String albumId, int order) {
        return newPhotoByOrderRequest(albumId, order).execute();
    }

    @Override
    public PicaPhoto getPhoto(String albumId, String chapterId) {
        return newPhotoRequest(albumId, chapterId).execute();
    }

    @Override
    public PicaPhoto refreshPhoto(String albumId, String chapterId) {
        return newPhotoRefreshRequest(albumId, chapterId).execute();
    }

    @Override
    public PicaContentPage search(SearchQuery query) {
        return newSearchRequest(query).execute();
    }

    @Override
    public PicaContentPage getFavorites(SearchQuery query) {
        return newFavoritesRequest(query).execute();
    }

    @Override
    public PicaContentPage getCategories(SearchQuery query) {
        return newCategoriesRequest(query).execute();
    }

    @Override
    public PicaContentPage getLeaderboard(TimeOption timeOption) {
        return newLeaderboardRequest(timeOption).execute();
    }

    @Override
    public List<PicaUserInfo> getKnightLeaderboard() {
        return newKnightLeaderboardRequest().execute();
    }

    @Override
    public PicaContentPage getRandomAlbums() {
        return newRandomAlbumsRequest().execute();
    }

    @Override
    public PicaUserInfo getUserInfo() {
        return newUserInfoRequest().execute();
    }

    @Override
    public PicaUserInfo login(String userNameOrEmail, String password) {
        return newLoginRequest(userNameOrEmail, password).execute();
    }

    @Override
    public PicaSessionSnapshot getSession() {
        synchronized (sessionLock) {
            if (closed.get()) {
                return new PicaSessionSnapshot(PicaSessionState.SIGNED_OUT, null);
            }
            PicaUserInfo publicUser = sessionState == PicaSessionState.SIGNED_IN
                    || sessionState == PicaSessionState.EXPIRED
                    ? PicaModelCopies.user(sessionUser) : null;
            return new PicaSessionSnapshot(sessionState, publicUser);
        }
    }

    @Override
    public void logout() {
        if (closed.get()) {
            return;
        }
        synchronized (sessionLock) {
            if (closed.get()) {
                return;
            }
            authGeneration++;
            sessionState = PicaSessionState.SIGNED_OUT;
            sessionUser = null;
            clearCredentialsLocked();
            cachePool.clear();
            albumGenerations.clear();
        }
        cancelActiveApiRequests();
    }

    @Override
    public void invalidateAlbum(String albumId) {
        requireId(albumId, "Album ID");
        synchronized (sessionLock) {
            if (closed.get()) {
                return;
            }
            albumGeneration(albumId).incrementAndGet();
            cachePool.remove(CacheKey.of(PicaAlbum.class, new AlbumCacheId(albumId)));
            removePhotoEntries(albumId);
        }
    }

    /**
     * 重新探测当前配置中的所有 API host。
     */
    void reprobeDomains() {
        if (!closed.get()) {
            domainManager.probeAllDomains(this::probeDomain);
        }
    }

    /**
     * 获取指定本子的章节摘要列表。
     */
    @SuppressWarnings("unchecked")
    List<PicaPhoto> getPhotoList(String albumId) {
        requireId(albumId, "Album ID");
        PicaRequestImpl<List<PicaPhoto>> request = newRequest(
                operation -> fetchPhotoSummaries(operation, albumId, captureAuth(false)));
        try {
            return request.execute();
        } finally {
            request.close();
        }
    }

    // == 本子与章节操作 ==

    private PicaAlbum executeAlbum(PicaRequestImpl<?> request, String albumId, boolean refresh) {
        request.checkBeforeWork();
        AuthContext auth = captureAuth(false);
        long albumGeneration = refresh
                ? advanceAlbumGeneration(albumId)
                : albumGenerationValue(albumId);
        if (!refresh) {
            PicaAlbum cached = cachedAlbum(albumId);
            if (cached != null) {
                request.checkBeforeWork();
                return cached;
            }
        }

        PicaAlbum album = fetchAlbumNetwork(request, albumId, auth);
        request.checkBeforeWork();
        commitAlbumIfCurrent(auth, albumId, albumGeneration, album, refresh);
        return PicaModelCopies.album(album);
    }

    private PicaAlbum fetchAlbumNetwork(PicaRequestImpl<?> request,
                                        String albumId,
                                        AuthContext auth) {
        List<PicaPhoto> photos = fetchPhotoSummaries(request, albumId, auth);
        request.checkBeforeWork();
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .build();
        PicaResponse response = executeGetRequest(request, apiClient, url, auth);
        JsonObject comic = dataObject(response, "comic");
        PicaAlbum album = parserAlbum(JsonUtils.toJsonString(comic), photos);
        validateAlbum(album);
        return album;
    }

    @SuppressWarnings("unchecked")
    private List<PicaPhoto> fetchPhotoSummaries(PicaRequestImpl<?> request,
                                                String albumId,
                                                AuthContext auth) {
        request.checkBeforeWork();
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .addPathSegment("eps")
                .addQueryParameter("page", "1")
                .build();
        PicaResponse response = executeGetRequest(request, apiClient, url, auth);
        JsonObject eps = dataObject(response, "eps");
        Object[] parsed = parserPhotoList(JsonUtils.toJsonString(eps), albumId);
        List<PicaPhoto> photos = new ArrayList<>((List<PicaPhoto>) parsed[0]);
        while (parsed[1] != null) {
            request.checkBeforeWork();
            HttpUrl pageUrl = newHttpUrlBuilder()
                    .addPathSegment("comics")
                    .addPathSegment(albumId)
                    .addPathSegment("eps")
                    .addQueryParameter("page", String.valueOf(parsed[1]))
                    .build();
            PicaResponse pageResponse = executeGetRequest(request, apiClient, pageUrl, auth);
            JsonObject pageEps = dataObject(pageResponse, "eps");
            parsed = parserPhotoList(JsonUtils.toJsonString(pageEps), albumId);
            photos.addAll((List<PicaPhoto>) parsed[0]);
        }
        return photos;
    }

    private PicaPhoto executePhotoById(PicaRequestImpl<?> request,
                                       String albumId,
                                       String chapterId,
                                       boolean refresh) {
        request.checkBeforeWork();
        AuthContext auth = captureAuth(false);
        long albumGeneration = albumGenerationValue(albumId);
        if (!refresh) {
            PicaPhoto cached = cachedPhoto(albumId, chapterId);
            if (cached != null) {
                request.checkBeforeWork();
                return cached;
            }
        }

        PicaAlbum album = cachedAlbumInternal(albumId);
        boolean refreshedAlbum = false;
        PicaPhoto summary = findPhoto(album, chapterId);
        if (summary == null) {
            AlbumRefresh refreshed = refreshAlbumForOperation(request, albumId, auth);
            album = refreshed.album();
            refreshedAlbum = true;
            albumGeneration = refreshed.generation();
            summary = findPhoto(album, chapterId);
            if (summary == null) {
                evictPhotoIfCurrent(auth, albumId, chapterId, albumGeneration);
                throw new PicaApiException(PicaApiException.Reason.NOT_FOUND);
            }
        }

        PhotoPages pages;
        for (;;) {
            try {
                pages = fetchPhotoPages(request, albumId, summary.order(), chapterId, auth);
                break;
            } catch (FirstPageFailure firstFailure) {
                if (refreshedAlbum) {
                    evictPhotoIfCurrent(auth, albumId, chapterId, albumGeneration);
                    throw firstFailure.failure;
                }
                AlbumRefresh refreshed = refreshAlbumForOperation(request, albumId, auth);
                album = refreshed.album();
                refreshedAlbum = true;
                albumGeneration = refreshed.generation();
                summary = findPhoto(album, chapterId);
                if (summary == null) {
                    evictPhotoIfCurrent(auth, albumId, chapterId, albumGeneration);
                    if (firstFailure.failure.getReason() == PicaApiException.Reason.HTTP_STATUS
                            && Integer.valueOf(404).equals(firstFailure.failure.getHttpStatus())) {
                        throw new PicaApiException(PicaApiException.Reason.NOT_FOUND);
                    }
                    throw new PicaApiException(PicaApiException.Reason.NOT_FOUND);
                }
            } catch (PicaApiException failure) {
                evictPhotoIfCurrent(auth, albumId, chapterId, albumGeneration);
                throw failure;
            }
        }

        PicaPhoto photo = buildPhoto(album, summary, pages.images());
        request.checkBeforeWork();
        commitPhotoIfCurrent(auth, albumId, albumGeneration, photo);
        return PicaModelCopies.photo(photo);
    }

    private PicaPhoto executePhotoByOrder(PicaRequestImpl<?> request,
                                          String albumId,
                                          int order) {
        request.checkBeforeWork();
        AuthContext auth = captureAuth(false);
        long albumGeneration = albumGenerationValue(albumId);
        PicaAlbum album = cachedAlbumInternal(albumId);
        PhotoPages pages;
        try {
            pages = fetchPhotoPages(request, albumId, order, null, auth);
        } catch (FirstPageFailure firstFailure) {
            throw firstFailure.failure;
        }

        PicaPhoto summary = findPhoto(album, pages.chapterId());
        if (album == null || summary == null || summary.order() != order) {
            AlbumRefresh refreshed = refreshAlbumForOperation(request, albumId, auth);
            album = refreshed.album();
            albumGeneration = refreshed.generation();
            summary = findPhoto(album, pages.chapterId());
            if (summary == null) {
                throw new PicaApiException(PicaApiException.Reason.STALE_RESOURCE);
            }
        }

        PicaPhoto photo = buildPhoto(album, summary, pages.images());
        request.checkBeforeWork();
        commitPhotoIfCurrent(auth, albumId, albumGeneration, photo);
        return PicaModelCopies.photo(photo);
    }

    @SuppressWarnings("unchecked")
    private PhotoPages fetchPhotoPages(PicaRequestImpl<?> request,
                                       String albumId,
                                       int order,
                                       String expectedChapterId,
                                       AuthContext auth) {
        request.checkBeforeWork();
        PicaResponse response;
        try {
            response = executeGetRequest(request, apiClient, photoPageUrl(albumId, order, 1), auth);
        } catch (PicaApiException failure) {
            if (failure.getReason() == PicaApiException.Reason.STALE_RESOURCE
                    || (failure.getReason() == PicaApiException.Reason.HTTP_STATUS
                    && Integer.valueOf(404).equals(failure.getHttpStatus()))) {
                throw new FirstPageFailure(failure);
            }
            throw failure;
        }

        PageData first = parsePage(response);
        if (first.chapterId() == null || first.chapterId().isBlank()
                || (expectedChapterId != null && !expectedChapterId.equals(first.chapterId()))) {
            throw new FirstPageFailure(new PicaApiException(PicaApiException.Reason.STALE_RESOURCE));
        }

        String chapterId = first.chapterId();
        List<PicaImage> images = new ArrayList<>(first.images());
        Integer nextPage = first.nextPage();
        while (nextPage != null) {
            request.checkBeforeWork();
            PicaResponse pageResponse = executeGetRequest(
                    request, apiClient, photoPageUrl(albumId, order, nextPage), auth);
            PageData page = parsePage(pageResponse);
            if (!chapterId.equals(page.chapterId())) {
                throw new PicaApiException(PicaApiException.Reason.STALE_RESOURCE);
            }
            images.addAll(page.images());
            nextPage = page.nextPage();
        }
        return new PhotoPages(chapterId, images);
    }

    @SuppressWarnings("unchecked")
    private PageData parsePage(PicaResponse response) {
        JsonObject data = dataObject(response, "pages");
        JsonObject responseData = responseData(response);
        JsonElement epValue = responseData.get("ep");
        if (epValue == null || epValue.isJsonNull() || !epValue.isJsonObject()) {
            throw new PicaApiException(PicaApiException.Reason.STALE_RESOURCE);
        }
        JsonObject epElement = epValue.getAsJsonObject();
        JsonElement idElement = epElement.get("_id");
        String chapterId = idElement == null || idElement.isJsonNull()
                ? null : scalarString(idElement);
        if (chapterId == null || chapterId.isBlank()) {
            throw new PicaApiException(PicaApiException.Reason.STALE_RESOURCE);
        }
        Object[] parsed = parserImageList(JsonUtils.toJsonString(data));
        return new PageData(chapterId, new ArrayList<>((List<PicaImage>) parsed[0]), (Integer) parsed[1]);
    }

    private static HttpUrl photoPageUrl(String albumId, int order, int page) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(PicaConstants.PLACEHOLDER_HOST)
                .addPathSegment("comics")
                .addPathSegment(albumId)
                .addPathSegment("order")
                .addPathSegment(String.valueOf(order))
                .addPathSegment("pages")
                .addQueryParameter("page", String.valueOf(page))
                .build();
    }

    private AlbumRefresh refreshAlbumForOperation(PicaRequestImpl<?> request,
                                                  String albumId,
                                                  AuthContext auth) {
        long generation = advanceAlbumGeneration(albumId);
        PicaAlbum album = fetchAlbumNetwork(request, albumId, auth);
        request.checkBeforeWork();
        commitAlbumIfCurrent(auth, albumId, generation, album, true);
        return new AlbumRefresh(album, generation);
    }

    private PicaPhoto buildPhoto(PicaAlbum album, PicaPhoto summary, List<PicaImage> images) {
        return new PicaPhoto(
                album.id(),
                summary.id(),
                summary.title(),
                summary.updatedAt(),
                summary.order(),
                new ArrayList<>(images),
                album.isSingleAlbum());
    }

    private static PicaPhoto findPhoto(PicaAlbum album, String chapterId) {
        if (album == null || album.photos() == null) {
            return null;
        }
        for (PicaPhoto photo : album.photos()) {
            if (photo != null && chapterId.equals(photo.id())) {
                return photo;
            }
        }
        return null;
    }

    private void validateAlbum(PicaAlbum album) {
        if (album == null || album.id() == null || album.id().isBlank()
                || album.photos() == null || album.photos().isEmpty()) {
            throw new PicaApiException(PicaApiException.Reason.STALE_RESOURCE);
        }
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (PicaPhoto photo : album.photos()) {
            if (photo == null || photo.id() == null || photo.id().isBlank()
                    || !ids.add(photo.id())) {
                throw new PicaApiException(PicaApiException.Reason.STALE_RESOURCE);
            }
        }
    }

    // == 其他 API 操作 ==

    private PicaContentPage executeSearch(PicaRequestImpl<?> request, SearchQuery query) {
        AuthContext auth = captureAuth(false);
        request.checkBeforeWork();
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
        PicaResponse response = executePostRequest(request, apiClient, url,
                RequestBody.create(JsonUtils.toJsonString(params), JSON_MEDIA_TYPE), auth);
        PicaContentPage page = parserContentPage(JsonUtils.toJsonString(dataObject(response, "comics")));
        request.checkBeforeWork();
        return PicaModelCopies.contentPage(page);
    }

    private PicaContentPage executeFavorites(PicaRequestImpl<?> request, SearchQuery query) {
        AuthContext auth = captureAuth(true);
        PicaContentPage cached = cachedFavoritePage(query, auth.generation());
        if (cached != null) {
            request.checkBeforeWork();
            return cached;
        }
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("users")
                .addPathSegment("favourite")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .addQueryParameter("s", query.getOrderBy().getValue())
                .build();
        PicaResponse response = executeGetRequest(request, apiClient, url, auth);
        PicaContentPage page = parserContentPage(JsonUtils.toJsonString(dataObject(response, "comics")));
        request.checkBeforeWork();
        commitFavoritePageIfCurrent(auth, page, query);
        return PicaModelCopies.contentPage(page);
    }

    private PicaContentPage executeCategories(PicaRequestImpl<?> request, SearchQuery query) {
        AuthContext auth = captureAuth(false);
        HttpUrl.Builder url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addQueryParameter("page", String.valueOf(query.getPage()))
                .addQueryParameter("s", query.getOrderBy().getValue());
        if (query.getCategories() != null && !query.getCategories().isEmpty()) {
            url.addQueryParameter("c", query.getCategories().get(0).getValue());
        }
        if (Strings.isNotBlank(query.getTag())) {
            url.addQueryParameter("t", query.getTag());
        }
        if (Strings.isNotBlank(query.getAuthor())) {
            url.addQueryParameter("a", query.getAuthor());
        }
        if (Strings.isNotBlank(query.getTranslator())) {
            url.addQueryParameter("ct", query.getTranslator());
        }
        if (Strings.isNotBlank(query.getCreator())) {
            url.addQueryParameter("ca", query.getCreator());
        }
        PicaResponse response = executeGetRequest(request, apiClient, url.build(), auth);
        return PicaModelCopies.contentPage(
                parserContentPage(JsonUtils.toJsonString(dataObject(response, "comics"))));
    }

    private PicaContentPage executeLeaderboard(PicaRequestImpl<?> request, TimeOption timeOption) {
        AuthContext auth = captureAuth(false);
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment("leaderboard")
                .addQueryParameter("tt", timeOption.getValue())
                .addQueryParameter("ct", "VC")
                .build();
        PicaResponse response = executeGetRequest(request, apiClient, url, auth);
        JsonArray array = dataArray(response, "comics");
        List<PicaAlbum> albums = new ArrayList<>();
        for (JsonElement element : array) {
            albums.add(parserAlbum(JsonUtils.toJsonString(element), null));
        }
        return PicaModelCopies.contentPage(new PicaContentPage(1, 1, 40, 40, albums));
    }

    private List<PicaUserInfo> executeKnightLeaderboard(PicaRequestImpl<?> request) {
        AuthContext auth = captureAuth(false);
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment("knight-leaderboard")
                .build();
        PicaResponse response = executeGetRequest(request, apiClient, url, auth);
        JsonArray array = dataArray(response, "users");
        List<PicaUserInfo> users = new ArrayList<>();
        for (JsonElement element : array) {
            users.add(parserUserInfo(JsonUtils.toJsonString(element)));
        }
        return PicaModelCopies.users(users);
    }

    private PicaContentPage executeRandomAlbums(PicaRequestImpl<?> request) {
        AuthContext auth = captureAuth(false);
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("comics")
                .addPathSegment("random")
                .build();
        PicaResponse response = executeGetRequest(request, apiClient, url, auth);
        JsonArray array = dataArray(response, "comics");
        List<PicaAlbum> albums = new ArrayList<>();
        for (JsonElement element : array) {
            albums.add(parserAlbum(JsonUtils.toJsonString(element), null));
        }
        return PicaModelCopies.contentPage(new PicaContentPage(1, 1, 20, 20, albums));
    }

    private PicaUserInfo executeUserInfo(PicaRequestImpl<?> request) {
        AuthContext auth = captureAuth(true);
        HttpUrl url = newHttpUrlBuilder()
                .addPathSegment("users")
                .addPathSegment("profile")
                .build();
        PicaResponse response = executeGetRequest(request, apiClient, url, auth);
        PicaUserInfo user = parserUserInfo(JsonUtils.toJsonString(dataObject(response, "user")));
        requireUserIdentity(user);
        request.checkBeforeWork();
        synchronized (sessionLock) {
            if (!closed.get() && sessionState == PicaSessionState.SIGNED_IN
                    && authGeneration == auth.generation()) {
                sessionUser = PicaModelCopies.user(user);
            }
        }
        return PicaModelCopies.user(user);
    }

    private PicaUserInfo executeLogin(PicaRequestImpl<?> request,
                                      String userNameOrEmail,
                                      String password,
                                      LoginAttempt attempt) {
        request.checkBeforeWork();
        LoginContext login = beginLogin();
        CookieManager candidateCookies = login.candidateCookies();
        OkHttpClient candidateClient = apiClient.newBuilder()
                .cookieJar(new JavaNetCookieJar(candidateCookies))
                .build();
        try {
            HttpUrl signInUrl = newHttpUrlBuilder()
                    .addPathSegment("auth")
                    .addPathSegment("sign-in")
                    .build();
            Map<String, String> credentials = new HashMap<>();
            credentials.put("email", userNameOrEmail);
            credentials.put("password", password);
            PicaResponse signIn = executePostRequest(
                    request,
                    candidateClient,
                    signInUrl,
                    RequestBody.create(JsonUtils.toJsonString(credentials), JSON_MEDIA_TYPE),
                    new AuthContext(null, login.generation(), false));
            String candidateToken = extractToken(signIn);
            request.checkBeforeWork();

            HttpUrl profileUrl = newHttpUrlBuilder()
                    .addPathSegment("users")
                    .addPathSegment("profile")
                    .build();
            PicaResponse profile = executeGetRequest(
                    request,
                    candidateClient,
                    profileUrl,
                    new AuthContext(candidateToken, login.generation(), false));
            PicaUserInfo user = parserUserInfo(JsonUtils.toJsonString(dataObject(profile, "user")));
            requireUserIdentity(user);
            request.checkBeforeWork();
            commitLogin(login, candidateToken, candidateCookies, user, attempt);
            return PicaModelCopies.user(user);
        } catch (RuntimeException exception) {
            rollbackLogin(attempt, login);
            throw exception;
        } finally {
            candidateCookies.getCookieStore().removeAll();
        }
    }

    private LoginContext beginLogin() {
        synchronized (sessionLock) {
            if (closed.get()) {
                throw new PicaApiException(PicaApiException.Reason.CLIENT_CLOSED);
            }
            if (sessionState == PicaSessionState.SIGNED_IN
                    || sessionState == PicaSessionState.AUTHENTICATING) {
                throw new IllegalStateException("Login requires a signed-out or expired session");
            }
            PicaSessionState previousState = sessionState;
            PicaUserInfo previousUser = sessionUser;
            authGeneration++;
            sessionState = PicaSessionState.AUTHENTICATING;
            sessionUser = null;
            clearCredentialsLocked();
            cachePool.clear();
            CookieManager candidate = new CookieManager();
            candidate.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
            return new LoginContext(previousState, previousUser, authGeneration, candidate);
        }
    }

    private void commitLogin(LoginContext login,
                             String candidateToken,
                             CookieManager candidateCookies,
                             PicaUserInfo user,
                             LoginAttempt attempt) {
        synchronized (sessionLock) {
            if (closed.get()) {
                throw new PicaApiException(PicaApiException.Reason.CLIENT_CLOSED);
            }
            if (authGeneration != login.generation() || sessionState != PicaSessionState.AUTHENTICATING) {
                throw new PicaApiException(PicaApiException.Reason.CANCELLED);
            }
            copyCookies(candidateCookies);
            sessionUser = PicaModelCopies.user(user);
            sessionState = PicaSessionState.SIGNED_IN;
            // token 不会出现在公开 model 或异常中。
            token = candidateToken;
            attempt.committed.set(new LoginCommit(login, login.generation()));
        }
        afterLoginCommit.run();
    }

    private void rollbackLogin(LoginAttempt attempt, LoginContext login) {
        LoginCommit committed = attempt.committed.getAndSet(null);
        if (committed != null) {
            rollbackCommittedLogin(committed);
            return;
        }
        synchronized (sessionLock) {
            if (authGeneration != login.generation() || sessionState != PicaSessionState.AUTHENTICATING) {
                return;
            }
            sessionState = login.previousState();
            sessionUser = PicaModelCopies.user(login.previousUser());
            clearCredentialsLocked();
            cachePool.clear();
            albumGenerations.clear();
        }
    }

    private void rollbackCommittedLogin(LoginAttempt attempt) {
        LoginCommit committed = attempt.committed.getAndSet(null);
        if (committed != null) {
            rollbackCommittedLogin(committed);
        }
    }

    private void rollbackCommittedLogin(LoginCommit committed) {
        LoginContext login = committed.context();
        synchronized (sessionLock) {
            if (authGeneration != committed.generation()
                    || sessionState != PicaSessionState.SIGNED_IN) {
                return;
            }
            authGeneration++;
            if (closed.get()) {
                sessionState = PicaSessionState.SIGNED_OUT;
                sessionUser = null;
            } else {
                sessionState = login.previousState();
                sessionUser = PicaModelCopies.user(login.previousUser());
            }
            clearCredentialsLocked();
            cachePool.clear();
            albumGenerations.clear();
        }
    }

    private void copyCookies(CookieManager candidate) {
        cookieManager.getCookieStore().removeAll();
        URI fallback = URI.create("https://" + domainManager.snapshotInPriorityOrder().get(0));
        for (HttpCookie cookie : candidate.getCookieStore().getCookies()) {
            String domain = cookie.getDomain();
            URI uri = domain == null || domain.isBlank()
                    ? fallback
                    : URI.create("https://" + (domain.startsWith(".") ? domain.substring(1) : domain));
            cookieManager.getCookieStore().add(uri, cookie);
        }
    }

    private String extractToken(PicaResponse response) {
        JsonObject data = responseData(response);
        JsonElement tokenElement = data.get("token");
        String value = tokenElement == null || tokenElement.isJsonNull() ? null : scalarString(tokenElement);
        if (value == null || value.isBlank()) {
            throw ParseResponseException.withHttpStatus(response.getHttpCode(), null);
        }
        return value;
    }

    private void requireUserIdentity(PicaUserInfo user) {
        if (user == null || user.id() == null || user.id().isBlank()) {
            throw ParseResponseException.withHttpStatus(null, null);
        }
    }

    // == 缓存与会话辅助方法 ==

    private PicaAlbum cachedAlbum(String albumId) {
        return PicaModelCopies.album(cachedAlbumInternal(albumId));
    }

    private PicaAlbum cachedAlbumInternal(String albumId) {
        return (PicaAlbum) cachePool.get(CacheKey.of(PicaAlbum.class, new AlbumCacheId(albumId)));
    }

    private PicaPhoto cachedPhoto(String albumId, String chapterId) {
        return PicaModelCopies.photo((PicaPhoto) cachePool.get(
                CacheKey.of(PicaPhoto.class, new PhotoCacheId(albumId, chapterId))));
    }

    private PicaContentPage cachedFavoritePage(SearchQuery query, long generation) {
        return PicaModelCopies.contentPage((PicaContentPage) cachePool.get(
                CacheKey.of(PicaContentPage.class,
                        new FavoriteCacheId(generation, query.getOrderBy().getValue(), query.getPage()))));
    }

    private void cacheAlbumInternal(PicaAlbum album) {
        cachePool.put(CacheKey.of(PicaAlbum.class, new AlbumCacheId(album.id())),
                PicaModelCopies.album(album));
    }

    private void cachePhotoInternal(PicaPhoto photo) {
        cachePool.put(CacheKey.of(PicaPhoto.class, new PhotoCacheId(photo.albumId(), photo.id())),
                PicaModelCopies.photo(photo));
    }

    private void cacheFavoritePageInternal(PicaContentPage page, SearchQuery query, long generation) {
        cachePool.put(CacheKey.of(PicaContentPage.class,
                        new FavoriteCacheId(generation, query.getOrderBy().getValue(), query.getPage())),
                PicaModelCopies.contentPage(page));
    }

    /**
     * 校验捕获的会话与本子代数，并在同一个临界区发布本子快照。
     *
     * <p>校验、缓存写入和按需清理章节缓存与所有失效路径共享同一把锁。</p>
     */
    private boolean commitAlbumIfCurrent(AuthContext auth,
                                         String albumId,
                                         long generation,
                                         PicaAlbum album,
                                         boolean clearPhotos) {
        synchronized (sessionLock) {
            if (!isCurrentLocked(auth, albumId, generation)) {
                return false;
            }
            beforeCacheCommit.run();
            cacheAlbumInternal(album);
            if (clearPhotos) {
                removePhotoEntries(albumId);
            }
            return true;
        }
    }

    /**
     * 仅在会话与本子代数仍然有效时发布完整章节。
     *
     * <p>缓存写入与代数校验共享 {@code sessionLock}，避免旧请求在失效后重新填充缓存。</p>
     */
    private boolean commitPhotoIfCurrent(AuthContext auth,
                                         String albumId,
                                         long generation,
                                         PicaPhoto photo) {
        synchronized (sessionLock) {
            if (!isCurrentLocked(auth, albumId, generation)) {
                return false;
            }
            beforeCacheCommit.run();
            cachePhotoInternal(photo);
            return true;
        }
    }

    /**
     * 仅在认证会话代数仍然有效时发布收藏分页。
     */
    private boolean commitFavoritePageIfCurrent(AuthContext auth,
                                                 PicaContentPage page,
                                                 SearchQuery query) {
        synchronized (sessionLock) {
            if (!isAuthCurrentLocked(auth)) {
                return false;
            }
            beforeCacheCommit.run();
            cacheFavoritePageInternal(page, query, auth.generation());
            return true;
        }
    }

    private void evictPhotoIfCurrent(AuthContext auth,
                                     String albumId,
                                     String chapterId,
                                     long generation) {
        synchronized (sessionLock) {
            if (isCurrentLocked(auth, albumId, generation)) {
                cachePool.remove(CacheKey.of(PicaPhoto.class, new PhotoCacheId(albumId, chapterId)));
            }
        }
    }

    private void removePhotoEntries(String albumId) {
        cachePool.removeIf(key -> key.type() == PicaPhoto.class
                && key.id() instanceof PhotoCacheId photoKey
                && albumId.equals(photoKey.albumId()));
    }

    private AtomicLong albumGeneration(String albumId) {
        return albumGenerations.computeIfAbsent(albumId, ignored -> new AtomicLong());
    }

    private long albumGenerationValue(String albumId) {
        synchronized (sessionLock) {
            return albumGeneration(albumId).get();
        }
    }

    private long advanceAlbumGeneration(String albumId) {
        synchronized (sessionLock) {
            return albumGeneration(albumId).incrementAndGet();
        }
    }

    private AuthContext captureAuth(boolean required) {
        synchronized (sessionLock) {
            if (closed.get()) {
                throw new PicaApiException(PicaApiException.Reason.CLIENT_CLOSED);
            }
            if (required && sessionState != PicaSessionState.SIGNED_IN) {
                throw new PicaApiException(PicaApiException.Reason.SESSION_REQUIRED);
            }
            return new AuthContext(token, authGeneration, sessionState == PicaSessionState.SIGNED_IN);
        }
    }

    private boolean isAuthCurrentLocked(AuthContext auth) {
        return !closed.get() && authGeneration == auth.generation()
                && sessionState == PicaSessionState.SIGNED_IN;
    }

    private boolean isCurrentLocked(AuthContext auth, String albumId, long generation) {
        return !closed.get() && authGeneration == auth.generation()
                && albumGeneration(albumId).get() == generation;
    }

    private void expireSessionIfCurrent(AuthContext auth) {
        synchronized (sessionLock) {
            if (closed.get() || !auth.authenticated() || authGeneration != auth.generation()
                    || sessionState != PicaSessionState.SIGNED_IN) {
                return;
            }
            authGeneration++;
            sessionState = PicaSessionState.EXPIRED;
            clearCredentialsLocked();
            cachePool.clear();
            albumGenerations.clear();
        }
    }

    private void clearCredentialsLocked() {
        token = null;
        cookieManager.getCookieStore().removeAll();
    }

    private void cancelActiveApiRequests() {
        for (PicaRequestImpl<?> request : activeRequests) {
            request.cancelFromLogout();
        }
    }

    // == API 传输 ==

    private PicaResponse executeGetRequest(PicaRequestImpl<?> request,
                                           OkHttpClient client,
                                           HttpUrl url,
                                           AuthContext auth) {
        Map<String, String> headers = buildHeaders(url, "GET", auth.token(), imageQuality.getValue());
        Request httpRequest = addAppHeaders(new Request.Builder().url(url).get(), headers).build();
        return executeRequest(request, client, httpRequest, auth);
    }

    private PicaResponse executePostRequest(PicaRequestImpl<?> request,
                                            OkHttpClient client,
                                            HttpUrl url,
                                            RequestBody body,
                                            AuthContext auth) {
        Map<String, String> headers = buildHeaders(url, "POST", auth.token(), imageQuality.getValue());
        Request httpRequest = addAppHeaders(new Request.Builder().url(url).post(body), headers).build();
        return executeRequest(request, client, httpRequest, auth);
    }

    private PicaResponse executeRequest(PicaRequestImpl<?> request,
                                        OkHttpClient client,
                                        Request httpRequest,
                                        AuthContext auth) {
        request.checkBeforeWork();
        Call call = client.newCall(httpRequest);
        registerApiCall(call);
        request.attachCall(call);
        try (Response response = call.execute()) {
            PicaResponse picaResponse = new PicaResponse(response);
            try {
                picaResponse.requireSuccess();
            } catch (PicaApiException failure) {
                if (auth.authenticated()
                        && failure.getReason() == PicaApiException.Reason.HTTP_STATUS
                        && Integer.valueOf(401).equals(failure.getHttpStatus())
                        && !request.isCancelled()) {
                    expireSessionIfCurrent(auth);
                }
                throw failure;
            }
            request.checkBeforeWork();
            return picaResponse;
        } catch (PicaApiException failure) {
            throw request.effective(failure);
        } catch (IOException exception) {
            throw request.failureForIOException(exception);
        } finally {
            request.detachCall(call);
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

    private boolean probeDomain(String domain) {
        if (closed.get()) {
            return false;
        }
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host(domain)
                .build();
        Call call = domainProbeClient.newCall(new Request.Builder().url(url).head().build());
        try {
            registerApiCall(call);
            try (Response response = call.execute()) {
                return response.code() < 500;
            }
        } catch (IOException | PicaApiException exception) {
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
     * 构造 API 请求所需的应用 headers 和签名。
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
            throw new PicaApiException(PicaApiException.Reason.CLIENT_CLOSED);
        }
        apiCalls.add(call);
        if (closed.get()) {
            apiCalls.remove(call);
            call.cancel();
            throw new PicaApiException(PicaApiException.Reason.CLIENT_CLOSED);
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

    // == 图片请求与下载便利方法 ==

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
        downloadImage(new PicaImage("", "", "", imageUrl), path);
    }

    @Override
    public void downloadImage(PicaImage image, IImagePathGenerator imagePathGenerator) throws IOException {
        Objects.requireNonNull(imagePathGenerator, "Image path generator cannot be null");
        downloadImage(image, Objects.requireNonNull(imagePathGenerator.generatePath(image),
                "Image path generator returned null"));
    }

    @Override
    public void downloadImage(PicaImage image, Path path) throws IOException {
        ensureDownloadOpen();
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
        ensureDownloadOpen();
        return downloadPhoto(photo, new DefaultPhotoPathGenerator());
    }

    @Override
    public DownloadResult downloadPhoto(PicaPhoto photo, IPhotoPathGenerator pathGenerator) {
        ensureDownloadOpen();
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
        ensureDownloadOpen();
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
        ensureDownloadOpen();
        Objects.requireNonNull(photo, "Photo cannot be null");
        Objects.requireNonNull(path, "Photo path cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");
        return awaitImageBatch(submitImageBatch(photo, path, executor));
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album) {
        ensureDownloadOpen();
        return downloadAlbum(album, new DefaultAlbumPathGenerator());
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, IAlbumPathGenerator pathGenerator) {
        ensureDownloadOpen();
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
        ensureDownloadOpen();
        Objects.requireNonNull(pathGenerator, "Album path generator cannot be null");
        Path path = Objects.requireNonNull(pathGenerator.generatePath(album),
                "Album path generator returned null");
        return downloadAlbum(album, path, executor);
    }

    @Override
    public DownloadResult downloadAlbum(PicaAlbum album, Path path, ExecutorService executor) {
        ensureDownloadOpen();
        Objects.requireNonNull(album, "Album cannot be null");
        Objects.requireNonNull(path, "Album path cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");

        List<ImageBatch> active = new ArrayList<>();
        List<Path> successful = new ArrayList<>();
        Map<PicaImage, Exception> failed = new HashMap<>();
        List<PicaPhoto> summaries = album.getPhotos() == null ? List.of() : album.getPhotos();

        for (PicaPhoto summary : summaries) {
            ensureDownloadOpen();
            PicaPhoto fullPhoto = summary;
            if (summary.getImages() == null || summary.getImages().isEmpty()) {
                fullPhoto = getPhoto(summary.getAlbumId(), summary.getId());
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
                        throw new ImageFetchException(ImageFetchException.Reason.CLIENT_CLOSED);
                    }
                    try {
                        downloadImage(image, target);
                        return target;
                    } catch (Exception exception) {
                        batch.failed.put(image, closed.get()
                                ? new ImageFetchException(ImageFetchException.Reason.CLIENT_CLOSED)
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
                        ? new ImageFetchException(ImageFetchException.Reason.CLIENT_CLOSED)
                        : new ImageFetchException(ImageFetchException.Reason.CANCELLED));
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

    // == 响应辅助方法与校验 ==

    private static JsonObject responseData(PicaResponse response) {
        try {
            JsonObject data = JsonUtils.toJsonObject(response.getData());
            if (data == null) {
                throw ParseResponseException.withHttpStatus(response.getHttpCode(), null);
            }
            return data;
        } catch (PicaApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ParseResponseException.withHttpStatus(response.getHttpCode(), exception);
        }
    }

    private static JsonObject dataObject(PicaResponse response, String member) {
        JsonElement value = responseData(response).get(member);
        if (value == null || value.isJsonNull() || !value.isJsonObject()) {
            throw ParseResponseException.withHttpStatus(response.getHttpCode(), null);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray dataArray(PicaResponse response, String member) {
        JsonElement value = responseData(response).get(member);
        if (value == null || value.isJsonNull() || !value.isJsonArray()) {
            throw ParseResponseException.withHttpStatus(response.getHttpCode(), null);
        }
        return value.getAsJsonArray();
    }

    private static String scalarString(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || element.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void validateSearchQuery(SearchQuery query, boolean requiresKeyword) {
        Objects.requireNonNull(query, "Query cannot be null");
        if (requiresKeyword) {
            Objects.requireNonNull(query.getKeyword(), "Keyword cannot be null");
        }
    }

    private static void requireId(String id, String name) {
        if (id == null) {
            throw new NullPointerException(name + " cannot be null");
        }
        if (id.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    // == 资源生命周期 ==

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long start = nanoTime.getAsLong();

        synchronized (sessionLock) {
            authGeneration++;
            sessionState = PicaSessionState.SIGNED_OUT;
            sessionUser = null;
            clearCredentialsLocked();
            cachePool.clear();
            albumGenerations.clear();
        }
        for (PicaRequestImpl<?> request : requestHandles) {
            request.closeFromClient();
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

        if (ownsDownloadExecutor) {
            downloadExecutor.shutdown();
            // 使用时间差而非绝对 deadline，允许单调时钟跨越 long 的符号边界。
            long elapsed = nanoTime.getAsLong() - start;
            long remaining = closeTimeoutNanos(config.getCloseTimeoutMs()) - elapsed;
            if (remaining > 0) {
                try {
                    if (!downloadExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                        downloadExecutor.shutdownNow();
                    }
                } catch (InterruptedException exception) {
                    downloadExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            } else {
                downloadExecutor.shutdownNow();
            }
        }
        closeHttpClient(apiClient);
        closeHttpClient(imageClient);
    }

    private static long closeTimeoutNanos(long timeoutMs) {
        try {
            return Math.multiplyExact(timeoutMs, 1_000_000L);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
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
