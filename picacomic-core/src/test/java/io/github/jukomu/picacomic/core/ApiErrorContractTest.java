package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.exception.NetworkException;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiErrorContractTest {

    @Test
    void finalResponseStatusAndRetryAfterReplaceEarlierAttemptMetadata() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration config = U2TestSupport.config(List.of("api-one.test"), 1);
            try (DefaultPicaClient client = U2TestSupport.client(fixture, config)) {
                fixture.server.enqueue(U2TestSupport.probe());
                fixture.server.enqueue(new MockResponse().setResponseCode(503)
                        .setHeader("Retry-After", "10").setBody("early-marker"));
                fixture.server.enqueue(new MockResponse().setResponseCode(429)
                        .setHeader("Retry-After", "3").setBody("final-marker"));

                PicaApiException failure = assertThrows(PicaApiException.class,
                        () -> client.getCategories(new SearchQuery.Builder().build()));
                assertEquals(PicaApiException.Reason.HTTP_STATUS, failure.getReason());
                assertEquals(429, failure.getHttpStatus());
                assertEquals(Duration.ofSeconds(3), failure.getRetryAfter());
                assertFalse(failure.toString().contains("early-marker"));
                assertFalse(failure.toString().contains("final-marker"));
            }
        }
    }

    @Test
    void retryAfterSupportsHttpDateAndRejectsInvalidValues() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration config = U2TestSupport.config(List.of("api-one.test"), 0);
            try (DefaultPicaClient client = U2TestSupport.client(fixture, config)) {
                fixture.server.enqueue(U2TestSupport.probe());
                fixture.server.enqueue(new MockResponse().setResponseCode(429)
                        .setHeader("Retry-After", ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(5)
                                .format(DateTimeFormatter.RFC_1123_DATE_TIME)));
                PicaApiException dateFailure = assertThrows(PicaApiException.class,
                        () -> client.getCategories(new SearchQuery.Builder().build()));
                assertNotNull(dateFailure.getRetryAfter());
                assertTrue(!dateFailure.getRetryAfter().isNegative());

                fixture.server.enqueue(new MockResponse().setResponseCode(429)
                        .setHeader("Retry-After", "not-a-date"));
                PicaApiException invalidFailure = assertThrows(PicaApiException.class,
                        () -> client.getCategories(new SearchQuery.Builder().build()));
                assertNull(invalidFailure.getRetryAfter());
            }
        }
    }

    @Test
    void providerMalformedAndNonTwoHundredBodiesUseDifferentReasons() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"error\":\"PROVIDER_CODE\",\"message\":\"secret\"}"));
            PicaApiException provider = assertThrows(PicaApiException.class,
                    () -> client.getCategories(new SearchQuery.Builder().build()));
            assertEquals(PicaApiException.Reason.PROVIDER, provider.getReason());
            assertEquals("PROVIDER_CODE", provider.getProviderCode());

            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));
            PicaApiException malformed = assertThrows(PicaApiException.class,
                    () -> client.getCategories(new SearchQuery.Builder().build()));
            assertEquals(PicaApiException.Reason.PARSE, malformed.getReason());

            fixture.server.enqueue(new MockResponse().setResponseCode(502).setBody("also-not-json"));
            PicaApiException nonSuccess = assertThrows(PicaApiException.class,
                    () -> client.getCategories(new SearchQuery.Builder().build()));
            assertEquals(PicaApiException.Reason.HTTP_STATUS, nonSuccess.getReason());
            assertEquals(502, nonSuccess.getHttpStatus());
        }
    }

    @Test
    void networkDisconnectAndCallTimeoutRemainDistinguishable() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration networkConfig = U2TestSupport.config(List.of("api-one.test"), 0);
            try (DefaultPicaClient client = U2TestSupport.client(fixture, networkConfig)) {
                fixture.server.enqueue(U2TestSupport.probe());
                fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
                PicaApiException network = assertThrows(PicaApiException.class,
                        () -> client.getCategories(new SearchQuery.Builder().build()));
                assertEquals(PicaApiException.Reason.NETWORK, network.getReason());
                assertTrue(network instanceof NetworkException);
            }
        }

        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration timeoutConfig = new PicaConfiguration.Builder()
                    .domains(List.of("api-one.test"))
                    .timeout(Duration.ofMillis(100))
                    .retryTimes(0)
                    .domainProbeIntervalMs(0)
                    .build();
            try (DefaultPicaClient client = U2TestSupport.client(fixture, timeoutConfig)) {
                fixture.server.enqueue(U2TestSupport.probe());
                fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
                PicaApiException timeout = assertThrows(PicaApiException.class,
                        () -> client.getCategories(new SearchQuery.Builder().build()));
                assertEquals(PicaApiException.Reason.TIMEOUT, timeout.getReason());
            }
        }
    }

    @Test
    void sensitiveResponseFieldsNeverEnterPublicExceptionText() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(new MockResponse().setResponseCode(429)
                    .setHeader("X-Secret-Header", "header-secret")
                    .setHeader("Retry-After", "1")
                    .setBody("password=fixture-password token=fixture-token email=user@example.test "
                            + "marker=unique-response-marker"));
            PicaApiException failure = assertThrows(PicaApiException.class,
                    () -> client.getCategories(new SearchQuery.Builder().build()));
            assertFalse(failure.getMessage().contains("unique-response-marker"));
            assertFalse(failure.toString().contains("fixture-password"));
            assertFalse(String.valueOf(failure.getCause()).contains("user@example.test"));
        }
    }
}
