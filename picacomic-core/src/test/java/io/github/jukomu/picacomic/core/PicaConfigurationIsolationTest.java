package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.constant.PicaConstants;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
                .downloadThreadPoolSize(11)
                .concurrentPhotoDownloads(7)
                .concurrentImageDownloads(9)
                .build();
        domains.set(0, "changed.test");

        assertEquals(List.of("api-one.test", "api-two.test"), configuration.getDomains());
        assertEquals(11, configuration.getDownloadThreadPoolSize());
        assertEquals(7, configuration.getConcurrentPhotoDownloads());
        assertEquals(9, configuration.getConcurrentImageDownloads());
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
    void concurrencyDefaultsAndPropertiesAreRealConfiguration() throws Exception {
        PicaConfiguration defaults = new PicaConfiguration.Builder().build();
        assertEquals(12, defaults.getDownloadThreadPoolSize());
        assertEquals(3, defaults.getConcurrentPhotoDownloads());
        assertEquals(20, defaults.getConcurrentImageDownloads());

        PicaConfiguration fromProperties = new PicaConfiguration.Builder()
                .loadFromProperties(new ByteArrayInputStream((
                        "download.thread.pool.size=17\n"
                                + "concurrent.photo.downloads=19\n"
                                + "concurrent.image.downloads=23\n")
                        .getBytes(StandardCharsets.UTF_8)))
                .build();
        assertEquals(17, fromProperties.getDownloadThreadPoolSize());
        assertEquals(19, fromProperties.getConcurrentPhotoDownloads());
        assertEquals(23, fromProperties.getConcurrentImageDownloads());
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
                () -> new PicaConfiguration.Builder().downloadThreadPoolSize(0));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().downloadThreadPoolSize(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().concurrentImageDownloads(0));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().concurrentImageDownloads(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().concurrentPhotoDownloads(0));
        assertThrows(IllegalArgumentException.class,
                () -> new PicaConfiguration.Builder().concurrentPhotoDownloads(-1));

        PicaConfiguration largePositive = new PicaConfiguration.Builder()
                .downloadThreadPoolSize(5)
                .concurrentPhotoDownloads(5)
                .concurrentImageDownloads(5)
                .build();
        assertEquals(5, largePositive.getDownloadThreadPoolSize());
        assertEquals(5, largePositive.getConcurrentPhotoDownloads());
        assertEquals(5, largePositive.getConcurrentImageDownloads());
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
