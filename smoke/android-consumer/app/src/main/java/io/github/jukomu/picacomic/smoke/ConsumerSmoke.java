package io.github.jukomu.picacomic.smoke;

import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import io.github.jukomu.picacomic.core.PicaComic;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Small no-credential surface used by the clean Android consumer. */
public final class ConsumerSmoke {

    private ConsumerSmoke() {
    }

    public static IPicaClient newClient() {
        return PicaComic.newApiClient(new PicaConfiguration.Builder()
                .domains(List.of("localhost"))
                .domainProbeIntervalMs(0)
                .build());
    }

    public static void assertCancelledHandleDoesNotNeedNetwork(IPicaClient client) {
        PicaRequest<?> request = client.newCategoriesRequest(new SearchQuery.Builder().build());
        try {
            request.cancel();
            try {
                request.execute();
                throw new AssertionError("cancelled handle unexpectedly completed");
            } catch (PicaApiException exception) {
                if (exception.getReason() != PicaApiException.Reason.CANCELLED) {
                    throw new AssertionError("unexpected cancellation reason", exception);
                }
            }
        } finally {
            request.close();
        }
    }

    public static void exerciseNio(Path cacheDirectory) throws IOException {
        Files.createDirectories(cacheDirectory);
        Path temporary = Files.createTempFile(cacheDirectory, "picacomic-", ".part");
        Path target = cacheDirectory.resolve("picacomic-nio-smoke.txt");
        try {
            Files.write(temporary, "desugared-nio".getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            if (!"desugared-nio".equals(content)) {
                throw new AssertionError("NIO round trip returned the wrong content");
            }
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(target);
        }
    }
}
