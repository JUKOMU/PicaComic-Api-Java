package io.github.jukomu.picacomic.core.net.interceptor;

import io.github.jukomu.picacomic.core.constant.PicaConstants;
import io.github.jukomu.picacomic.core.net.provider.PicaDomainManager;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * API 专用的有界 host 重试拦截器。
 */
public final class RetryAndDomainRedirectInterceptor implements Interceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetryAndDomainRedirectInterceptor.class);

    private final PicaDomainManager domainManager;
    private final int maxRetries;

    /**
     * @param maxRetries 首次尝试之后的额外尝试次数
     * @param domainManager 当前 client 的 API host 管理器
     */
    public RetryAndDomainRedirectInterceptor(int maxRetries, PicaDomainManager domainManager) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries must be non-negative");
        }
        this.maxRetries = maxRetries;
        this.domainManager = domainManager;
    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request original = chain.request();
        try {
            domainManager.ensureInitialized(chain.call()::isCanceled);
        } catch (CancellationException exception) {
            throw new IOException("API request was cancelled", exception);
        }

        IOException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (chain.call().isCanceled()) {
                throw new IOException("API request was cancelled", lastException);
            }
            if (deadlineReached(chain)) {
                throw new IOException("API request deadline reached", lastException);
            }

            String host;
            try {
                host = domainManager.getBestDomain();
            } catch (CancellationException exception) {
                throw new IOException("API request was cancelled", exception);
            }
            Request request = buildAttemptRequest(original, host);
            try {
                Response response = chain.proceed(request);
                int status = response.code();
                if (response.isSuccessful()) {
                    domainManager.reportSuccess(host);
                    return response;
                }
                if (isRetryableStatus(status) && attempt < maxRetries) {
                    response.close();
                    domainManager.reportFailure(host);
                    LOGGER.debug("API request host={} attempt={} status={} retrying",
                            host, attempt + 1, status);
                    continue;
                }
                if (isRetryableStatus(status)) {
                    domainManager.reportFailure(host);
                }
                return response;
            } catch (IOException exception) {
                lastException = exception;
                if (attempt >= maxRetries || chain.call().isCanceled() || deadlineReached(chain)) {
                    throw exception;
                }
                domainManager.reportFailure(host);
                LOGGER.debug("API request host={} attempt={} io={} retrying", host, attempt + 1,
                        exception.getClass().getSimpleName());
            }
        }

        throw lastException == null ? new IOException("API request failed") : lastException;
    }

    private Request buildAttemptRequest(Request original, String host) throws IOException {
        HttpUrl originalUrl = original.url();
        if (!PicaConstants.PLACEHOLDER_HOST.equals(originalUrl.host())
                && !domainManager.contains(originalUrl.host())) {
            throw new IOException("API request target is not an authorized host");
        }
        HttpUrl attemptUrl = originalUrl.newBuilder().host(host).build();
        return original.newBuilder()
                .url(attemptUrl)
                .removeHeader("Host")
                .build();
    }

    private static boolean isRetryableStatus(int status) {
        return status == 403 || status >= 500;
    }

    private static boolean deadlineReached(Chain chain) {
        return chain.call().timeout().hasDeadline()
                && chain.call().timeout().deadlineNanoTime() <= System.nanoTime();
    }
}
