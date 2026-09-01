package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.exception.ImageFetchException;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageBoundaryTest {

    @Test
    void imageRequestIsBlankGetAndDoesNotInheritApiCredentials() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "session=secret-cookie; Path=/")
                    .setBody("{\"data\":{\"token\":\"secret-token\"}}"));
            fixture.server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody("png-fixture"));
            try (IPicaClient client = client(fixture)) {
                client.login("fixture-user", "fixture-password");
                byte[] bytes = client.fetchImageBytes(image(fixture, "s2.picacomic.com", "one.png"));
                assertEquals("png-fixture", new String(bytes));
            }
            assertEquals(2, fixture.server.getRequestCount());
            var apiRequest = fixture.server.takeRequest();
            var imageRequest = fixture.server.takeRequest();
            assertEquals("POST", apiRequest.getMethod());
            assertEquals("GET", imageRequest.getMethod());
            assertEquals(0, imageRequest.getBodySize());
            assertEquals("identity", imageRequest.getHeader("Accept-Encoding"));
            assertFalse(hasAnyHeader(imageRequest, "Cookie", "authorization", "signature", "nonce",
                    "time", "app-channel", "app-uuid", "app-version", "app-platform",
                    "Content-Type", "Origin", "Referer"));
        }
    }

    @Test
    void unknownSourcesAreRejectedBeforeNetwork() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            assertReason(client, new PicaImage("x", "x", "https://unknown.test", null),
                    ImageFetchException.Reason.DISALLOWED_HOST);
            assertReason(client, new PicaImage("x", "x", "http://s2.picacomic.com", null),
                    ImageFetchException.Reason.DISALLOWED_HOST);
            assertReason(client, new PicaImage("x", "x", "https://s2.picacomic.com:8443", null),
                    ImageFetchException.Reason.DISALLOWED_HOST);
            assertReason(client, new PicaImage("x", "x", "https://user@s2.picacomic.com", null),
                    ImageFetchException.Reason.DISALLOWED_HOST);
            assertReason(client, new PicaImage("x", "../x", "https://s2.picacomic.com", null),
                    ImageFetchException.Reason.INVALID_SOURCE);
            assertReason(client, new PicaImage("x", null, null, null),
                    ImageFetchException.Reason.INVALID_SOURCE);
            assertEquals(0, fixture.server.getRequestCount());
        }
    }

    @Test
    void onlyHttp200RasterIdentityResponsesSucceed() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(206)
                    .setHeader("Content-Type", "image/png").setBody("partial"));
            assertReason(client, image(fixture, "s2.picacomic.com", "partial.png"),
                    ImageFetchException.Reason.HTTP_STATUS);

            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/html").setBody("html"));
            assertReason(client, image(fixture, "s2.picacomic.com", "html.png"),
                    ImageFetchException.Reason.UNSUPPORTED_MEDIA_TYPE);

            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setHeader("Content-Encoding", "gzip").setBody("compressed"));
            assertReason(client, image(fixture, "s2.picacomic.com", "gzip.png"),
                    ImageFetchException.Reason.UNSUPPORTED_CONTENT_ENCODING);
        }
    }

    @Test
    void fileServerAndPathAreCombinedThroughTheSamePolicy() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/gif").setBody("gif"));
            PicaImage fromFields = new PicaImage("field.gif", "folder/field.gif",
                    "https://s2.picacomic.com/", null);
            assertEquals("gif", new String(client.fetchImageBytes(fromFields)));
            assertEquals(1, fixture.server.getRequestCount());
        }
    }

    @Test
    void smallFixedAndChunkedBodiesAreReadToEof() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setHeader("Content-Length", "5").setBody("12345"));
            assertEquals("12345", new String(client.fetchImageBytes(
                    image(fixture, "s2.picacomic.com", "known.png"))));

            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setChunkedBody("12345", 2));
            assertEquals("12345", new String(client.fetchImageBytes(
                    image(fixture, "s2.picacomic.com", "chunked.png"))));
            assertEquals(2, fixture.server.getRequestCount());
        }
    }

    @Test
    void fixedLengthEarlyEofIsStableAndDoesNotReturnPartialBytes() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody("abc").setHeader("Content-Length", "4")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
            assertReason(client, image(fixture, "s2.picacomic.com", "truncated.png"),
                    ImageFetchException.Reason.TRUNCATED_BODY);
        }
    }

    @Test
    void redirectsAreManualFreshBlankRequestsAndUnknownTargetsAreRejected() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", "/static/second.png"));
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/jpeg").setBody("redirected"));
            assertEquals("redirected", new String(client.fetchImageBytes(
                    image(fixture, "s2.picacomic.com", "first.png"))));
            assertEquals(2, fixture.server.getRequestCount());
            var first = fixture.server.takeRequest();
            var second = fixture.server.takeRequest();
            assertEquals("GET", first.getMethod());
            assertEquals("GET", second.getMethod());
            assertFalse(hasAnyHeader(second, "Cookie", "authorization", "signature", "Origin", "Referer"));

            fixture.server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", fixture.url("unknown.test", "/static/nope.png")));
            assertReason(client, image(fixture, "s2.picacomic.com", "unknown-redirect.png"),
                    ImageFetchException.Reason.REDIRECT_REJECTED);
            assertEquals(3, fixture.server.getRequestCount());
        }
    }

    @Test
    void crossAllowlistedHostRedirectIsRevalidated() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture)) {
            fixture.server.enqueue(new MockResponse().setResponseCode(307)
                    .setHeader("Location", fixture.url("s3.picacomic.com", "/static/second.webp")));
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/webp").setBody("webp"));
            assertEquals("webp", new String(client.fetchImageBytes(
                    image(fixture, "s2.picacomic.com", "first.webp"))));
            assertEquals(2, fixture.server.getRequestCount());
            assertEquals("s2.picacomic.com", recordedHost(fixture.server.takeRequest()));
            assertEquals("s3.picacomic.com", recordedHost(fixture.server.takeRequest()));
        }
    }

    @Test
    void cancellationAndTimeoutUseStableReasons() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody("0123456789").throttleBody(1, 1, TimeUnit.SECONDS));
            try (IPicaClient client = client(fixture)) {
                var request = client.newImageRequest(image(fixture, "s2.picacomic.com", "slow.png"));
                ExecutorService caller = Executors.newSingleThreadExecutor();
                try {
                    Future<byte[]> future = caller.submit(request::execute);
                    assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
                    request.cancel();
                    var execution = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> future.get(3, TimeUnit.SECONDS));
                    ImageFetchException exception = (ImageFetchException) execution.getCause();
                    assertEquals(ImageFetchException.Reason.CANCELLED, exception.getReason());
                    assertTrue(request.isCancelled());
                } finally {
                    request.close();
                    caller.shutdownNow();
                }
            }
        }

        try (LocalTlsFixture fixture = new LocalTlsFixture(); IPicaClient client = client(fixture,
                Duration.ofMillis(100))) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeadersDelay(1, TimeUnit.SECONDS)
                    .setHeader("Content-Type", "image/png").setBody("late"));
            ImageFetchException exception = assertThrows(ImageFetchException.class,
                    () -> client.fetchImageBytes(image(fixture, "s2.picacomic.com", "timeout.png")));
            assertEquals(ImageFetchException.Reason.TIMEOUT, exception.getReason());
        }
    }

    @Test
    void clientCloseCancelsInFlightImageAndLeavesExternalExecutorOwnedByCaller() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
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
            var request = client.newImageRequest(image(fixture, "s2.picacomic.com", "close.png"));
            Future<byte[]> future = external.submit(request::execute);
            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
            client.close();
            var execution = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(3, TimeUnit.SECONDS));
            assertEquals(ImageFetchException.Reason.CLIENT_CLOSED,
                    ((ImageFetchException) execution.getCause()).getReason());
            assertTrue(request.isCancelled());
            assertFalse(external.isShutdown());
            client.close();
            external.shutdownNow();
        }
    }

    private static IPicaClient client(LocalTlsFixture fixture) {
        return client(fixture, Duration.ofSeconds(3));
    }

    private static IPicaClient client(LocalTlsFixture fixture, Duration timeout) {
        PicaConfiguration config = new PicaConfiguration.Builder()
                .domains(List.of("api-one.test", "api-two.test"))
                .timeout(timeout)
                .retryTimes(1)
                .build();
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(),
                fixture.clientCertificates.trustManager(), fixture.socketFactory));
    }

    private static PicaImage image(LocalTlsFixture fixture, String host, String name) {
        return new PicaImage(name, "", "", fixture.url(host, "/static/" + name));
    }

    private static void assertReason(IPicaClient client, PicaImage image,
                                     ImageFetchException.Reason reason) {
        ImageFetchException exception = assertThrows(ImageFetchException.class,
                () -> client.fetchImageBytes(image));
        assertEquals(reason, exception.getReason());
        if (reason != ImageFetchException.Reason.HTTP_STATUS) {
            assertEquals(null, exception.getHttpStatus());
        }
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
