package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.client.PicaImageRequest;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okio.Buffer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMemoryBudgetTest {

    @Test
    void reservationsAreBoundedWaitableAndReleasedExactlyOnce() throws Exception {
        ImageMemoryBudget budget = new ImageMemoryBudget();
        ImageMemoryBudget.Reservation full = budget.acquire(
                ImageMemoryBudget.MAX_PAYLOAD_BYTES,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                () -> false,
                () -> ImageFetchException.Reason.CANCELLED);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ImageMemoryBudget.Reservation> waiting = executor.submit(() -> budget.acquire(
                    1,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                    () -> false,
                    () -> ImageFetchException.Reason.CANCELLED));
            Thread.sleep(100);
            assertFalse(waiting.isDone());
            full.close();
            full.close();
            ImageMemoryBudget.Reservation one = waiting.get(3, TimeUnit.SECONDS);
            assertEquals(1, one.getBytes());
            one.close();
            assertEquals(0, budget.getReservedBytes());
        } finally {
            full.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aWaitingReservationCanBeCancelled() throws Exception {
        ImageMemoryBudget budget = new ImageMemoryBudget();
        ImageMemoryBudget.Reservation full = budget.acquire(
                ImageMemoryBudget.MAX_PAYLOAD_BYTES,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                () -> false,
                () -> ImageFetchException.Reason.CANCELLED);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ImageMemoryBudget.Reservation> waiting = executor.submit(() -> budget.acquire(
                    1,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                    cancelled::get,
                    () -> ImageFetchException.Reason.CANCELLED));
            Thread.sleep(100);
            cancelled.set(true);
            budget.signalWaiters();
            ExecutionException exception = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> waiting.get(3, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof ImageFetchException);
            assertEquals(ImageFetchException.Reason.CANCELLED,
                    ((ImageFetchException) exception.getCause()).getReason());
        } finally {
            full.close();
            executor.shutdownNow();
        }
    }

    @Test
    void actualKnownLengthRequestNearLimitCompletesAndReleasesItsReservation() throws Exception {
        int maxBytes = 32 * 1024 * 1024;
        byte[] payload = new byte[maxBytes - 1024];
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             IPicaClient client = client(fixture, maxBytes)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(new Buffer().write(payload)));
            PicaImageRequest first = client.newImageRequest(image(fixture, "known-near-limit.png"));
            try {
                assertEquals(payload.length, first.execute().length);
            } finally {
                first.close();
            }
            assertEquals(1, fixture.server.getRequestCount());

            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png").setBody("released"));
            assertEquals("released", new String(client.fetchImageBytes(
                    image(fixture, "known-after-release.png"))));
            assertEquals(2, fixture.server.getRequestCount());
        }
    }

    @Test
    void actualUnknownLengthRequestReservesWaitsCancelsAndReleasesBudget() throws Exception {
        int maxBytes = 32 * 1024 * 1024;
        byte[] payload = new byte[maxBytes - 1024];
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             IPicaClient client = client(fixture, maxBytes)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg")
                    .setChunkedBody(new Buffer().write(payload), 64 * 1024));
            PicaImageRequest first = client.newImageRequest(image(fixture, "unknown-near-limit.jpg"));
            try {
                assertEquals(payload.length, first.execute().length);
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));

                fixture.server.enqueue(new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "image/jpeg").setBody("waiting"));
                PicaImageRequest waiting = client.newImageRequest(image(fixture, "waiting.jpg"));
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<byte[]> future = executor.submit(waiting::execute);
                    assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
                    waiting.cancel();
                    ExecutionException exception = org.junit.jupiter.api.Assertions.assertThrows(
                            ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                    assertTrue(exception.getCause() instanceof ImageFetchException);
                    assertEquals(ImageFetchException.Reason.CANCELLED,
                            ((ImageFetchException) exception.getCause()).getReason());
                } finally {
                    waiting.close();
                    executor.shutdownNow();
                }
            } finally {
                first.close();
            }

            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg").setBody("released"));
            assertEquals("released", new String(client.fetchImageBytes(
                    image(fixture, "unknown-after-release.jpg"))));
        }
    }

    private static IPicaClient client(LocalTlsFixture fixture, int maxBytes) {
        PicaConfiguration config = new PicaConfiguration.Builder()
                .domains(List.of("api-one.test"))
                .maxImageBytes(maxBytes)
                .timeout(Duration.ofSeconds(10))
                .build();
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                fixture.socketFactory));
    }

    private static PicaImage image(LocalTlsFixture fixture, String name) {
        return new PicaImage(name, "", "", fixture.url("s2.picacomic.com", "/static/" + name));
    }

}
