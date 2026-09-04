package io.github.jukomu.picacomic.external;

import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.enums.TimeOption;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从 core 实现包之外的包编译并验证公开 API。
 */
class PublicSurfaceCompilationTest {

    @TempDir
    Path tempDir;

    @Test
    void externalConsumersCanUseOnlyTheFactoryAndApiContracts() throws IOException {
        String allowed = """
                package external.consumer;
                import io.github.jukomu.picacomic.api.client.IPicaClient;
                import io.github.jukomu.picacomic.api.client.PicaImageRequest;
                import io.github.jukomu.picacomic.api.client.PicaRequest;
                import io.github.jukomu.picacomic.api.enums.TimeOption;
                import io.github.jukomu.picacomic.api.model.PicaImage;
                import io.github.jukomu.picacomic.api.model.PicaSessionSnapshot;
                import io.github.jukomu.picacomic.api.model.SearchQuery;
                import io.github.jukomu.picacomic.core.PicaComic;
                import io.github.jukomu.picacomic.core.config.PicaConfiguration;
                class AllowedConsumer {
                    void createAndClose() {
                        PicaConfiguration config = new PicaConfiguration.Builder().build();
                        try (IPicaClient client = PicaComic.newApiClient(config)) {
                            PicaSessionSnapshot session = client.getSession();
                            SearchQuery query = new SearchQuery.Builder().build();
                            PicaRequest<?> album = client.newAlbumRequest("album-id");
                            PicaRequest<?> albumRefresh = client.newAlbumRefreshRequest("album-id");
                            PicaRequest<?> photo = client.newPhotoRequest("album-id", "chapter-id");
                            PicaRequest<?> photoRefresh = client.newPhotoRefreshRequest(
                                    "album-id", "chapter-id");
                            PicaRequest<?> photoByOrder = client.newPhotoByOrderRequest("album-id", 1);
                            PicaRequest<?> search = client.newSearchRequest(query);
                            PicaRequest<?> favorites = client.newFavoritesRequest(query);
                            PicaRequest<?> categories = client.newCategoriesRequest(query);
                            PicaRequest<?> leaderboard = client.newLeaderboardRequest(TimeOption.DAY1);
                            PicaRequest<?> knights = client.newKnightLeaderboardRequest();
                            PicaRequest<?> random = client.newRandomAlbumsRequest();
                            PicaRequest<?> user = client.newUserInfoRequest();
                            PicaRequest<?> login = client.newLoginRequest("fixture-user", "fixture-password");
                            PicaImageRequest request = client.newImageRequest(
                                    new PicaImage("image.png", "", "https://s2.picacomic.com", null));
                            client.logout();
                            session.getState();
                            album.close();
                            albumRefresh.close();
                            photo.close();
                            photoRefresh.close();
                            photoByOrder.close();
                            search.close();
                            favorites.close();
                            categories.close();
                            leaderboard.close();
                            knights.close();
                            random.close();
                            user.close();
                            login.close();
                            request.close();
                        }
                    }
                }
                """;
        assertTrue(compile("AllowedConsumer", allowed), "approved public API must remain compilable");

        String forbidden = """
                package external.consumer;
                import io.github.jukomu.picacomic.core.DefaultPicaClient;
                import io.github.jukomu.picacomic.core.OkHttpBuilder;
                import io.github.jukomu.picacomic.core.OkHttpPicaImageRequest;
                import io.github.jukomu.picacomic.core.PicaDomainManager;
                import java.net.CookieManager;
                import okhttp3.OkHttpClient;
                class ForbiddenConsumer {
                    void bypass() {
                        OkHttpBuilder.HttpClientContext context = OkHttpBuilder.build(null);
                        OkHttpClient imageClient = context.getImageClient();
                        PicaDomainManager domains = context.getDomainManager();
                        CookieManager cookies = context.getCookieManager();
                        OkHttpPicaImageRequest request = null;
                        DefaultPicaClient concrete = null;
                    }
                }
                """;
        assertFalse(compile("ForbiddenConsumer", forbidden),
                "raw clients, domain state, cookies, and concrete requests must not compile externally");
    }

    @Test
    void removedImageSizeSurfaceAndReasonStayAbsent() throws Exception {
        assertThrows(NoSuchFieldException.class,
                () -> PicaConfiguration.class.getField("DEFAULT_MAX_IMAGE_BYTES"));
        assertThrows(NoSuchMethodException.class,
                () -> PicaConfiguration.class.getMethod("getMaxImageBytes"));
        assertThrows(NoSuchMethodException.class,
                () -> PicaConfiguration.Builder.class.getMethod("maxImageBytes", long.class));
        assertFalse(Arrays.stream(ImageFetchException.Reason.values())
                .anyMatch(reason -> "TOO_LARGE".equals(reason.name())));
    }

    @Test
    void publicCoreTypesAreLimitedToTheFactoryAndConfiguration() throws Exception {
        Path classesDirectory = Path.of("target/classes");
        Set<String> publicEntryPoints = Set.of(
                "io.github.jukomu.picacomic.core.PicaComic",
                "io.github.jukomu.picacomic.core.config.PicaConfiguration",
                "io.github.jukomu.picacomic.core.config.PicaConfiguration$Builder");
        try (Stream<Path> classes = Files.walk(classesDirectory)) {
            classes.filter(path -> path.toString().endsWith(".class"))
                    .map(classesDirectory::relativize)
                    .map(path -> path.toString()
                            .replace(Path.of("\\").toString(), ".")
                            .replace('/', '.')
                            .replace('\\', '.')
                            .replaceFirst("\\.class$", ""))
                    .filter(name -> !name.equals("module-info") && !name.equals("package-info"))
                    .forEach(name -> {
                        try {
                            Class<?> type = Class.forName(
                                    name,
                                    false,
                                    ClassLoader.getSystemClassLoader());
                            if (Modifier.isPublic(type.getModifiers())) {
                                assertTrue(publicEntryPoints.contains(type.getName())
                                                || type.getName().startsWith(
                                                "io.github.jukomu.picacomic.core.internal."),
                                        "unexpected public core type: " + type.getName());
                            }
                        } catch (ClassNotFoundException exception) {
                            throw new AssertionError("cannot inspect core class " + name, exception);
                        }
                    });
        }
    }

    private boolean compile(String className, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests require a JDK compiler");

        Path sourceFile = tempDir.resolve(className + ".java");
        Path outputDirectory = tempDir.resolve(className + "-classes");
        Files.createDirectories(outputDirectory);
        Files.writeString(sourceFile, source);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(sourceFile.toFile());
            Boolean compiled = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                            "-d", outputDirectory.toString()),
                    null,
                    units).call();
            return Boolean.TRUE.equals(compiled);
        }
    }
}
