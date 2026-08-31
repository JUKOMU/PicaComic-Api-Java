package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.exception.ResponseException;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRetrySecurityTest {

    @Test
    void loginPostDoesNotRetryAndSensitiveRequestStaysOnConfiguredApiHosts() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(503).setBody("temporary"));
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(java.util.List.of("api-one.test", "api-two.test"))
                    .retryTimes(3)
                    .timeout(Duration.ofSeconds(3))
                    .build();
            try (IPicaClient client = new DefaultPicaClient(config, LocalTlsClientContextFactory.build(config,
                    fixture.dns, fixture.clientCertificates.sslSocketFactory(),
                    fixture.clientCertificates.trustManager(), fixture.socketFactory))) {
                assertThrows(ResponseException.class, () -> client.login("user@example.test", "secret"));
            }
            assertEquals(1, fixture.server.getRequestCount());
            RecordedRequest request = fixture.server.takeRequest();
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("api-one.test", recordedHost(request));
            assertTrue(request.getBody().readUtf8().contains("secret"));
        }
    }

    @Test
    void getRetriesOnlyToConfiguredHostsAndKeepsMethodPathAndHeaders() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(503).setBody("temporary"));
            fixture.server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "session=api-cookie; Path=/")
                    .setBody("{\"ok\":true}"));
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(java.util.List.of("api-one.test", "api-two.test"))
                    .retryTimes(1)
                    .timeout(Duration.ofSeconds(3))
                    .build();
            OkHttpBuilder.HttpClientContext context = LocalTlsClientContextFactory.build(config, fixture.dns,
                    fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                    fixture.socketFactory);
            try (Response response = context.getApiClient().newCall(new Request.Builder()
                    .url("https://pica-placeholder.domain.com/comics?page=2")
                    .get()
                    .header("authorization", "Bearer fixture-token")
                    .build()).execute()) {
                assertEquals(200, response.code());
            } finally {
                closeContext(context);
            }
            assertEquals(2, fixture.server.getRequestCount());
            RecordedRequest first = fixture.server.takeRequest();
            RecordedRequest second = fixture.server.takeRequest();
            assertEquals("GET", first.getMethod());
            assertEquals("GET", second.getMethod());
            assertEquals("/comics?page=2", first.getRequestUrl().encodedPath() + "?" + first.getRequestUrl().query());
            assertEquals(first.getRequestUrl().encodedPath(), second.getRequestUrl().encodedPath());
            assertTrue(java.util.Set.of("api-one.test", "api-two.test")
                    .contains(recordedHost(first)));
            assertTrue(java.util.Set.of("api-one.test", "api-two.test")
                    .contains(recordedHost(second)));
            assertEquals("Bearer fixture-token", second.getHeader("authorization"));
        }
    }

    @Test
    void apiAutomaticRedirectIsDisabled() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", "https://unknown.test/comics"));
            PicaConfiguration config = new PicaConfiguration.Builder()
                    .domains(java.util.List.of("api-one.test"))
                    .retryTimes(2)
                    .build();
            OkHttpBuilder.HttpClientContext context = LocalTlsClientContextFactory.build(config, fixture.dns,
                    fixture.clientCertificates.sslSocketFactory(), fixture.clientCertificates.trustManager(),
                    fixture.socketFactory);
            try (Response response = context.getApiClient().newCall(new Request.Builder()
                    .url("https://pica-placeholder.domain.com/comics")
                    .get().build()).execute()) {
                assertEquals(302, response.code());
            } finally {
                closeContext(context);
            }
            assertEquals(1, fixture.server.getRequestCount());
        }
    }

    private static void closeContext(OkHttpBuilder.HttpClientContext context) {
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
