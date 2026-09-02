package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.enums.PicaSessionState;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.PicaSessionSnapshot;
import io.github.jukomu.picacomic.api.model.PicaContentPage;
import io.github.jukomu.picacomic.api.model.PicaUserInfo;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLifecycleTest {

    @Test
    void loginExposesAuthenticatingAndRollsBackWhenTheFirstStepIsCancelled() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            PicaRequest<PicaUserInfo> request = client.newLoginRequest("fixture-user", "fixture-password");
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<PicaUserInfo> future = caller.submit(request::execute);
                assertTrue(fixture.server.takeRequest(3, TimeUnit.SECONDS) != null);
                assertEquals("POST", fixture.server.takeRequest(3, TimeUnit.SECONDS).getMethod());
                assertEquals(PicaSessionState.AUTHENTICATING, client.getSession().state());

                request.cancel();
                PicaApiException failure = failureFrom(future);
                assertEquals(PicaApiException.Reason.CANCELLED, failure.getReason());
                assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());
                assertEquals(2, fixture.server.getRequestCount());
            } finally {
                request.close();
                caller.shutdownNow();
            }
        }
    }

    @Test
    void profileFailureDoesNotLeaveAHalfSignedInSession() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(U2TestSupport.signIn());
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("malformed-profile"));

            assertThrows(PicaApiException.class,
                    () -> client.login("fixture-user", "fixture-password"));
            assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());
            PicaApiException required = assertThrows(PicaApiException.class,
                    () -> client.getUserInfo());
            assertEquals(PicaApiException.Reason.SESSION_REQUIRED, required.getReason());
            assertEquals(3, fixture.server.getRequestCount());
        }
    }

    @Test
    void getUserInfoRefreshesTheInProcessSessionSnapshot() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            U2TestSupport.loginFixture(fixture, "user-id", "before-refresh");
            client.login("before-refresh", "fixture-password");
            fixture.server.enqueue(U2TestSupport.profileWithNestedModels("user-id", "after-refresh"));

            assertEquals("after-refresh", client.getUserInfo().name());
            assertEquals("after-refresh", client.getSession().user().name());
        }
    }

    @Test
    void cancellationAfterCommitRollsBackTheCommittedCredentials() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        Runnable afterCommit = () -> {
            committed.countDown();
            await(releaseCommit);
        };
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration config = U2TestSupport.config(List.of("api-one.test"), 0);
            try (DefaultPicaClient client = U2TestSupport.instrumentedClient(
                    fixture, config, afterCommit, () -> {
                    })) {
                U2TestSupport.loginFixture(fixture, "user-id", "fixture-user");
                PicaRequest<PicaUserInfo> request = client.newLoginRequest("fixture-user", "fixture-password");
                ExecutorService caller = Executors.newSingleThreadExecutor();
                try {
                    Future<PicaUserInfo> future = caller.submit(request::execute);
                    assertTrue(committed.await(3, TimeUnit.SECONDS));
                    assertEquals(PicaSessionState.SIGNED_IN, client.getSession().state());

                    request.cancel();
                    releaseCommit.countDown();
                    PicaApiException failure = failureFrom(future);
                    assertEquals(PicaApiException.Reason.CANCELLED, failure.getReason());
                    assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());
                    assertEquals(3, fixture.server.getRequestCount());
                    PicaApiException required = assertThrows(PicaApiException.class,
                            () -> client.newFavoritesRequest(new SearchQuery.Builder().build()).execute());
                    assertEquals(PicaApiException.Reason.SESSION_REQUIRED, required.getReason());
                } finally {
                    releaseCommit.countDown();
                    request.close();
                    caller.shutdownNow();
                }
            }
        }
    }

    @Test
    void closeAfterCommitReportsClientClosedAndCannotLeaveSignedInState() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        Runnable afterCommit = () -> {
            committed.countDown();
            await(releaseCommit);
        };
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration config = U2TestSupport.config(List.of("api-one.test"), 0);
            DefaultPicaClient client = U2TestSupport.instrumentedClient(
                    fixture, config, afterCommit, () -> {
                    });
            U2TestSupport.loginFixture(fixture, "user-id", "fixture-user");
            PicaRequest<PicaUserInfo> request = client.newLoginRequest("fixture-user", "fixture-password");
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<PicaUserInfo> future = caller.submit(request::execute);
                assertTrue(committed.await(3, TimeUnit.SECONDS));
                client.close();
                releaseCommit.countDown();
                PicaApiException failure = failureFrom(future);
                assertEquals(PicaApiException.Reason.CLIENT_CLOSED, failure.getReason());
                PicaSessionSnapshot session = client.getSession();
                assertEquals(PicaSessionState.SIGNED_OUT, session.state());
                assertNull(session.user());
            } finally {
                releaseCommit.countDown();
                request.close();
                caller.shutdownNow();
                client.close();
            }
        }
    }

    @Test
    void logoutCancelsAnAuthenticatedRequestAndAllowsRelogin() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            U2TestSupport.loginFixture(fixture, "first-id", "first-user");
            client.login("first-user", "fixture-password");

            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            PicaRequest<PicaUserInfo> profile = client.newUserInfoRequest();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                assertEquals("HEAD", fixture.server.takeRequest(3, TimeUnit.SECONDS).getMethod());
                assertEquals("POST", fixture.server.takeRequest(3, TimeUnit.SECONDS).getMethod());
                assertEquals("GET", fixture.server.takeRequest(3, TimeUnit.SECONDS).getMethod());
                Future<PicaUserInfo> future = caller.submit(profile::execute);
                var observed = fixture.server.takeRequest(3, TimeUnit.SECONDS);
                assertEquals("GET", observed.getMethod());
                client.logout();
                PicaApiException failure = failureFrom(future);
                assertEquals(PicaApiException.Reason.CANCELLED, failure.getReason());
                assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());

                fixture.server.enqueue(U2TestSupport.signIn());
                fixture.server.enqueue(U2TestSupport.profile("second-id", "second-user"));
                assertEquals("second-id", client.login("second-user", "fixture-password").id());
                assertEquals(PicaSessionState.SIGNED_IN, client.getSession().state());
            } finally {
                profile.close();
                caller.shutdownNow();
            }
        }
    }

    @Test
    void inFlightFavoritesCommitIsLinearizedWithLogout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable beforeCommit = () -> {
            entered.countDown();
            await(release);
        };
        try (LocalTlsFixture fixture = new LocalTlsFixture()) {
            PicaConfiguration config = U2TestSupport.config(List.of("api-one.test"), 0);
            try (DefaultPicaClient client = U2TestSupport.instrumentedClient(
                    fixture, config, () -> {
                    }, beforeCommit)) {
                U2TestSupport.loginFixture(fixture, "user-id", "fixture-user");
                client.login("fixture-user", "fixture-password");
                drain(fixture, 3);

                fixture.server.enqueue(U2TestSupport.contentPage("late-favorite"));
                PicaRequest< PicaContentPage> request = client.newFavoritesRequest(
                        new SearchQuery.Builder().build());
                ExecutorService caller = Executors.newSingleThreadExecutor();
                ExecutorService logoutExecutor = Executors.newSingleThreadExecutor();
                try {
                    Future<PicaContentPage> favorite = caller.submit(request::execute);
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    CountDownLatch logoutStarted = new CountDownLatch(1);
                    Future<?> logout = logoutExecutor.submit(() -> {
                        logoutStarted.countDown();
                        client.logout();
                    });
                    assertTrue(logoutStarted.await(3, TimeUnit.SECONDS));
                    release.countDown();
                    try {
                        favorite.get(3, TimeUnit.SECONDS);
                    } catch (ExecutionException failure) {
                        assertEquals(PicaApiException.Reason.CANCELLED,
                                ((PicaApiException) failure.getCause()).getReason());
                    }
                    logout.get(3, TimeUnit.SECONDS);
                    assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());
                    assertNoCachedContentPages(client);

                    fixture.server.enqueue(U2TestSupport.signIn());
                    fixture.server.enqueue(U2TestSupport.profile("new-user-id", "new-user"));
                    client.login("new-user", "fixture-password");
                    fixture.server.enqueue(U2TestSupport.contentPage("fresh-favorite"));
                    assertEquals("fresh-favorite", client.getFavorites(new SearchQuery.Builder().build())
                            .albums().get(0).title());
                } finally {
                    release.countDown();
                    request.close();
                    caller.shutdownNow();
                    logoutExecutor.shutdownNow();
                }
            }
        }
    }

    private static PicaApiException failureFrom(Future<?> future) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(3, TimeUnit.SECONDS));
        return (PicaApiException) failure.getCause();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("fixture latch did not open");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("fixture latch interrupted", exception);
        }
    }

    private static void drain(LocalTlsFixture fixture, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            assertTrue(fixture.server.takeRequest(3, TimeUnit.SECONDS) != null);
        }
    }

    private static void assertNoCachedContentPages(DefaultPicaClient client) throws Exception {
        Field poolField = DefaultPicaClient.class.getDeclaredField("cachePool");
        poolField.setAccessible(true);
        Object pool = poolField.get(client);
        Field mapField = pool.getClass().getDeclaredField("cacheMap");
        mapField.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) mapField.get(pool);
        assertTrue(map.isEmpty(), "logout must clear every cache entry");
    }
}
