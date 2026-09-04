package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.PicaImageRequest;
import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.enums.PicaSessionState;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.model.PicaContentPage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.api.model.PicaUserInfo;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRequestCancellationTest {

    @Test
    void cancelBeforeExecuteIsSingleUseAndStartsNoCall() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            PicaRequest<PicaAlbum> request = client.newAlbumRequest("album-id");
            request.cancel();
            PicaApiException failure = assertThrows(PicaApiException.class, request::execute);
            assertEquals(PicaApiException.Reason.CANCELLED, failure.getReason());
            assertTrue(request.isCancelled());
            assertThrows(IllegalStateException.class, request::execute);
            request.close();
            assertEquals(0, fixture.server.getRequestCount());
        }
    }

    @Test
    void cancellationDuringAlbumPaginationStopsBeforeAlbumDetailAndDoesNotCachePartialData() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(U2TestSupport.albumEpsPage(1, 2,
                    U2TestSupport.photoSummary("chapter-a", "A", 1)));
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            PicaRequest<PicaAlbum> request = client.newAlbumRequest("album-id");
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<PicaAlbum> future = caller.submit(request::execute);
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "HEAD");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                request.cancel();
                assertEquals(PicaApiException.Reason.CANCELLED, failureFrom(future).getReason());
                assertEquals(3, fixture.server.getRequestCount());

                fixture.server.enqueue(U2TestSupport.albumEps(
                        U2TestSupport.photoSummary("chapter-a", "A", 1)));
                fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "complete"));
                assertEquals("complete", client.getAlbum("album-id").title());
            } finally {
                request.close();
                caller.shutdownNow();
            }
        }
    }

    @Test
    void cancellationDuringPhotoPaginationDoesNotPublishPartialPhoto() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(U2TestSupport.albumEps(
                    U2TestSupport.photoSummary("chapter-a", "A", 1)));
            fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "album"));
            client.getAlbum("album-id");

            fixture.server.enqueue(U2TestSupport.photoPage("chapter-a", "one.png", 1, 2));
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            PicaRequest<PicaPhoto> request = client.newPhotoRequest("album-id", "chapter-a");
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "HEAD");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                Future<PicaPhoto> future = caller.submit(request::execute);
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                request.cancel();
                assertEquals(PicaApiException.Reason.CANCELLED, failureFrom(future).getReason());

                fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "complete.png"));
                assertEquals("complete.png", client.getPhoto("album-id", "chapter-a")
                        .images().get(0).originalName());
            } finally {
                request.close();
                caller.shutdownNow();
            }
        }
    }

    @Test
    void cancellationDuringMirrorAttemptDoesNotStartAnotherAttempt() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration config = U2TestSupport.config(
                    List.of("api-one.test", "api-two.test"), 5);
            try (DefaultPicaClient client = U2TestSupport.client(fixture, config)) {
                fixture.server.enqueue(U2TestSupport.probe());
                fixture.server.enqueue(U2TestSupport.probe());
                fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
                PicaRequest<?> request = client.newCategoriesRequest(new SearchQuery.Builder().build());
                ExecutorService caller = Executors.newSingleThreadExecutor();
                try {
                    Future<?> future = caller.submit(request::execute);
                    assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "HEAD");
                    assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "HEAD");
                    assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                    request.cancel();
                    assertEquals(PicaApiException.Reason.CANCELLED, failureFrom(future).getReason());
                    assertEquals(3, fixture.server.getRequestCount());
                } finally {
                    request.close();
                    caller.shutdownNow();
                }
            }
        }
    }

    @Test
    void cancellingOneApiRequestDoesNotCancelAnotherRequest() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            fixture.server.enqueue(U2TestSupport.contentPage("independent"));
            PicaRequest<PicaContentPage> first = client.newCategoriesRequest(
                    new SearchQuery.Builder().build());
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<PicaContentPage> firstFuture = caller.submit(first::execute);
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "HEAD");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");

                assertEquals("independent", client.getCategories(new SearchQuery.Builder().build())
                        .albums().get(0).title());
                first.cancel();
                assertEquals(PicaApiException.Reason.CANCELLED, failureFrom(firstFuture).getReason());
            } finally {
                first.close();
                caller.shutdownNow();
            }
        }
    }

    @Test
    void cancellationDuringLoginProfileStopsTheAtomicLogin() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(U2TestSupport.signIn());
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            PicaRequest<PicaUserInfo> request = client.newLoginRequest("fixture-user", "fixture-password");
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<PicaUserInfo> future = caller.submit(request::execute);
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "HEAD");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "POST");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                request.cancel();
                assertEquals(PicaApiException.Reason.CANCELLED, failureFrom(future).getReason());
                assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());
            } finally {
                request.close();
                caller.shutdownNow();
            }
        }
    }

    @Test
    void clientClosedAlwaysWinsOverARequestCancellation() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            DefaultPicaClient client = U2TestSupport.client(fixture);
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            PicaRequest<?> request = client.newCategoriesRequest(new SearchQuery.Builder().build());
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = caller.submit(request::execute);
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "HEAD");
                assertMethod(fixture.server.takeRequest(3, TimeUnit.SECONDS), "GET");
                client.close();
                request.cancel();
                assertEquals(PicaApiException.Reason.CLIENT_CLOSED, failureFrom(future).getReason());
                assertTrue(request.isCancelled());
            } finally {
                request.close();
                caller.shutdownNow();
                client.close();
            }
        }
    }

    @Test
    void imageRequestUsesTheGenericSingleUseRequestSurface() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("image-bytes"));
            PicaImageRequest imageRequest = client.newImageRequest(
                    new io.github.jukomu.picacomic.api.model.PicaImage(
                            "one.png", "", "", fixture.url("image-one.test", "/static/one.png")));
            PicaRequest<byte[]> generic = imageRequest;
            assertEquals("image-bytes", new String(generic.execute()));
            generic.cancel();
            generic.close();
            assertThrows(IllegalStateException.class, generic::execute);
        }
    }

    private static PicaApiException failureFrom(Future<?> future) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(3, TimeUnit.SECONDS));
        return assertInstanceOf(PicaApiException.class, failure.getCause());
    }

    private static void assertMethod(RecordedRequest request, String expected) {
        assertNotNull(request);
        assertEquals(expected, request.getMethod());
    }
}
