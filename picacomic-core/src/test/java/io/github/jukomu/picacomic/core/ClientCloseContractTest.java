package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.util.FileUtils;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCloseContractTest {

    @TempDir
    Path tempDir;

    @Test
    void closeIsIdempotentAndPreCreatedRequestsReportClientClosed() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            DefaultPicaClient client = U2TestSupport.client(fixture,
                    new PicaConfiguration.Builder()
                            .domains(List.of("api-one.test"))
                            .domainProbeIntervalMs(0)
                            .closeTimeoutMs(0)
                            .build());
            PicaRequest<?> request = client.newCategoriesRequest(new SearchQuery.Builder().build());
            ExecutorService closers = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> closeCalls = List.of(
                        closers.submit(client::close),
                        closers.submit(client::close),
                        closers.submit(client::close),
                        closers.submit(client::close));
                for (Future<?> closeCall : closeCalls) {
                    closeCall.get(3, TimeUnit.SECONDS);
                }
                assertTrue(request.isCancelled());
                PicaApiException failure = assertThrows(PicaApiException.class, request::execute);
                assertEquals(PicaApiException.Reason.CLIENT_CLOSED, failure.getReason());
                assertEquals(io.github.jukomu.picacomic.api.enums.PicaSessionState.SIGNED_OUT,
                        client.getSession().state());
            } finally {
                request.close();
                closers.shutdownNow();
                client.close();
            }
        }
    }

    @Test
    void closeUsesOneBudgetEvenWhenAnOwnedDownloadWorkerIgnoresInterrupt() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("image"));
            CountDownLatch writerStarted = new CountDownLatch(1);
            CountDownLatch releaseWriter = new CountDownLatch(1);
            DefaultPicaClient.ImageFileWriter writer = (temporary, bytes) -> {
                writerStarted.countDown();
                for (;;) {
                    try {
                        if (releaseWriter.await(50, TimeUnit.MILLISECONDS)) {
                            break;
                        }
                    } catch (InterruptedException ignored) {
                        // 在测试释放夹具前保持 worker 存活。
                    }
                }
                Files.write(temporary, bytes, StandardOpenOption.WRITE);
            };
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(List.of("api-one.test"))
                    .domainProbeIntervalMs(0)
                    .downloadThreadPoolSize(1)
                    .closeTimeoutMs(100)
                    .timeout(Duration.ofSeconds(3))
                    .build();
            DefaultPicaClient client = new DefaultPicaClient(config,
                    LocalTlsClientContextFactory.build(config, fixture.dns,
                            fixture.clientCertificates.sslSocketFactory(),
                            fixture.clientCertificates.trustManager(), fixture.socketFactory),
                    writer, FileUtils::moveAtomically);
            ExecutorService coordinator = Executors.newSingleThreadExecutor();
            PicaPhoto photo = new PicaPhoto("album", "chapter", "chapter", "", 1,
                    List.of(new PicaImage("image.png", "", "",
                            fixture.url("image-one.test", "/static/image.png"))), false);
            try {
                Future<DownloadResult> batch = coordinator.submit(
                        () -> client.downloadPhoto(photo, tempDir.resolve("close-budget")));
                assertTrue(writerStarted.await(3, TimeUnit.SECONDS));
                long started = System.nanoTime();
                client.close();
                long elapsedNanos = System.nanoTime() - started;
                assertTrue(elapsedNanos < TimeUnit.SECONDS.toNanos(2),
                        "close exceeded its single total budget");
                releaseWriter.countDown();
                DownloadResult result = batch.get(3, TimeUnit.SECONDS);
                assertTrue(result.getSuccessfulFiles().isEmpty());
                assertEquals(1, result.getFailedTasks().size());
            } finally {
                releaseWriter.countDown();
                client.close();
                coordinator.shutdownNow();
            }
        }
    }

    @Test
    void closeComputesRemainingFromElapsedTimeAcrossNanoTimeWrap() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("image"));
            CountDownLatch writerStarted = new CountDownLatch(1);
            CountDownLatch releaseWriter = new CountDownLatch(1);
            CountDownLatch writerFinished = new CountDownLatch(1);
            DefaultPicaClient.ImageFileWriter writer = (temporary, bytes) -> {
                writerStarted.countDown();
                try {
                    for (;;) {
                        try {
                            if (releaseWriter.await(50, TimeUnit.MILLISECONDS)) {
                                break;
                            }
                        } catch (InterruptedException ignored) {
                            // 在测试释放夹具前保持自有 worker 存活。
                        }
                    }
                    Files.write(temporary, bytes, StandardOpenOption.WRITE);
                } finally {
                    writerFinished.countDown();
                }
            };
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(List.of("api-one.test"))
                    .domainProbeIntervalMs(0)
                    .downloadThreadPoolSize(1)
                    .closeTimeoutMs(1)
                    .timeout(Duration.ofSeconds(3))
                    .build();
            AtomicInteger clockReads = new AtomicInteger();
            LongSupplier clock = () -> clockReads.getAndIncrement() == 0
                    ? Long.MAX_VALUE - 50
                    : Long.MIN_VALUE + 50;
            DefaultPicaClient client = new DefaultPicaClient(config,
                    LocalTlsClientContextFactory.build(config, fixture.dns,
                            fixture.clientCertificates.sslSocketFactory(),
                            fixture.clientCertificates.trustManager(), fixture.socketFactory),
                    writer, FileUtils::moveAtomically, () -> { }, () -> { }, clock);
            ExecutorService coordinator = Executors.newSingleThreadExecutor();
            ExecutorService closer = Executors.newSingleThreadExecutor();
            PicaPhoto photo = new PicaPhoto("album", "chapter", "chapter", "", 1,
                    List.of(new PicaImage("image.png", "", "",
                            fixture.url("image-one.test", "/static/image.png"))), false);
            try {
                Future<DownloadResult> batch = coordinator.submit(
                        () -> client.downloadPhoto(photo, tempDir.resolve("wrap-budget")));
                assertTrue(writerStarted.await(3, TimeUnit.SECONDS));
                Future<?> close = closer.submit(client::close);

                close.get(3, TimeUnit.SECONDS);
                assertEquals(2, clockReads.get(), "close must sample start and elapsed time");

                releaseWriter.countDown();
                assertTrue(writerFinished.await(3, TimeUnit.SECONDS));
                DownloadResult result = batch.get(3, TimeUnit.SECONDS);
                assertTrue(result.getSuccessfulFiles().isEmpty());
                assertEquals(1, result.getFailedTasks().size());
            } finally {
                releaseWriter.countDown();
                client.close();
                coordinator.shutdownNow();
                closer.shutdownNow();
            }
        }
    }

    @Test
    void closeNeverShutsDownAnExternalExecutorAndItRemainsUsable() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            ExecutorService external = Executors.newSingleThreadExecutor();
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(List.of("api-one.test"))
                    .domainProbeIntervalMs(0)
                    .executor(external)
                    .closeTimeoutMs(0)
                    .build();
            DefaultPicaClient client = U2TestSupport.client(fixture, config);
            try {
                client.close();
                assertFalse(external.isShutdown());
                assertEquals("usable", external.submit(() -> "usable").get(3, TimeUnit.SECONDS));
            } finally {
                client.close();
                external.shutdownNow();
            }
        }
    }

    @Test
    void closeClearsPendingApiCallsBeforeClosingTransport() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            DefaultPicaClient client = U2TestSupport.client(fixture);
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(new MockResponse()
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            PicaRequest<?> request = client.newCategoriesRequest(new SearchQuery.Builder().build());
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = caller.submit(request::execute);
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
                client.close();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> future.get(3, TimeUnit.SECONDS));
                assertEquals(PicaApiException.Reason.CLIENT_CLOSED,
                        ((PicaApiException) failure.getCause()).getReason());
            } finally {
                request.close();
                caller.shutdownNow();
                client.close();
            }
        }
    }
}
