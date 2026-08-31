package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.net.LocalTlsClientContextFactory;
import io.github.jukomu.picacomic.core.util.FileUtils;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
