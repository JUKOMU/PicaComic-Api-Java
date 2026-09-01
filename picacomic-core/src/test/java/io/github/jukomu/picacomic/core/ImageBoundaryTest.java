package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageBoundaryTest {

    @Test
    void imageRequestIsAGetWithoutApiCredentialsOrBody() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("image"));
            assertEquals("image", new String(client.fetchImageBytes(
                    image(fixture, "image-one.test", "one.png"))));
            var request = fixture.server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("GET", request.getMethod());
            assertEquals(0, request.getBodySize());
            assertFalse(hasAnyHeader(request, "Cookie", "authorization", "signature", "nonce", "time",
                    "app-channel", "app-uuid", "app-version", "app-platform", "Content-Type",
                    "Origin", "Referer"));
        }
    }

    @Test
    void arbitraryHttpsSourcesAndFieldLocatorsAreAcceptedButIncompleteLocatorsFail() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("direct"));
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("fields"));
            assertEquals("direct", new String(client.fetchImageBytes(
                    image(fixture, "unknown.test", "outside-static/path.png"))));
            PicaImage fromFields = new PicaImage("field.gif", "folder/field.gif",
                    "https://unknown.test/", null);
            assertEquals("fields", new String(client.fetchImageBytes(fromFields)));
            assertThrows(ImageFetchException.class,
                    () -> client.fetchImageBytes(new PicaImage("missing", null, null, null)));
            assertEquals(2, fixture.server.getRequestCount());
        }
    }

    @Test
    void successful2xxResponsesMissingMimeAndUsingGzipAreAccepted() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(206).setBody("partial"));
            fixture.server.enqueue(new MockResponse().setResponseCode(201)
                    .setHeader("Content-Type", "text/html").setBody("ordinary"));
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Encoding", "gzip")
                    .setBody(new Buffer().write(gzip("compressed"))));
            assertEquals("partial", new String(client.fetchImageBytes(
                    image(fixture, "image-one.test", "partial"))));
            assertEquals("ordinary", new String(client.fetchImageBytes(
                    image(fixture, "image-one.test", "ordinary"))));
            assertEquals("compressed", new String(client.fetchImageBytes(
                    image(fixture, "image-one.test", "compressed"))));
        }
    }

    @Test
    void fixedAndUnknownLengthBodiesAreReadThroughEof() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Length", "5").setBody("12345"));
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setChunkedBody("12345", 2));
            assertEquals("12345", new String(client.fetchImageBytes(
                    image(fixture, "image-one.test", "known"))));
            assertEquals("12345", new String(client.fetchImageBytes(
                    image(fixture, "image-one.test", "chunked"))));
            assertEquals(2, fixture.server.getRequestCount());
        }
    }

    @Test
    void fixedLengthEarlyEofIsReportedWithoutReturningPartialBytes() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("abc").setHeader("Content-Length", "4")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
            ImageFetchException exception = assertThrows(ImageFetchException.class,
                    () -> client.fetchImageBytes(image(fixture, "image-one.test", "truncated")));
            assertEquals(ImageFetchException.Reason.TRUNCATED_BODY, exception.getReason());
        }
    }

    @Test
    void redirectsUseOkHttpDefaultBehaviorEvenWhenTheTargetHostChanges() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", "/redirected"));
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("relative"));
            fixture.server.enqueue(new MockResponse().setResponseCode(307)
                    .setHeader("Location", fixture.url("unknown.test", "/outside")));
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("cross-host"));

            assertEquals("relative", new String(client.fetchImageBytes(
                    image(fixture, "image-one.test", "first"))));
            assertEquals("cross-host", new String(client.fetchImageBytes(
                    image(fixture, "image-one.test", "second"))));
            assertEquals(4, fixture.server.getRequestCount());
            assertEquals("image-one.test", recordedHost(fixture.server.takeRequest()));
            assertEquals("image-one.test", recordedHost(fixture.server.takeRequest()));
            assertEquals("image-one.test", recordedHost(fixture.server.takeRequest()));
            assertEquals("unknown.test", recordedHost(fixture.server.takeRequest()));
        }
    }

    @Test
    void cancellationDoesNotRetryAndImageReadTimeoutIsIndependent() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("0123456789").throttleBody(1, 1, TimeUnit.SECONDS));
            try (IPicaClient client = client(fixture, Duration.ofSeconds(3))) {
                var request = client.newImageRequest(image(fixture, "image-one.test", "slow"));
                ExecutorService caller = Executors.newSingleThreadExecutor();
                try {
                    Future<byte[]> future = caller.submit(request::execute);
                    assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
                    request.cancel();
                    ExecutionException execution = assertThrows(ExecutionException.class,
                            () -> future.get(3, TimeUnit.SECONDS));
                    assertEquals(ImageFetchException.Reason.CANCELLED,
                            ((ImageFetchException) execution.getCause()).getReason());
                    assertEquals(1, fixture.server.getRequestCount());
                } finally {
                    request.close();
                    caller.shutdownNow();
                }
            }
        }

        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture,
                Duration.ofMillis(100))) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeadersDelay(1, TimeUnit.SECONDS).setBody("late"));
            ImageFetchException exception = assertThrows(ImageFetchException.class,
                    () -> client.fetchImageBytes(image(fixture, "image-one.test", "timeout")));
            assertEquals(ImageFetchException.Reason.TIMEOUT, exception.getReason());
        }
    }

    @Test
    void clientCloseCancelsImageAndDoesNotOwnExternalExecutor() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("0123456789").throttleBody(1, 1, TimeUnit.SECONDS));
            ExecutorService external = Executors.newSingleThreadExecutor();
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(List.of("api-one.test"))
                    .executor(external)
                    .timeout(Duration.ofSeconds(3))
                    .build();
            DefaultPicaClient client = new DefaultPicaClient(config,
                    LocalTlsClientContextFactory.build(config, fixture.dns,
                            fixture.clientCertificates.sslSocketFactory(),
                            fixture.clientCertificates.trustManager(), fixture.socketFactory));
            var request = client.newImageRequest(image(fixture, "image-one.test", "close"));
            Future<byte[]> future = external.submit(request::execute);
            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
            client.close();
            ExecutionException execution = assertThrows(ExecutionException.class,
                    () -> future.get(3, TimeUnit.SECONDS));
            assertEquals(ImageFetchException.Reason.CLIENT_CLOSED,
                    ((ImageFetchException) execution.getCause()).getReason());
            assertTrue(request.isCancelled());
            assertFalse(external.isShutdown());
            external.shutdownNow();
        }
    }

    private static IPicaClient client(LocalTlsFixture fixture) {
        return client(fixture, Duration.ofSeconds(3));
    }

    private static IPicaClient client(LocalTlsFixture fixture, Duration imageTimeout) {
        PicaConfiguration config = new PicaConfiguration.Builder()
                .domains(List.of("api-one.test", "api-two.test"))
                .timeout(Duration.ofSeconds(3))
                .imageTimeout(imageTimeout)
                .retryTimes(1)
                .build();
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                fixture.socketFactory));
    }

    private static PicaImage image(LocalTlsFixture fixture, String host, String name) {
        return new PicaImage(name, "", "", fixture.url(host, "/static/" + name));
    }

    private static byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static boolean hasAnyHeader(okhttp3.mockwebserver.RecordedRequest request, String... names) {
        for (String name : names) {
            if (request.getHeader(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static String recordedHost(okhttp3.mockwebserver.RecordedRequest request) {
        String host = request.getHeader("Host");
        return host == null ? request.getHeader(":authority") : host;
    }
}
