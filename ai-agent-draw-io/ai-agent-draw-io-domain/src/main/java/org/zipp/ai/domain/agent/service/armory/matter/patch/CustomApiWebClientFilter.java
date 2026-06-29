package org.zipp.ai.domain.agent.service.armory.matter.patch;

import org.zipp.ai.types.util.SecretLogSanitizer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.net.URI;

public class CustomApiWebClientFilter implements ExchangeFilterFunction {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CustomApiWebClientFilter.class);

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String customBaseUrl = request.headers().getFirst("X-Custom-Base-Url");
        String customApiKey = request.headers().getFirst("X-Custom-Api-Key");
        String customCompletionsPath = request.headers().getFirst("X-Custom-Completions-Path");

        log.info("[CustomApiWebClientFilter] original URL={}, customBaseUrl={}, customApiKey={}, customCompletionsPath={}",
                request.url(), customBaseUrl,
                SecretLogSanitizer.maskSecret(customApiKey),
                customCompletionsPath);

        if (customBaseUrl != null || customApiKey != null) {
            ClientRequest.Builder builder = ClientRequest.from(request);
            URI newUri = null;
            if (customBaseUrl != null) {
                try {
                    URI originalUri = request.url();
                    URI customUri = new URI(customBaseUrl);
                    String customPath = customUri.getPath();
                    if (customPath == null) customPath = "";
                    String path = originalUri.getPath();

                    // 如果用户自定义了 completionsPath，用它替换原始路径中的默认路径
                    if (customCompletionsPath != null && !customCompletionsPath.isEmpty()) {
                        // 确保自定义路径以 / 开头
                        String normalizedCustomPath = customCompletionsPath.startsWith("/") ? customCompletionsPath : "/" + customCompletionsPath;
                        // 将原始路径中的 /v1/chat/completions 或 /v1/embeddings 替换为自定义路径
                        if (path.endsWith("/chat/completions") || path.endsWith("/embeddings")) {
                            // 保留原始路径中的 embeddings 部分判断
                            if (path.endsWith("/embeddings")) {
                                // embeddings 暂不替换，保持原逻辑
                                if (customPath.endsWith("/v1") && path.startsWith("/v1")) {
                                    customPath = customPath.substring(0, customPath.length() - 3);
                                }
                            } else {
                                // chat/completions: 用自定义 completionsPath 替换
                                path = normalizedCustomPath;
                            }
                        }
                    } else {
                        // 没有自定义 completionsPath，使用原有的去重逻辑
                        if (customPath.endsWith("/v1") && path.startsWith("/v1")) {
                            customPath = customPath.substring(0, customPath.length() - 3);
                        }
                    }

                    String newPath = customPath + path;
                    newPath = newPath.replaceAll("//+", "/");
                    newUri = new URI(customUri.getScheme(), customUri.getUserInfo(), customUri.getHost(), customUri.getPort(), newPath, originalUri.getQuery(), originalUri.getFragment());
                    builder.url(newUri);
                } catch (Exception e) {
                    log.error("[CustomApiWebClientFilter] Failed to modify URL", e);
                }
            }
            builder.headers(headers -> {
                headers.remove("X-Custom-Base-Url");
                headers.remove("X-Custom-Api-Key");
                headers.remove("X-Custom-Completions-Path");
                if (customApiKey != null && !customApiKey.isEmpty()) {
                    headers.set("Authorization", "Bearer " + customApiKey);
                }
            });
            ClientRequest newRequest = builder.build();
            log.info("[CustomApiWebClientFilter] final URL={}, Authorization={}",
                    newRequest.url(),
                    SecretLogSanitizer.sanitize(newRequest.headers().getOrDefault("Authorization", java.util.List.of("(none)")).toString()));
            return next.exchange(newRequest);
        }

        return next.exchange(request);
    }
}
