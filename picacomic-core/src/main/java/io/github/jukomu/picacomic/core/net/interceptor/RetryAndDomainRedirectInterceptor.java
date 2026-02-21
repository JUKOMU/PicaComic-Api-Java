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
import java.util.concurrent.TimeUnit;

/**
 * @author JUKOMU
 * @Description: 一个OkHttp拦截器，负责实现核心的重试和域名动态切换逻辑
 * <p>
 * 该拦截器是有状态的，并协调了以下几个核心功能：
 * <ul>
 *   <li><b>重试机制:</b> 在发生网络故障 (如 {@link IOException}) 或服务器端错误 (5xx) 时重试请求。</li>
 *   <li><b>动态域名切换:</b> 与 {@link PicaDomainManager} 协作，为每次尝试选择表现最佳的域名，从而有效绕过临时不可用的服务器。</li>
 *   <li><b>代理回退:</b> 在达到可配置的失败尝试次数 (由 {@code proxyFallbackThreshold} 定义) 后，它会自动切换到公共图片代理 (wsrv.nl)。</li>
 *   <li><b>域名熔断:</b> 一旦某个域名因失败次数过多而触发了代理回退，它将在 {@code PicaDomainManager} 中被标记为“阻塞”。后续对该域名的请求将跳过初始的直连尝试，立即使用代理。</li>
 * </ul>
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class RetryAndDomainRedirectInterceptor implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger(RetryAndDomainRedirectInterceptor.class);
    private final PicaDomainManager domainManager;
    private final int maxRetriesPerRequest;
    private final int proxyFallbackThreshold;

    public RetryAndDomainRedirectInterceptor(int maxRetries, PicaDomainManager domainManager, int proxyFallbackThreshold) {
        this.maxRetriesPerRequest = maxRetries;
        this.domainManager = domainManager;
        if (proxyFallbackThreshold >= maxRetries) {
            proxyFallbackThreshold = maxRetries - 1;
        }
        this.proxyFallbackThreshold = proxyFallbackThreshold;

    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request originalRequest = chain.request();
        IOException lastException = null;

        // 如果该域名已知被墙，直接跳过前面的重试，从最后一次（代理尝试）开始
        int startTryCount = 0;
        String originalHost = originalRequest.url().host();
        if (domainManager.isDomainBlocked(originalHost)) {
            logger.info("Host {} is in blocked list. Switching to proxy immediately.", originalHost);
            startTryCount = proxyFallbackThreshold;
        }

        for (int tryCount = startTryCount; tryCount <= maxRetriesPerRequest; tryCount++) {
            Request requestToProceed;

            // 当重试次数达到阈值时，强制使用公共图片代理
            if (tryCount >= proxyFallbackThreshold) {
                // 只有经过了重试失败才打印这个警告
                if (startTryCount < proxyFallbackThreshold) {
                    logger.warn("Retry count reached {}. Switching to Public Image Proxy (wsrv.nl).", tryCount);
                    // 标记该域名为被阻断，下次直接走代理
                    domainManager.markDomainAsBlocked(originalHost);
                }
                requestToProceed = buildProxyRequest(originalRequest);
            } else {
                // 原有逻辑
                final boolean isPlaceholder = isPlaceholderRequest(originalRequest);

                if (isPlaceholder) {
                    // 获取当前最佳域名
                    String bestDomain = domainManager.getBestDomain();
                    if (bestDomain == null) {
                        throw new IOException("No available domains to try.", lastException);
                    }
                    // 如果是占位符请求，总是用最佳域名替换
                    Request requestToProceed1 = replaceHost(originalRequest, bestDomain);
                    requestToProceed = addAddHeaders(requestToProceed1, bestDomain);
                } else {
                    requestToProceed = addAddHeaders(originalRequest, "manhuabika.com");
                }
            }

            HttpUrl requestUrl = requestToProceed.url();
            String currentHost = requestUrl.host();

            if (tryCount == 0 || tryCount == proxyFallbackThreshold) {
                logger.info("Sending request to {}", requestUrl);
            } else {
                logger.warn("Retrying request to {} (Attempt {}/{})", requestUrl, tryCount, maxRetriesPerRequest);
            }

            try {
                // 如果是代理请求，通常响应较快，但为了保险维持超时设置
                Response response = chain.withConnectTimeout(10, TimeUnit.SECONDS).proceed(requestToProceed);

                if (response.isSuccessful()) {
                    // 如果不是代理请求，才上报成功到域名管理器
                    if (tryCount < 3) {
                        domainManager.reportSuccess(currentHost);
                    }
                    return response;
                }

                // 服务端错误 (HTTP 5xx)，报告失败，关闭响应，然后继续循环重试
                if (response.code() >= 500) {
                    if (tryCount < proxyFallbackThreshold) {
                        domainManager.reportFailure(currentHost);
                    }
                    response.close();
                    lastException = new IOException("Server error: " + response.code() + " for host " + currentHost);
                    logger.warn("Request to {} failed with server error: {}", requestUrl, response.code());
                    continue;
                }

                // 其他非成功响应 (4xx, 3xx)，不重试，直接返回
                logger.error("Request to {} failed with client error: {}. No retry will be attempted.", requestUrl, response.code());
                return response;

            } catch (IOException e) {
                if (tryCount < proxyFallbackThreshold) {
                    domainManager.reportFailure(currentHost);
                }
                lastException = e;
                logger.warn("Request to {} failed with IOException: {}", requestUrl, e.getMessage());
            }
        }

        // 如果循环结束仍未成功，抛出最后一次记录的异常
        logger.error("Request for {} failed after {} retries.", originalRequest.url(), maxRetriesPerRequest, lastException);
        throw new IOException("Request failed after " + maxRetriesPerRequest + " retries for URL: " + originalRequest.url(), lastException);
    }

    /**
     * 构建公共代理请求 (使用 wsrv.nl)
     */
    private Request buildProxyRequest(Request originalRequest) {
        String originalUrlStr = originalRequest.url().toString();

        // wsrv.nl 的格式是 https://wsrv.nl/?url=原始域名/路径
        // 需要去除原始 URL 的 http:// 或 https:// 前缀
        String cleanUrl = originalUrlStr.replaceAll("^https?://", "");

        HttpUrl proxyUrl = new HttpUrl.Builder()
                .scheme("https")
                .host("wsrv.nl")
                .addQueryParameter("url", cleanUrl)
                .build();

        return originalRequest.newBuilder()
                .url(proxyUrl)
                .removeHeader("Host")
                .removeHeader("Authority")
                .removeHeader("Origin")
                .removeHeader("Referer")
                .build();
    }

    /**
     * 检查请求是否使用了占位符
     */
    private boolean isPlaceholderRequest(Request request) {
        return PicaConstants.PLACEHOLDER_HOST.equals(request.url().host());
    }

    /**
     * 将请求的 host 替换为指定的域名。
     */
    private Request replaceHost(Request request, String newHost) {
        HttpUrl newUrl = request.url().newBuilder()
                .host(newHost)
                .build();
        return request.newBuilder()
                .url(newUrl)
                .header("Host", newHost) // 确保Host头被正确设置
                .build();
    }

    private Request addAddHeaders(Request request, String newHost) {
        HttpUrl newUrl = request.url().newBuilder()
                .build();
        String origin = "https://" + newHost;
        return request.newBuilder()
                .url(newUrl)
                .header("Authority", newHost)
                .header("Origin", origin)
                .header("Referer", origin)
                .build();
    }
}