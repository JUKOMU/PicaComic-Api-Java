package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.enums.PicaSessionState;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.model.PicaContentPage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.api.model.PicaSessionSnapshot;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class U2ContractTest {

    @Test
    void apiErrorsAreStructuredAndDoNotExposeResponseText() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(new MockResponse().setResponseCode(429)
                    .setHeader("Retry-After", "3")
                    .setBody("secret-marker https://private.test token=password"));

            PicaApiException failure = assertThrows(PicaApiException.class,
                    () -> client.getCategories(new SearchQuery.Builder().build()));
            assertEquals(PicaApiException.Reason.HTTP_STATUS, failure.getReason());
            assertEquals(429, failure.getHttpStatus());
            assertEquals(Duration.ofSeconds(3), failure.getRetryAfter());
            assertFalse(failure.getMessage().contains("secret-marker"));
            assertFalse(failure.toString().contains("private.test"));
            assertFalse(String.valueOf(failure.getCause()).contains("password"));
        }
    }

    @Test
    void successfulHttpWithProviderFailureAndMalformedPayloadHaveDistinctReasons() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"error\":\"PROVIDER_CODE\",\"message\":\"secret\"}"));
            PicaApiException provider = assertThrows(PicaApiException.class,
                    () -> client.getCategories(new SearchQuery.Builder().build()));
            assertEquals(PicaApiException.Reason.PROVIDER, provider.getReason());
            assertEquals("PROVIDER_CODE", provider.getProviderCode());

            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("not-json-secret-marker"));
            PicaApiException malformed = assertThrows(PicaApiException.class,
                    () -> client.getCategories(new SearchQuery.Builder().build()));
            assertEquals(PicaApiException.Reason.PARSE, malformed.getReason());
            assertFalse(malformed.toString().contains("secret-marker"));
        }
    }

    @Test
    void networkAndTimeoutFailuresUseStructuredNetworkReasons() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            PicaApiException network = assertThrows(PicaApiException.class,
                    () -> client.getCategories(new SearchQuery.Builder().build()));
            assertEquals(PicaApiException.Reason.NETWORK, network.getReason());
            assertTrue(network instanceof io.github.jukomu.picacomic.api.exception.NetworkException);
        }
    }

    @Test
    void loginIsAtomicAndSessionCopiesAreIsolated() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());
            fixture.server.enqueue(probe());
            fixture.server.enqueue(signIn());
            fixture.server.enqueue(profile("user-id", "fixture-user"));

            assertEquals("user-id", client.login("fixture-user", "fixture-password").id());
            PicaSessionSnapshot first = client.getSession();
            assertEquals(PicaSessionState.SIGNED_IN, first.state());
            assertEquals("user-id", first.user().id());
            first.user().getCharacters().add("mutated");
            assertTrue(client.getSession().user().getCharacters().isEmpty());

            client.logout();
            assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());
            fixture.server.enqueue(signIn());
            fixture.server.enqueue(profile("user-id-2", "fixture-user-2"));
            assertEquals("user-id-2", client.login("fixture-user-2", "fixture-password").id());
        }
    }

    @Test
    void final401ExpiresTheSessionBut403DoesNot() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(signIn());
            fixture.server.enqueue(profile("user-id", "fixture-user"));
            client.login("fixture-user", "fixture-password");

            fixture.server.enqueue(new MockResponse().setResponseCode(401)
                    .setBody("expired-secret"));
            PicaApiException expired = assertThrows(PicaApiException.class, client::getUserInfo);
            assertEquals(PicaApiException.Reason.HTTP_STATUS, expired.getReason());
            assertEquals(PicaSessionState.EXPIRED, client.getSession().state());
            assertEquals("user-id", client.getSession().user().id());

            fixture.server.enqueue(signIn());
            fixture.server.enqueue(profile("user-id", "fixture-user"));
            client.login("fixture-user", "fixture-password");
            fixture.server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
            PicaApiException forbidden = assertThrows(PicaApiException.class, client::getUserInfo);
            assertEquals(PicaApiException.Reason.HTTP_STATUS, forbidden.getReason());
            assertEquals(PicaSessionState.SIGNED_IN, client.getSession().state());
        }
    }

    @Test
    void apiRequestCancellationDoesNotStartASecondEndpointCall() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeadersDelay(2, TimeUnit.SECONDS)
                    .setBody("{\"data\":{\"comics\":{\"docs\":[],\"page\":1,\"pages\":1,\"total\":0}}}"));
            PicaRequest<PicaContentPage> request = client.newCategoriesRequest(
                    new SearchQuery.Builder().build());
            Future<PicaContentPage> future = executor.submit(request::execute);
            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
            request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PicaApiException);
            assertEquals(PicaApiException.Reason.CANCELLED,
                    ((PicaApiException) failure.getCause()).getReason());
            assertEquals(2, fixture.server.getRequestCount());
            executor.shutdownNow();
        }
    }

    @Test
    void apiRequestCreatedBeforeClientCloseReportsClientClosed() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(List.of("api-one.test"))
                    .domainProbeIntervalMs(0)
                    .build();
            DefaultPicaClient client = new DefaultPicaClient(config,
                    LocalTlsClientContextFactory.build(config, fixture.dns,
                            fixture.clientCertificates.sslSocketFactory(),
                            fixture.clientCertificates.trustManager(), fixture.socketFactory));
            PicaRequest<PicaContentPage> request = client.newCategoriesRequest(
                    new SearchQuery.Builder().build());
            client.close();
            assertTrue(request.isCancelled());
            PicaApiException failure = assertThrows(PicaApiException.class, request::execute);
            assertEquals(PicaApiException.Reason.CLIENT_CLOSED, failure.getReason());
        }
    }

    @Test
    void stablePhotoIdentityAndAlbumCacheDoNotUseOrderAsPhotoKey() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(albumEps(photoSummary("chapter-a", "A", 1)));
            fixture.server.enqueue(albumDetail("album-id", "Album", List.of("chapter-a")));
            PicaAlbum album = client.getAlbum("album-id");
            album.getCategories().add("caller-only");
            assertTrue(client.getAlbum("album-id").getCategories().isEmpty());

            fixture.server.enqueue(photoPages("chapter-a", "a.png"));
            PicaPhoto first = client.getPhoto("album-id", "chapter-a");
            first.getImages().clear();
            PicaPhoto cached = client.getPhoto("album-id", "chapter-a");
            assertEquals(1, cached.getImages().size());

            fixture.server.enqueue(photoPages("chapter-a", "a-new.png"));
            PicaPhoto byOrder = client.getPhoto("album-id", 1);
            assertEquals("chapter-a", byOrder.id());
            assertEquals("a-new.png", byOrder.images().get(0).originalName());
        }
    }

    @Test
    void requestIsSingleUseAndCancelBeforeExecuteIsStable() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            PicaRequest<PicaAlbum> request = client.newAlbumRequest("album-id");
            request.cancel();
            PicaApiException cancelled = assertThrows(PicaApiException.class, request::execute);
            assertEquals(PicaApiException.Reason.CANCELLED, cancelled.getReason());
            assertTrue(request.isCancelled());
            assertThrows(IllegalStateException.class, request::execute);
            assertEquals(0, fixture.server.getRequestCount());
        }
    }

    @Test
    void requiredSessionFailureDoesNotSendNetworkRequest() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            PicaApiException failure = assertThrows(PicaApiException.class,
                    () -> client.getFavorites(new SearchQuery.Builder().build()));
            assertEquals(PicaApiException.Reason.SESSION_REQUIRED, failure.getReason());
            assertEquals(0, fixture.server.getRequestCount());
        }
    }

    @Test
    void albumPhotoOrderLookupReturnsAStableStaleErrorWhenPagesChangeIdentity() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(albumEps(photoSummary("chapter-a", "A", 1)));
            fixture.server.enqueue(albumDetail("album-id", "Album", List.of("chapter-a")));
            client.getAlbum("album-id");
            fixture.server.enqueue(photoPage("chapter-a", "a.png", 1, 2));
            fixture.server.enqueue(photoPage("chapter-b", "b.png", 2, 2));
            PicaApiException failure = assertThrows(PicaApiException.class,
                    () -> client.getPhoto("album-id", 1));
            assertEquals(PicaApiException.Reason.STALE_RESOURCE, failure.getReason());
        }
    }

    @Test
    void stableIdRefreshFollowsAnInsertedChapterAndOrderLookupUsesItsNewIdentity() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(albumEps(photoSummary("chapter-a", "A", 1),
                    photoSummary("chapter-b", "B", 2)));
            fixture.server.enqueue(albumDetail("album-id", "Album", List.of("chapter-a", "chapter-b")));
            client.getAlbum("album-id");

            fixture.server.enqueue(photoPages("chapter-x", "x.png"));
            fixture.server.enqueue(albumEps(photoSummary("chapter-x", "X", 1),
                    photoSummary("chapter-a", "A", 2), photoSummary("chapter-b", "B", 3)));
            fixture.server.enqueue(albumDetail("album-id", "Album v2", List.of("chapter-x", "chapter-a", "chapter-b")));
            fixture.server.enqueue(photoPages("chapter-a", "a.png"));
            assertEquals("chapter-a", client.refreshPhoto("album-id", "chapter-a").id());

            fixture.server.enqueue(photoPages("chapter-x", "x-new.png"));
            assertEquals("chapter-x", client.getPhoto("album-id", 1).id());
        }
    }

    @Test
    void refreshAlbumFailureLeavesThePreviousCacheAvailable() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(probe());
            fixture.server.enqueue(albumEps(photoSummary("chapter-a", "A", 1)));
            fixture.server.enqueue(albumDetail("album-id", "Album", List.of("chapter-a")));
            assertEquals("Album", client.getAlbum("album-id").title());

            fixture.server.enqueue(new MockResponse().setResponseCode(503).setBody("not retained"));
            PicaApiException failure = assertThrows(PicaApiException.class,
                    () -> client.refreshAlbum("album-id"));
            assertEquals(PicaApiException.Reason.HTTP_STATUS, failure.getReason());
            assertEquals("Album", client.getAlbum("album-id").title());
        }
    }

    @Test
    void albumOrderLookupSortsOnlyAWorkingCopy() throws Exception {
        PicaPhoto second = new PicaPhoto("album", "second", "second", "", 2, List.of(), false);
        PicaPhoto first = new PicaPhoto("album", "first", "first", "", 1, List.of(), false);
        PicaAlbum album = new PicaAlbum("album", null, "album", "", null, "", "",
                new java.util.ArrayList<>(), new java.util.ArrayList<>(), 0, 2, false,
                "", "", false, false, 0, 0, 0, 0, 0, 0, false, false,
                new java.util.ArrayList<>(List.of(second, first)));

        assertEquals("first", album.getPhoto(1).id());
        assertEquals(List.of(second, first), album.photos());
    }

    private static IPicaClient client(LocalTlsFixture fixture) {
        PicaConfiguration config = new PicaConfiguration.Builder()
                .domains(List.of("api-one.test"))
                .timeout(Duration.ofSeconds(3))
                .retryTimes(0)
                .domainProbeIntervalMs(0)
                .build();
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                fixture.socketFactory));
    }

    private static MockResponse probe() {
        return new MockResponse().setResponseCode(200);
    }

    private static MockResponse signIn() {
        return envelope("{\"token\":\"fixture-token\"}");
    }

    private static MockResponse profile(String id, String name) {
        return envelope("{\"user\":{\"_id\":\"" + id + "\",\"name\":\"" + name + "\"}}");
    }

    private static MockResponse albumEps(String... summaries) {
        return envelope("{\"eps\":{\"total\":" + summaries.length
                + ",\"page\":1,\"pages\":1,\"docs\":[" + String.join(",", summaries) + "]}}");
    }

    private static String photoSummary(String id, String title, int order) {
        return "{\"_id\":\"" + id + "\",\"title\":\"" + title
                + "\",\"order\":" + order + ",\"updated_at\":\"\"}";
    }

    private static MockResponse albumDetail(String id, String title, List<String> ignored) {
        return envelope("{\"comic\":{\"_id\":\"" + id + "\",\"title\":\""
                + title + "\",\"categories\":[],\"tags\":[]}}");
    }

    private static MockResponse photoPages(String chapterId, String imageName) {
        return photoPage(chapterId, imageName, 1, 1);
    }

    private static MockResponse photoPage(String chapterId, String imageName, int page, int pages) {
        return envelope("{\"ep\":{\"_id\":\"" + chapterId
                + "\"},\"pages\":{\"page\":" + page + ",\"pages\":" + pages
                + ",\"docs\":[{\"media\":{\"originalName\":\""
                + imageName + "\",\"path\":\"image\",\"fileServer\":\"https://s2.picacomic.com\"}}]}}");
    }

    private static MockResponse envelope(String data) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":" + data + "}");
    }
}
