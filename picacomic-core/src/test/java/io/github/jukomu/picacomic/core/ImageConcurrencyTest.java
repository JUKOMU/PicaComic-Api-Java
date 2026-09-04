package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.client.PicaImageRequest;
import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void directAndBatchRequestsShareTheConfiguredReaderSlot() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(slowImageResponse("direct"));
            fixture.server.enqueue(imageResponse("batch"));
            PicaConfiguration config = config(1);
            DefaultPicaClient client = newClient(fixture, config);
            ExecutorService directCaller = Executors.newSingleThreadExecutor();
            ExecutorService batchExecutor = Executors.newSingleThreadExecutor();
            ExecutorService coordinator = Executors.newSingleThreadExecutor();
            PicaImageRequest direct = client.newImageRequest(image(fixture, "direct.png"));
            try {
                Future<byte[]> directFuture = directCaller.submit(direct::execute);
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));

                PicaPhoto photo = singleImagePhoto(fixture, "batch.png");
                Future<DownloadResult> batchFuture = coordinator.submit(
                        () -> client.downloadPhoto(photo, tempDir.resolve("batch"), batchExecutor));
                waitForRequestCount(fixture, 1);
                assertEquals(1, fixture.server.getRequestCount(),
                        "batch leaf must wait for the direct request's shared slot");

                direct.cancel();
                assertReason(directFuture, ImageFetchException.Reason.CANCELLED);
                DownloadResult result = batchFuture.get(3, TimeUnit.SECONDS);
                assertTrue(result.isAllSuccess(), result.getFailedTasks().toString());
                assertEquals(2, fixture.server.getRequestCount());
            } finally {
                direct.close();
                client.close();
                directCaller.shutdownNow();
                batchExecutor.shutdownNow();
                coordinator.shutdownNow();
            }
        }
    }

    @Test
    void waitingRequestCanCancelAndTheSlotIsReleasedForTheNextRequest() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(slowImageResponse("first"));
            fixture.server.enqueue(imageResponse("released"));
            PicaConfiguration config = config(1);
            DefaultPicaClient client = newClient(fixture, config);
            ExecutorService caller = Executors.newFixedThreadPool(2);
            PicaImageRequest first = client.newImageRequest(image(fixture, "first.png"));
            PicaImageRequest waiting = client.newImageRequest(image(fixture, "waiting.png"));
            try {
                Future<byte[]> firstFuture = caller.submit(first::execute);
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
                Future<byte[]> waitingFuture = caller.submit(waiting::execute);
                waitForRequestCount(fixture, 1);
                waiting.cancel();
                assertReason(waitingFuture, ImageFetchException.Reason.CANCELLED);

                first.cancel();
                assertReason(firstFuture, ImageFetchException.Reason.CANCELLED);
                assertEquals("released", new String(client.fetchImageBytes(
                        image(fixture, "released.png"))));
                assertEquals(2, fixture.server.getRequestCount());
            } finally {
                first.close();
                waiting.close();
                client.close();
                caller.shutdownNow();
            }
        }
    }

    private static PicaConfiguration config(int imageConcurrency) {
        return new PicaConfiguration.Builder()
                .domains(List.of("api-one.test"))
                .timeout(Duration.ofSeconds(3))
                .downloadThreadPoolSize(1)
                .concurrentPhotoDownloads(1)
                .concurrentImageDownloads(imageConcurrency)
                .build();
    }

    private static DefaultPicaClient newClient(LocalTlsFixture fixture, PicaConfiguration config) {
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                fixture.socketFactory));
    }

    private static MockResponse slowImageResponse(String body) {
        return imageResponse(body).throttleBody(1, 1, TimeUnit.SECONDS);
    }

    private static MockResponse imageResponse(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "image/png").setBody(body);
    }

    private static PicaImage image(LocalTlsFixture fixture, String name) {
        return new PicaImage(name, "", "", fixture.url("s2.picacomic.com", "/static/" + name));
    }

    private static PicaPhoto singleImagePhoto(LocalTlsFixture fixture, String name) {
        return new PicaPhoto("album", "photo", "chapter", "", 1,
                List.of(image(fixture, name)), false);
    }

    private static void waitForRequestCount(LocalTlsFixture fixture, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (fixture.server.getRequestCount() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(fixture.server.getRequestCount() >= expected,
                "expected request count " + expected + ", got " + fixture.server.getRequestCount());
    }

    private static void assertReason(Future<byte[]> future, ImageFetchException.Reason reason)
            throws Exception {
        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(exception.getCause() instanceof ImageFetchException);
        assertEquals(reason, ((ImageFetchException) exception.getCause()).getReason());
    }
}
