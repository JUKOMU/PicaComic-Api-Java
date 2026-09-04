package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

/** Shared local-only fixtures for the U2 contract matrix. */
final class U2TestSupport {

    private U2TestSupport() {
    }

    static PicaConfiguration config(List<String> domains, int retryTimes) {
        return new PicaConfiguration.Builder()
                .domains(domains)
                .timeout(Duration.ofSeconds(3))
                .retryTimes(retryTimes)
                .domainProbeIntervalMs(0)
                .build();
    }

    static DefaultPicaClient client(LocalTlsFixture fixture) {
        return client(fixture, config(List.of("api-one.test"), 0));
    }

    static DefaultPicaClient client(LocalTlsFixture fixture, PicaConfiguration config) {
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                fixture.socketFactory));
    }

    static DefaultPicaClient instrumentedClient(LocalTlsFixture fixture,
                                                PicaConfiguration config,
                                                Runnable afterLoginCommit,
                                                Runnable beforeCacheCommit) {
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                fixture.socketFactory), U2TestSupport::writeImageFile,
                io.github.jukomu.picacomic.core.internal.util.FileUtils::moveAtomically,
                afterLoginCommit, beforeCacheCommit);
    }

    static MockResponse probe() {
        return new MockResponse().setResponseCode(200);
    }

    static MockResponse signIn() {
        return envelope("{\"token\":\"fixture-token\"}");
    }

    static MockResponse profile(String id, String name) {
        return envelope("{\"user\":{\"_id\":\"" + id + "\",\"name\":\"" + name + "\"}}");
    }

    static MockResponse profileWithNestedModels(String id, String name) {
        return envelope("{\"user\":{\"_id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"characters\":[\"character\"],\"avatar\":{\"originalName\":\"avatar.png\","
                + "\"path\":\"avatar\",\"fileServer\":\"https://s2.picacomic.com\"}}}");
    }

    static MockResponse albumEps(String... summaries) {
        return albumEpsPage(1, 1, summaries);
    }

    static MockResponse albumEpsPage(int page, int pages, String... summaries) {
        return envelope("{\"eps\":{\"total\":" + summaries.length
                + ",\"page\":" + page + ",\"pages\":" + pages
                + ",\"docs\":[" + String.join(",", summaries) + "]}}");
    }

    static String photoSummary(String id, String title, int order) {
        return "{\"_id\":\"" + id + "\",\"title\":\"" + title
                + "\",\"order\":" + order + ",\"updated_at\":\"\"}";
    }

    static MockResponse albumDetail(String id, String title) {
        return envelope("{\"comic\":{\"_id\":\"" + id + "\",\"title\":\""
                + title + "\",\"categories\":[],\"tags\":[]}}");
    }

    static MockResponse albumDetailWithNestedModels(String id, String title) {
        return envelope("{\"comic\":{\"_id\":\"" + id + "\",\"title\":\""
                + title + "\",\"categories\":[\"category\"],\"tags\":[\"tag\"],"
                + "\"_creator\":{\"_id\":\"creator\",\"name\":\"Creator\","
                + "\"characters\":[\"character\"],\"avatar\":{\"originalName\":\"avatar.png\","
                + "\"path\":\"avatar\",\"fileServer\":\"https://s2.picacomic.com\"}}}}");
    }

    static MockResponse photoPages(String chapterId, String imageName) {
        return photoPage(chapterId, imageName, 1, 1);
    }

    static MockResponse photoPage(String chapterId, String imageName, int page, int pages) {
        return envelope("{\"ep\":{\"_id\":\"" + chapterId
                + "\"},\"pages\":{\"page\":" + page + ",\"pages\":" + pages
                + ",\"docs\":[{\"media\":{\"originalName\":\""
                + imageName + "\",\"path\":\"image\",\"fileServer\":\"https://s2.picacomic.com\"}}]}}");
    }

    static MockResponse contentPage(String title) {
        return envelope("{\"comics\":{\"docs\":[{\"_id\":\"album-" + title
                + "\",\"title\":\"" + title + "\",\"categories\":[],\"tags\":[]}],"
                + "\"page\":1,\"pages\":1,\"total\":1,\"limit\":20}}");
    }

    static MockResponse contentPageWithNestedModels(String title) {
        return envelope("{\"comics\":{\"docs\":[{\"_id\":\"album-" + title
                + "\",\"title\":\"" + title + "\",\"categories\":[\"category\"],"
                + "\"tags\":[\"tag\"],\"_creator\":{\"_id\":\"creator\","
                + "\"name\":\"Creator\",\"characters\":[\"character\"]}}],"
                + "\"page\":1,\"pages\":1,\"total\":1,\"limit\":20}}");
    }

    static MockResponse envelope(String data) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":" + data + "}");
    }

    static void loginFixture(LocalTlsFixture fixture, String id, String name) {
        fixture.server.enqueue(probe());
        fixture.server.enqueue(signIn());
        fixture.server.enqueue(profile(id, name));
    }

    private static void writeImageFile(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes, StandardOpenOption.WRITE);
    }
}
