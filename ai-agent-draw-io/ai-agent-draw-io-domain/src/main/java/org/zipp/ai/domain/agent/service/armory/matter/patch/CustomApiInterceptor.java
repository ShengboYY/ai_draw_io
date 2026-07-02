package org.zipp.ai.domain.agent.service.armory.matter.patch;

import org.zipp.ai.types.util.SecretLogSanitizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

import java.io.IOException;
import java.net.URI;

public class CustomApiInterceptor implements ClientHttpRequestInterceptor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CustomApiInterceptor.class);
    private final CustomApiRequestGuard requestGuard;

    public CustomApiInterceptor() {
        this(new CustomApiRequestGuard());
    }

    CustomApiInterceptor(CustomApiRequestGuard requestGuard) {
        this.requestGuard = requestGuard == null ? new CustomApiRequestGuard() : requestGuard;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();
        String customBaseUrl = headers.getFirst("X-Custom-Base-Url");
        String customApiKey = headers.getFirst("X-Custom-Api-Key");
        String customCompletionsPath = headers.getFirst("X-Custom-Completions-Path");
        boolean hasCustomBaseUrl = customBaseUrl != null && !customBaseUrl.isBlank();

        log.info("[CustomApiInterceptor] original URL={}, customBaseUrl={}, customApiKey={}, customCompletionsPath={}",
                request.getURI(), customBaseUrl,
                SecretLogSanitizer.maskSecret(customApiKey),
                customCompletionsPath);

        HttpRequest modifiedRequest = request;
        URI targetUri = request.getURI();

        if (hasCustomBaseUrl) {
            try {
                targetUri = requestGuard.rewriteCustomUri(targetUri, customBaseUrl, customCompletionsPath);
                requestGuard.assertAllowed(targetUri);
            } catch (IllegalArgumentException e) {
                throw new IOException("Blocked unsafe custom model endpoint.", e);
            }
        }

        if (customBaseUrl != null || customApiKey != null) {
            URI guardedTargetUri = targetUri;
            modifiedRequest = new HttpRequestWrapper(request) {
                @Override
                public URI getURI() {
                    return guardedTargetUri;
                }

                @Override
                public HttpHeaders getHeaders() {
                    HttpHeaders modifiedHeaders = new HttpHeaders();
                    modifiedHeaders.putAll(super.getHeaders());
                    modifiedHeaders.remove("X-Custom-Base-Url");
                    modifiedHeaders.remove("X-Custom-Api-Key");
                    modifiedHeaders.remove("X-Custom-Completions-Path");
                    if (customApiKey != null && !customApiKey.isEmpty()) {
                        modifiedHeaders.set("Authorization", "Bearer " + customApiKey);
                    }
                    return modifiedHeaders;
                }
            };
        }

        ClientHttpResponse response = execution.execute(modifiedRequest, body);
        if (hasCustomBaseUrl && isRedirect(response)) {
            try {
                // Redirects are new outbound targets, so apply the same DNS/IP checks before any caller can follow them.
                requestGuard.assertRedirectAllowed(targetUri, response.getHeaders().getLocation());
            } catch (IllegalArgumentException e) {
                response.close();
                throw new IOException("Blocked unsafe custom model redirect.", e);
            }
        }
        log.info("[CustomApiInterceptor] final URL={}, Authorization={}",
                targetUri,
                SecretLogSanitizer.maskAuthorization(customApiKey));
        return response;
    }

    private boolean isRedirect(ClientHttpResponse response) throws IOException {
        int statusCode = response.getRawStatusCode();
        return statusCode >= 300 && statusCode < 400;
    }
}
