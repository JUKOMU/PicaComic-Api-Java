package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.exception.ResponseException;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import io.github.jukomu.picacomic.core.internal.net.provider.PicaDomainManager;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRetrySecurityTest {

    @Test
    void loginPostRetries403And5xxAcrossConfiguredHostsWithTheSameBody() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            enqueueProbeResponses(fixture, 2, 200);
            fixture.server.enqueue(new MockResponse().setResponseCode(503).setBody("temporary"));
            fixture.server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
            fixture.server.enqueue(new MockResponse().setResponseCode(502).setBody("temporary"));
            fixture.server.enqueue(loginResponse());
            fixture.server.enqueue(profileResponse());

            PicaConfiguration config = config(List.of("api-one.test", "api-two.test"), 3);
            try (IPicaClient client = newClient(fixture, config)) {
                assertNotNull(client.login("user@example.test", "fixture-password"));
            }

            assertEquals(7, fixture.server.getRequestCount());
            assertProbeRequests(fixture, 2);
            List<RecordedRequest> attempts = takeRequests(fixture, 4);
            List<String> bodies = attempts.stream().map(request -> request.getBody().readUtf8()).toList();
            String body = bodies.get(0);
            assertTrue(body.contains("fixture-password"));
            for (int i = 0; i < attempts.size(); i++) {
                RecordedRequest attempt = attempts.get(i);
                assertEquals("POST", attempt.getMethod());
                assertEquals(body, bodies.get(i));
                assertTrue(Set.of("api-one.test", "api-two.test").contains(recordedHost(attempt)));
            }
        }
    }

    @Test
    void postIoFailureIsRetriedWithTheRequestBodyIntact() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            PicaConfiguration config = config(List.of("api-one.test", "api-two.test"), 1);
            OkHttpBuilder.HttpClientContext context = LocalTlsClientContextFactory.build(config, fixture.dns,
                    fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                    fixture.socketFactory);
            String body = "{\"fixture\":\"body\"}";
            try (Response response = context.getApiClient().newCall(new Request.Builder()
                    .url("https://pica-placeholder.domain.com/auth/sign-in")
                    .post(RequestBody.create(body, MediaType.get("application/json")))
                    .build()).execute()) {
                assertEquals(200, response.code());
            } finally {
                closeContext(context);
            }
            RecordedRequest first = fixture.server.takeRequest(3, TimeUnit.SECONDS);
            RecordedRequest second = fixture.server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second);
            RecordedRequest successful = "POST".equals(first.getMethod()) ? first : second;
            assertEquals("POST", successful.getMethod());
            assertEquals(body, successful.getBody().readUtf8());
        }
    }

    @Test
    void apiRedirectUsesOkHttpDefaultRedirectBehavior() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", "/redirected"));
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            PicaConfiguration config = config(List.of("api-one.test"), 0);
            OkHttpBuilder.HttpClientContext context = LocalTlsClientContextFactory.build(config, fixture.dns,
                    fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                    fixture.socketFactory);
            try (Response response = context.getApiClient().newCall(new Request.Builder()
                    .url("https://pica-placeholder.domain.com/comics")
                    .get().build()).execute()) {
                assertEquals(200, response.code());
                assertEquals("/redirected", response.request().url().encodedPath());
            } finally {
                closeContext(context);
            }
            assertEquals(2, fixture.server.getRequestCount());
            assertEquals("GET", fixture.server.takeRequest().getMethod());
            assertEquals("GET", fixture.server.takeRequest().getMethod());
        }
    }

    @Test
    void cancellationDuringAnAttemptDoesNotStartAnotherRetry() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(503)
                    .setHeadersDelay(2, TimeUnit.SECONDS));
            PicaConfiguration config = config(List.of("api-one.test", "api-two.test"), 5);
            OkHttpBuilder.HttpClientContext context = LocalTlsClientContextFactory.build(config, fixture.dns,
                    fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                    fixture.socketFactory);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            okhttp3.Call call = context.getApiClient().newCall(new Request.Builder()
                    .url("https://pica-placeholder.domain.com/comics").get().build());
            Future<Response> future = executor.submit(call::execute);
            try {
                assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS));
                call.cancel();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> future.get(3, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IOException);
                assertEquals(1, fixture.server.getRequestCount());
            } finally {
                call.cancel();
                executor.shutdownNow();
                closeContext(context);
            }
        }
    }

    @Test
    void allProbeFailuresStillPermitAnActualRequest() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            enqueueProbeResponses(fixture, 2, 503);
            fixture.server.enqueue(loginResponse());
            fixture.server.enqueue(profileResponse());
            PicaConfiguration config = config(List.of("api-one.test", "api-two.test"), 0);
            try (IPicaClient client = newClient(fixture, config)) {
                assertNotNull(client.login("fixture-user", "fixture-password"));
            }
            assertEquals(4, fixture.server.getRequestCount());
            assertProbeRequests(fixture, 2);
            RecordedRequest actual = fixture.server.takeRequest();
            assertEquals("POST", actual.getMethod());
            assertEquals("api-one.test", recordedHost(actual));
        }
    }

    @Test
    void periodicProbeCanRestoreAPreviouslyFailedHost() throws Exception {
        PicaDomainManager manager = new PicaDomainManager(List.of("api-one.test", "api-two.test"));
        AtomicBoolean reachable = new AtomicBoolean(false);
        try {
            manager.setProbe(domain -> reachable.get());
            manager.ensureInitialized(() -> false);
            manager.reportFailure("api-one.test");
            manager.startPeriodicProbe(10);
            reachable.set(true);
            awaitState(manager, "api-one.test", 0);
            assertEquals(0, manager.getDomainStates().get("api-two.test"));
        } finally {
            manager.shutdown();
        }
    }

    private static PicaConfiguration config(List<String> domains, int retryTimes) {
        return new PicaConfiguration.Builder()
                .domains(domains)
                .retryTimes(retryTimes)
                .timeout(Duration.ofSeconds(3))
                .domainProbeTimeoutMs(500)
                .build();
    }

    private static IPicaClient newClient(LocalTlsFixture fixture, PicaConfiguration config) {
        return new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config, fixture.dns,
                fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                fixture.socketFactory));
    }

    private static MockResponse loginResponse() {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"token\":\"fixture-token\"}}");
    }

    private static MockResponse profileResponse() {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"user\":{\"_id\":\"fixture-user-id\",\"name\":\"fixture-user\"}}}");
    }

    private static void enqueueProbeResponses(LocalTlsFixture fixture, int count, int status) {
        for (int i = 0; i < count; i++) {
            fixture.server.enqueue(new MockResponse().setResponseCode(status));
        }
    }

    private static void assertProbeRequests(LocalTlsFixture fixture, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            RecordedRequest request = fixture.server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("HEAD", request.getMethod());
        }
    }

    private static List<RecordedRequest> takeRequests(LocalTlsFixture fixture, int count) throws Exception {
        List<RecordedRequest> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RecordedRequest request = fixture.server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            requests.add(request);
        }
        return requests;
    }

    private static void awaitState(PicaDomainManager manager, String domain, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline
                && !Integer.valueOf(expected).equals(manager.getDomainStates().get(domain))) {
            Thread.sleep(10);
        }
        assertEquals(expected, manager.getDomainStates().get(domain));
    }

    private static void closeContext(OkHttpBuilder.HttpClientContext context) {
        context.getDomainManager().shutdown();
        context.getApiClient().dispatcher().cancelAll();
        context.getImageClient().dispatcher().cancelAll();
        context.getApiClient().dispatcher().executorService().shutdownNow();
        context.getImageClient().dispatcher().executorService().shutdownNow();
        context.getApiClient().connectionPool().evictAll();
        context.getImageClient().connectionPool().evictAll();
    }

    private static String recordedHost(RecordedRequest request) {
        String host = request.getHeader("Host");
        return host == null ? request.getHeader(":authority") : host;
    }
}
