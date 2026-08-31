package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.constant.PicaConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PicaConfigurationIsolationTest {

    @Test
    void configurationIsAnImmutableValueSnapshotAndFactoryReturnsInterface() {
        List<String> domains = new ArrayList<>(List.of("API-ONE.test", "api-two.test"));
        PicaConfiguration configuration = new PicaConfiguration.Builder()
                .domains(domains)
                .timeout(Duration.ofSeconds(2))
                .concurrentImageDownloads(4)
                .maxImageBytes(1024)
                .build();
        domains.set(0, "changed.test");

        assertEquals(List.of("api-one.test", "api-two.test"), configuration.getDomains());
        assertEquals(1024, configuration.getMaxImageBytes());
        assertEquals(4, configuration.getConcurrentImageDownloads());
        assertThrows(UnsupportedOperationException.class,
                () -> configuration.getDomains().add("other.test"));

        IPicaClient client = PicaComic.newApiClient(configuration);
        try {
            assertFalse(Modifier.isPublic(client.getClass().getModifiers()));
            assertEquals("DefaultPicaClient", client.getClass().getSimpleName());
        } finally {
            client.close();
        }
    }

    @Test
    void invalidSecurityRelevantConfigurationFailsBeforeClientConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().domains(List.of("https://api.test")).build());
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().domains(List.of("127.0.0.1")).build());
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().domains(List.of("api.test", "API.TEST")).build());
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().timeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().proxy(" ", 8080));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().proxy("127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().concurrentImageDownloads(0));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().concurrentImageDownloads(5));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().maxImageBytes(0));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().maxImageBytes(33L * 1024 * 1024));
    }

    @Test
    void resultsAndAllowlistDoNotExposeMutableBackingCollections() {
        PicaImage image = new PicaImage("one.png", "p", "https://s2.picacomic.com", null);
        List<Path> files = new ArrayList<>(List.of(Path.of("one.png")));
        Map<PicaImage, Exception> failures = new HashMap<>();
        DownloadResult result = new DownloadResult(files, failures);
        files.clear();
        failures.put(image, new Exception("late"));

        assertEquals(1, result.getSuccessfulFiles().size());
        assertTrue(result.getFailedTasks().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> result.getSuccessfulFiles().add(Path.of("two.png")));
        assertThrows(UnsupportedOperationException.class,
                () -> result.getFailedTasks().put(image, new Exception()));
        assertThrows(UnsupportedOperationException.class,
                () -> PicaConstants.DEFAULT_DOMAINS.add("other.test"));
        assertThrows(UnsupportedOperationException.class,
                () -> PicaConstants.IMAGE_HOST_ALLOWLIST.add("other.test"));
        assertEquals(java.util.Set.of(
                        "img.picacomic.com", "s2.picacomic.com", "s3.picacomic.com",
                        "storage.picacomic.com", "storage1.picacomic.com", "storage-b.picacomic.com"),
                PicaConstants.IMAGE_HOST_ALLOWLIST);
    }

    @Test
    void incompleteImageLocatorHasStableModelBehavior() {
        PicaImage image = new PicaImage("one.png", null, null, null);
        assertThrows(IllegalStateException.class, image::getImageUrl);
    }
}
