package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.internal.util.FileUtils;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchAtomicDownloadTest {

    @TempDir
    Path tempDir;

    @Test
    void singleThreadExternalExecutorRunsLeafTasksWithoutParentChildStarvation() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(imageResponse("first"));
            fixture.server.enqueue(imageResponse("second"));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(List.of("api-one.test"))
                    .executor(executor)
                    .timeout(Duration.ofSeconds(3))
                    .build();
            PicaImage first = image(fixture, "one.png");
            PicaImage second = new PicaImage("../two.png", "", "",
                    fixture.url("s2.picacomic.com", "/static/two.png"));
            PicaPhoto photo = new PicaPhoto("album", "photo", "chapter", "", 1,
                    List.of(first, second), false);
            Path directory = tempDir.resolve("chapter");
            try (IPicaClient client = new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config,
                    fixture.dns, fixture.clientCertificates.sslSocketFactory(),
                    fixture.clientCertificates.trustManager(), fixture.socketFactory))) {
                DownloadResult result = client.downloadPhoto(photo, directory, executor);
                assertTrue(result.isAllSuccess(), result.getFailedTasks().toString());
                assertEquals(2, result.getSuccessfulFiles().size());
            } finally {
                assertFalse(executor.isShutdown());
                executor.shutdownNow();
            }
            assertTrue(Files.exists(directory.resolve("one.png")));
            assertTrue(Files.exists(directory.resolve(".._two.png")));
            try (var files = Files.list(directory)) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
            }
        }
    }

    @Test
    void downloadAlbumUsesStableChapterIdsAfterAlbumIsReordered() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(List.of("api-one.test"))
                    .timeout(Duration.ofSeconds(3))
                    .domainProbeIntervalMs(0)
                    .concurrentPhotoDownloads(1)
                    .build();
            DefaultPicaClient client = U2TestSupport.client(fixture, config);
            try {
                fixture.server.enqueue(U2TestSupport.probe());
                fixture.server.enqueue(U2TestSupport.albumEps(
                        U2TestSupport.photoSummary("chapter-a", "A", 1),
                        U2TestSupport.photoSummary("chapter-b", "B", 2)));
                fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "initial"));
                PicaAlbum album = client.getAlbum("album-id");

                fixture.server.enqueue(U2TestSupport.photoPages("chapter-inserted", "unexpected.png"));
                fixture.server.enqueue(U2TestSupport.albumEps(
                        U2TestSupport.photoSummary("chapter-inserted", "Inserted", 1),
                        U2TestSupport.photoSummary("chapter-b", "B", 2),
                        U2TestSupport.photoSummary("chapter-a", "A", 3)));
                fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "reordered"));
                fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "a.png"));
                fixture.server.enqueue(imageResponse("a-content"));
                fixture.server.enqueue(U2TestSupport.photoPages("chapter-b", "b.png"));
                fixture.server.enqueue(imageResponse("b-content"));

                Path target = tempDir.resolve("album-download");
                DownloadResult result = client.downloadAlbum(album, target);

                assertTrue(result.isAllSuccess(), result.getFailedTasks().toString());
                assertEquals(2, result.getSuccessfulFiles().size());
                assertEquals("a-content", Files.readString(target.resolve("3").resolve("A").resolve("a.png")));
                assertEquals("b-content", Files.readString(target.resolve("2").resolve("B").resolve("b.png")));
                assertEquals(10, fixture.server.getRequestCount());
            } finally {
                client.close();
            }
        }
    }

    @Test
    void finalFilesAppearOnlyAfterAtomicMoveAndExistingTargetsSkipNetwork() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            Path target = tempDir.resolve("nested").resolve("safe.png");
            fixture.server.enqueue(imageResponse("new-data"));
            client.downloadImage(image(fixture, "safe.png"), target);
            assertEquals("new-data", Files.readString(target));
            try (var files = Files.list(target.getParent())) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
            }

            Files.writeString(target, "caller-data");
            client.downloadImage(image(fixture, "safe.png"), target);
            assertEquals("caller-data", Files.readString(target));
            assertEquals(1, fixture.server.getRequestCount());
        }
    }

    @Test
    void clientCloseCancelsQueuedLeafTasks() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(imageResponse("first").throttleBody(1, 1, TimeUnit.SECONDS));
            fixture.server.enqueue(imageResponse("queued"));
            ExecutorService leafExecutor = Executors.newSingleThreadExecutor();
            ExecutorService coordinatorExecutor = Executors.newSingleThreadExecutor();
            IPicaClient client = client(fixture);
            Path directory = tempDir.resolve("close-queued");
            PicaPhoto photo = new PicaPhoto("album", "photo", "chapter", "", 1,
                    List.of(image(fixture, "first.png"), image(fixture, "queued.png")), false);
            try {
                Future<DownloadResult> batch = coordinatorExecutor.submit(
                        () -> client.downloadPhoto(photo, directory, leafExecutor));
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
                client.close();
                DownloadResult result = batch.get(5, TimeUnit.SECONDS);
                assertTrue(result.getSuccessfulFiles().isEmpty());
                assertEquals(2, result.getFailedTasks().size());
                assertTrue(result.getFailedTasks().values().stream().allMatch(
                        exception -> exception instanceof ImageFetchException imageException
                                && imageException.getReason() == ImageFetchException.Reason.CLIENT_CLOSED),
                        result.getFailedTasks().toString());
                assertEquals(1, fixture.server.getRequestCount(),
                        "queued leaf must not start after client close");
                assertNoPartFiles(directory);
            } finally {
                client.close();
                coordinatorExecutor.shutdownNow();
                leafExecutor.shutdownNow();
            }
        }
    }

    @Test
    void interruptedBatchRemovesCancelledFuturesAndAllowsLaterCompletion() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(imageResponse("first"));
            fixture.server.enqueue(imageResponse("completed"));
            CountDownLatch writerStarted = new CountDownLatch(1);
            CountDownLatch releaseWriter = new CountDownLatch(1);
            AtomicBoolean blockWriter = new AtomicBoolean(true);
            DefaultPicaClient.ImageFileWriter writer = (temporary, bytes) -> {
                if (blockWriter.get()) {
                    writerStarted.countDown();
                    try {
                        releaseWriter.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("fixture writer interrupted", exception);
                    }
                }
                Files.write(temporary, bytes, StandardOpenOption.WRITE);
            };
            ExecutorService leafExecutor = Executors.newSingleThreadExecutor();
            ExecutorService coordinatorExecutor = Executors.newSingleThreadExecutor();
            DefaultPicaClient client = internalClient(fixture, writer, BatchAtomicDownloadTest::moveImageFile);
            AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
            Path directory = tempDir.resolve("interrupted-batch");
            PicaPhoto photo = new PicaPhoto("album", "photo", "chapter", "", 1,
                    List.of(image(fixture, "first.png"), image(fixture, "queued.png")), false);
            try {
                Future<DownloadResult> batch = coordinatorExecutor.submit(() -> {
                    coordinatorThread.set(Thread.currentThread());
                    return client.downloadPhoto(photo, directory, leafExecutor);
                });
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                awaitBatchFutureCount(client, 2);
                coordinatorThread.get().interrupt();
                DownloadResult interrupted = batch.get(5, TimeUnit.SECONDS);
                assertTrue(interrupted.getSuccessfulFiles().isEmpty());
                assertEquals(2, interrupted.getFailedTasks().size());
                awaitBatchFutureCount(client, 0);

                blockWriter.set(false);
                DownloadResult completed = client.downloadPhoto(
                        singleImagePhoto(fixture, "completed.png"),
                        tempDir.resolve("completed-batch"), leafExecutor);
                assertTrue(completed.isAllSuccess(), completed.getFailedTasks().toString());
                awaitBatchFutureCount(client, 0);
                assertFalse(leafExecutor.isShutdown());
            } finally {
                releaseWriter.countDown();
                client.close();
                coordinatorExecutor.shutdownNow();
                leafExecutor.shutdownNow();
            }
        }
    }

    @Test
    void writeFailureCleansTemporaryPartFile() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(imageResponse("write-failure"));
            Path directory = tempDir.resolve("write-failure");
            DefaultPicaClient client = internalClient(fixture,
                    (temporary, bytes) -> {
                        assertTrue(Files.exists(temporary));
                        throw new IOException("fixture write failure");
                    }, BatchAtomicDownloadTest::moveImageFile);
            try {
                DownloadResult result = client.downloadPhoto(singleImagePhoto(fixture, "write.png"), directory);
                assertTrue(result.getSuccessfulFiles().isEmpty());
                assertEquals(1, result.getFailedTasks().size());
                assertTrue(result.getFailedTasks().values().stream()
                        .allMatch(exception -> exception instanceof IOException));
            } finally {
                client.close();
            }
            assertFalse(Files.exists(directory.resolve("write.png")));
            assertNoPartFiles(directory);
        }
    }

    @Test
    void unsupportedAtomicMoveFallsBackToReplaceExistingAndCleansPartFile() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(imageResponse("move-failure"));
            Path directory = tempDir.resolve("move-failure");
            DefaultPicaClient client = internalClient(fixture, BatchAtomicDownloadTest::writeImageFile,
                    (temporary, destination) -> {
                        assertTrue(Files.exists(temporary));
                        throw new AtomicMoveNotSupportedException(
                                temporary.toString(), destination.toString(), "fixture");
            });
            try {
                DownloadResult result = client.downloadPhoto(singleImagePhoto(fixture, "move.png"), directory);
                assertTrue(result.isAllSuccess(), result.getFailedTasks().toString());
                assertEquals("move-failure", Files.readString(directory.resolve("move.png")));
            } finally {
                client.close();
            }
            assertTrue(Files.exists(directory.resolve("move.png")));
            assertNoPartFiles(directory);
        }
    }

    @Test
    void clientCloseDuringWriteCleansTemporaryPartFile() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(imageResponse("cancel-write"));
            CountDownLatch writerStarted = new CountDownLatch(1);
            CountDownLatch writerFinished = new CountDownLatch(1);
            CountDownLatch writerBlock = new CountDownLatch(1);
            DefaultPicaClient.ImageFileWriter writer = (temporary, bytes) -> {
                writerStarted.countDown();
                try {
                    if (!writerBlock.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("fixture writer was not cancelled");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ImageFetchException(ImageFetchException.Reason.CANCELLED, exception);
                } finally {
                    writerFinished.countDown();
                }
            };
            ExecutorService leafExecutor = Executors.newSingleThreadExecutor();
            ExecutorService coordinatorExecutor = Executors.newSingleThreadExecutor();
            DefaultPicaClient client = internalClient(fixture, writer,
                    BatchAtomicDownloadTest::moveImageFile);
            Path directory = tempDir.resolve("cancel-write");
            PicaPhoto photo = new PicaPhoto("album", "photo", "chapter", "", 1,
                    List.of(image(fixture, "cancel-write.png")), false);
            try {
                Future<DownloadResult> batch = coordinatorExecutor.submit(
                        () -> client.downloadPhoto(photo, directory, leafExecutor));
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                assertTrue(hasPartFile(directory));
                client.close();
                assertTrue(writerFinished.await(5, TimeUnit.SECONDS));
                DownloadResult result = batch.get(5, TimeUnit.SECONDS);
                assertTrue(result.getSuccessfulFiles().isEmpty());
                assertEquals(1, result.getFailedTasks().size());
                assertTrue(result.getFailedTasks().values().stream().allMatch(
                        exception -> exception instanceof ImageFetchException imageException
                                && imageException.getReason() == ImageFetchException.Reason.CLIENT_CLOSED));
                assertNoPartFiles(directory);
            } finally {
                client.close();
                coordinatorExecutor.shutdownNow();
                leafExecutor.shutdownNow();
            }
        }
    }

    @Test
    void remoteMetadataIsReducedToOneSafeSegment() {
        assertEquals("_CON.txt", FileUtils.safePathSegment("CON.txt"));
        assertEquals("unknown_filename", FileUtils.safePathSegment(".."));
        assertFalse(FileUtils.safePathSegment("../x\\y").contains("/"));
        assertFalse(FileUtils.safePathSegment("../x\\y").contains("\\"));
        assertTrue(FileUtils.resolveDescendant(tempDir, "../escape").startsWith(
                FileUtils.normalizeAbsolute(tempDir)));
    }

    private static MockResponse imageResponse(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(body);
    }

    private static IPicaClient client(LocalTlsFixture fixture) {
        PicaConfiguration config = new PicaConfiguration.Builder()
                .domains(List.of("api-one.test"))
                .timeout(Duration.ofSeconds(3))
                .build();
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(),
                fixture.clientCertificates.trustManager(), fixture.socketFactory));
    }

    private static PicaImage image(LocalTlsFixture fixture, String name) {
        return new PicaImage(name, "", "", fixture.url("s2.picacomic.com", "/static/" + name));
    }

    private static PicaPhoto singleImagePhoto(LocalTlsFixture fixture, String name) {
        return new PicaPhoto("album", "photo", "chapter", "", 1,
                List.of(image(fixture, name)), false);
    }

    private static DefaultPicaClient internalClient(LocalTlsFixture fixture,
                                                     DefaultPicaClient.ImageFileWriter writer,
                                                     DefaultPicaClient.ImageFileMover mover) {
        PicaConfiguration config = new PicaConfiguration.Builder()
                .domains(List.of("api-one.test"))
                .timeout(Duration.ofSeconds(3))
                .build();
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                fixture.socketFactory), writer, mover);
    }

    private static void writeImageFile(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes, StandardOpenOption.WRITE);
    }

    private static void moveImageFile(Path temporary, Path target) throws IOException {
        FileUtils.moveAtomically(temporary, target);
    }

    private static boolean hasPartFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".part"));
        }
    }

    private static void assertNoPartFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private static void awaitBatchFutureCount(DefaultPicaClient client, int expected)
            throws Exception {
        Field field = DefaultPicaClient.class.getDeclaredField("batchFutures");
        field.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline
                && ((java.util.Set<?>) field.get(client)).size() != expected) {
            Thread.sleep(10);
        }
        assertEquals(expected, ((java.util.Set<?>) field.get(client)).size());
    }
}
