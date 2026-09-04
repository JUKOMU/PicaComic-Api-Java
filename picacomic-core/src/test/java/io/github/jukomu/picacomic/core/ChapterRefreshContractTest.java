package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterRefreshContractTest {

    @Test
    void insertedChapterRefreshesStableIdButOrderLookupReturnsTheActualIdentity() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            seedAlbum(fixture, client,
                    U2TestSupport.photoSummary("chapter-a", "A", 1),
                    U2TestSupport.photoSummary("chapter-b", "B", 2));

            fixture.server.enqueue(U2TestSupport.photoPages("chapter-x", "x-old.png"));
            fixture.server.enqueue(U2TestSupport.albumEps(
                    U2TestSupport.photoSummary("chapter-x", "X", 1),
                    U2TestSupport.photoSummary("chapter-a", "A", 2),
                    U2TestSupport.photoSummary("chapter-b", "B", 3)));
            fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "new-album"));
            fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "a.png"));
            assertEquals("chapter-a", client.refreshPhoto("album-id", "chapter-a").id());

            fixture.server.enqueue(U2TestSupport.photoPages("chapter-x", "x-new.png"));
            PicaPhoto byOrder = client.getPhoto("album-id", 1);
            assertEquals("chapter-x", byOrder.id());
            assertEquals("x-new.png", byOrder.images().get(0).originalName());
        }
    }

    @Test
    void reorderedChapterIsRetriedAgainstTheRefreshedLocator() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            seedAlbum(fixture, client,
                    U2TestSupport.photoSummary("chapter-a", "A", 1),
                    U2TestSupport.photoSummary("chapter-b", "B", 2));

            fixture.server.enqueue(U2TestSupport.photoPages("chapter-b", "b-at-old-order.png"));
            fixture.server.enqueue(U2TestSupport.albumEps(
                    U2TestSupport.photoSummary("chapter-b", "B", 1),
                    U2TestSupport.photoSummary("chapter-a", "A", 2)));
            fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "reordered"));
            fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "a-at-new-order.png"));

            PicaPhoto photo = client.refreshPhoto("album-id", "chapter-a");
            assertEquals("chapter-a", photo.id());
            assertEquals(2, photo.order());
            assertEquals("a-at-new-order.png", photo.images().get(0).originalName());
        }
    }

    @Test
    void deletedStableChapterIsEvictedAndReportedAsNotFound() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            seedAlbum(fixture, client, U2TestSupport.photoSummary("chapter-a", "A", 1));
            fixture.server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(404).setBody("deleted-marker"));
            fixture.server.enqueue(U2TestSupport.albumEps(
                    U2TestSupport.photoSummary("chapter-b", "B", 1)));
            fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "without-a"));

            PicaApiException failure = assertThrows(PicaApiException.class,
                    () -> client.refreshPhoto("album-id", "chapter-a"));
            assertEquals(PicaApiException.Reason.NOT_FOUND, failure.getReason());
            assertEquals(6, fixture.server.getRequestCount());
        }
    }

    @Test
    void orderLocator404RemainsAnHttpStatusBecauseNoStableIdWasRequested() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            seedAlbum(fixture, client, U2TestSupport.photoSummary("chapter-a", "A", 1));
            fixture.server.enqueue(new okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(404).setBody("deleted-marker"));

            PicaApiException failure = assertThrows(PicaApiException.class,
                    () -> client.getPhoto("album-id", 1));
            assertEquals(PicaApiException.Reason.HTTP_STATUS, failure.getReason());
            assertEquals(404, failure.getHttpStatus());
        }
    }

    @Test
    void duplicateOrdersNeverOverwriteStableIdCacheEntries() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            seedAlbum(fixture, client,
                    U2TestSupport.photoSummary("chapter-a", "A", 1),
                    U2TestSupport.photoSummary("chapter-b", "B", 1));

            fixture.server.enqueue(U2TestSupport.photoPages("chapter-b", "b.png"));
            fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "a.png"));
            assertEquals("chapter-b", client.getPhoto("album-id", 1).id());
            assertEquals("chapter-a", client.getPhoto("album-id", 1).id());
            assertEquals(5, fixture.server.getRequestCount());
        }
    }

    @Test
    void mismatchedLaterPageNeverPublishesPartialPhoto() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            seedAlbum(fixture, client, U2TestSupport.photoSummary("chapter-a", "A", 1));
            fixture.server.enqueue(U2TestSupport.photoPage("chapter-a", "first.png", 1, 2));
            fixture.server.enqueue(U2TestSupport.photoPage("chapter-b", "wrong.png", 2, 2));
            PicaApiException failure = assertThrows(PicaApiException.class,
                    () -> client.getPhoto("album-id", "chapter-a"));
            assertEquals(PicaApiException.Reason.STALE_RESOURCE, failure.getReason());

            fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "complete.png"));
            assertEquals("complete.png", client.getPhoto("album-id", "chapter-a")
                    .images().get(0).originalName());
            assertEquals(6, fixture.server.getRequestCount());
        }
    }

    @Test
    void invalidateAtomicallyRemovesAlbumAndAllFullPhotoEntries() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            seedAlbum(fixture, client, U2TestSupport.photoSummary("chapter-a", "A", 1));
            fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "cached.png"));
            client.getPhoto("album-id", "chapter-a");
            client.invalidateAlbum("album-id");

            fixture.server.enqueue(U2TestSupport.albumEps(
                    U2TestSupport.photoSummary("chapter-a", "A", 1)));
            fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "refetched"));
            fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "fresh.png"));
            assertEquals("fresh.png", client.getPhoto("album-id", "chapter-a")
                    .images().get(0).originalName());
            assertEquals(7, fixture.server.getRequestCount());
        }
    }

    @Test
    void inFlightAlbumCommitIsLinearizedWithInvalidate() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        Runnable beforeCommit = () -> {
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                await(release);
            }
        };
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            DefaultPicaClient client = U2TestSupport.instrumentedClient(fixture,
                    U2TestSupport.config(List.of("api-one.test"), 0), () -> {
                    }, beforeCommit);
            try {
                seedAlbum(fixture, client, U2TestSupport.photoSummary("chapter-a", "A", 1));
                armed.set(true);
                fixture.server.enqueue(U2TestSupport.albumEps(
                        U2TestSupport.photoSummary("chapter-a", "late", 1)));
                fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "late-album"));
                ExecutorService caller = Executors.newSingleThreadExecutor();
                ExecutorService invalidator = Executors.newSingleThreadExecutor();
                try {
                    Future<?> request = caller.submit(() -> client.refreshAlbum("album-id"));
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    Future<?> invalidation = invalidator.submit(() -> client.invalidateAlbum("album-id"));
                    release.countDown();
                    request.get(3, TimeUnit.SECONDS);
                    invalidation.get(3, TimeUnit.SECONDS);

                    fixture.server.enqueue(U2TestSupport.albumEps(
                            U2TestSupport.photoSummary("chapter-a", "A", 1)));
                    fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "after-invalidate"));
                    assertEquals("after-invalidate", client.getAlbum("album-id").title());
                } finally {
                    release.countDown();
                    caller.shutdownNow();
                    invalidator.shutdownNow();
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    void inFlightPhotoCommitIsLinearizedWithInvalidate() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        Runnable beforeCommit = () -> {
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                await(release);
            }
        };
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            DefaultPicaClient client = U2TestSupport.instrumentedClient(fixture,
                    U2TestSupport.config(List.of("api-one.test"), 0), () -> {
                    }, beforeCommit);
            try {
                seedAlbum(fixture, client, U2TestSupport.photoSummary("chapter-a", "A", 1));
                armed.set(true);
                fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "late.png"));
                PicaRequest<PicaPhoto> request = client.newPhotoRequest("album-id", "chapter-a");
                ExecutorService caller = Executors.newSingleThreadExecutor();
                ExecutorService invalidator = Executors.newSingleThreadExecutor();
                try {
                    Future<PicaPhoto> photo = caller.submit(request::execute);
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    CountDownLatch invalidationStarted = new CountDownLatch(1);
                    Future<?> invalidation = invalidator.submit(() -> {
                        invalidationStarted.countDown();
                        client.invalidateAlbum("album-id");
                    });
                    assertTrue(invalidationStarted.await(3, TimeUnit.SECONDS));
                    release.countDown();
                    assertEquals("late.png", photo.get(3, TimeUnit.SECONDS)
                            .images().get(0).originalName());
                    invalidation.get(3, TimeUnit.SECONDS);

                    fixture.server.enqueue(U2TestSupport.albumEps(
                            U2TestSupport.photoSummary("chapter-a", "A", 1)));
                    fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "after-invalidate"));
                    fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "fresh.png"));
                    assertEquals("fresh.png", client.getPhoto("album-id", "chapter-a")
                            .images().get(0).originalName());
                } finally {
                    release.countDown();
                    request.close();
                    caller.shutdownNow();
                    invalidator.shutdownNow();
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    void invalidateDuringStableIdInternalRefreshCannotRebindItsGeneration() throws Exception {
        runLateInternalRefresh(false);
    }

    @Test
    void invalidateDuringOrderInternalRefreshCannotRebindItsGeneration() throws Exception {
        runLateInternalRefresh(true);
    }

    private static void runLateInternalRefresh(boolean orderPath) throws Exception {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicBoolean blockRefresh = new AtomicBoolean();
        BlockingQueue<MockResponse> responses = new LinkedBlockingQueue<>();
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    String path = request.getPath();
                    if (path != null && path.contains("/eps")
                            && blockRefresh.compareAndSet(true, false)) {
                        refreshStarted.countDown();
                        if (!releaseRefresh.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("internal refresh was not released");
                        }
                    }
                    return responses.take();
                }
            });
            DefaultPicaClient client = U2TestSupport.client(fixture);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                responses.add(U2TestSupport.probe());
                responses.add(U2TestSupport.albumEps(
                        U2TestSupport.photoSummary("chapter-a", "A", 1)));
                responses.add(U2TestSupport.albumDetail("album-id", "old"));
                assertEquals("old", client.getAlbum("album-id").title());

                blockRefresh.set(true);
                responses.add(U2TestSupport.photoPages("chapter-x", "stale-locator.png"));
                responses.add(U2TestSupport.albumEps(orderPath
                        ? U2TestSupport.photoSummary("chapter-x", "X", 1)
                        : U2TestSupport.photoSummary("chapter-a", "A", 2)));
                responses.add(U2TestSupport.albumDetail("album-id", "late-refresh"));
                if (!orderPath) {
                    responses.add(U2TestSupport.photoPages("chapter-a", "stale-photo.png"));
                }

                Future<PicaPhoto> pending = caller.submit(() -> orderPath
                        ? client.getPhoto("album-id", 1)
                        : client.refreshPhoto("album-id", "chapter-a"));
                assertTrue(refreshStarted.await(3, TimeUnit.SECONDS));
                client.invalidateAlbum("album-id");
                releaseRefresh.countDown();
                PicaPhoto lateResult = pending.get(3, TimeUnit.SECONDS);
                assertEquals(orderPath ? "chapter-x" : "chapter-a", lateResult.id());

                String targetChapter = orderPath ? "chapter-x" : "chapter-a";
                responses.add(U2TestSupport.albumEps(orderPath
                        ? U2TestSupport.photoSummary("chapter-x", "X", 1)
                        : U2TestSupport.photoSummary("chapter-a", "A", 2)));
                responses.add(U2TestSupport.albumDetail("album-id", "fresh-album"));
                responses.add(U2TestSupport.photoPages(targetChapter, "fresh-photo.png"));
                assertEquals("fresh-photo.png", client.getPhoto("album-id", targetChapter)
                        .images().get(0).originalName());
            } finally {
                releaseRefresh.countDown();
                caller.shutdownNow();
                client.close();
            }
        }
    }

    private static void seedAlbum(LocalTlsFixture fixture,
                                  DefaultPicaClient client,
                                  String... summaries) {
        fixture.server.enqueue(U2TestSupport.probe());
        fixture.server.enqueue(U2TestSupport.albumEps(summaries));
        fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "old-album"));
        assertEquals("old-album", client.getAlbum("album-id").title());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("fixture latch did not open");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("fixture latch interrupted", exception);
        }
    }
}
