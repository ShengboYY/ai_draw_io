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
    private final CustomApiRequestGuard requestGuard;

    public CustomApiWebClientFilter() {
        this(new CustomApiRequestGuard());
    }

    CustomApiWebClientFilter(CustomApiRequestGuard requestGuard) {
        this.requestGuard = requestGuard == null ? new CustomApiRequestGuard() : requestGuard;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String customBaseUrl = request.headers().getFirst("X-Custom-Base-Url");
        String customApiKey = request.headers().getFirst("X-Custom-Api-Key");
        String customCompletionsPath = request.headers().getFirst("X-Custom-Completions-Path");
        boolean hasCustomBaseUrl = customBaseUrl != null && !customBaseUrl.isBlank();

        log.info("[CustomApiWebClientFilter] original URL={}, customBaseUrl={}, customApiKey={}, customCompletionsPath={}",
                request.url(), customBaseUrl,
                SecretLogSanitizer.maskSecret(customApiKey),
                customCompletionsPath);

        if (customBaseUrl != null || customApiKey != null) {
            ClientRequest.Builder builder = ClientRequest.from(request);
            URI targetUri = request.url();
            if (hasCustomBaseUrl) {
                targetUri = requestGuard.rewriteCustomUri(targetUri, customBaseUrl, customCompletionsPath);
                requestGuard.assertAllowed(targetUri);
                builder.url(targetUri);
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
            Mono<ClientResponse> response = next.exchange(newRequest);
            if (!hasCustomBaseUrl) {
                return response;
            }
            URI guardedTargetUri = targetUri;
            return response.flatMap(clientResponse -> {
                if (!clientResponse.statusCode().is3xxRedirection()) {
                    return Mono.just(clientResponse);
                }
                try {
                    // Redirects are new outbound targets, so apply the same DNS/IP checks before any caller can follow them.
                    requestGuard.assertRedirectAllowed(
                            guardedTargetUri,
                            clientResponse.headers().asHttpHeaders().getLocation());
                    return Mono.just(clientResponse);
                } catch (IllegalArgumentException e) {
                    return clientResponse.releaseBody().then(Mono.error(e));
                }
            });
        }

        return next.exchange(request);
    }
}
