package org.zipp.ai.domain.agent.service.armory.matter.patch;

import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CustomApiOutboundSecurityTest {

    @Test
    public void restClientInterceptorRejectsCustomBaseUrlThatTargetsMetadataIp() throws Exception {
        CustomApiInterceptor interceptor = new CustomApiInterceptor();
        AtomicBoolean executed = new AtomicBoolean(false);

        assertBlocked(() -> interceptor.intercept(
                restRequest("https://169.254.169.254"),
                new byte[0],
                (request, body) -> {
                    executed.set(true);
                    return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                }));

        assertFalse("blocked SSRF target must not reach the HTTP client", executed.get());
    }

    @Test
    public void webClientFilterRejectsCustomBaseUrlThatTargetsLoopbackIp() {
        CustomApiWebClientFilter filter = new CustomApiWebClientFilter();
        AtomicBoolean exchanged = new AtomicBoolean(false);

        assertBlocked(() -> filter.filter(
                webRequest("https://127.0.0.1"),
                request -> {
                    exchanged.set(true);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                }).block());

        assertFalse("blocked SSRF target must not reach the HTTP client", exchanged.get());
    }

    @Test
    public void webClientFilterRejectsRedirectsToBlockedTargets() {
        CustomApiWebClientFilter filter = new CustomApiWebClientFilter();
        AtomicBoolean exchanged = new AtomicBoolean(false);

        assertBlocked(() -> filter.filter(
                webRequest("https://93.184.216.34"),
                request -> {
                    exchanged.set(true);
                    return Mono.just(ClientResponse.create(HttpStatus.FOUND)
                            .header(HttpHeaders.LOCATION, "https://127.0.0.1/metadata")
                            .build());
                }).block());

        assertTrue("the initial public request is allowed before checking the redirect", exchanged.get());
    }

    @Test
    public void restClientInterceptorRejectsRedirectsToBlockedTargets() throws Exception {
        CustomApiInterceptor interceptor = new CustomApiInterceptor();
        AtomicBoolean executed = new AtomicBoolean(false);

        assertBlocked(() -> interceptor.intercept(
                restRequest("https://93.184.216.34"),
                new byte[0],
                (request, body) -> {
                    executed.set(true);
                    MockClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.FOUND);
                    response.getHeaders().setLocation(URI.create("https://127.0.0.1/metadata"));
                    return response;
                }));

        assertTrue("the initial public request is allowed before checking the redirect", executed.get());
    }

    @Test
    public void guardRejectsHostsThatResolveToPrivateAddresses() throws Exception {
        CustomApiRequestGuard guard = new CustomApiRequestGuard(
                host -> new InetAddress[]{InetAddress.getByName("10.0.0.8")});

        assertBlocked(() -> guard.assertAllowed(URI.create("https://model-proxy.example/v1")));
    }

    private HttpRequest restRequest(String customBaseUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Custom-Base-Url", customBaseUrl);
        headers.set("X-Custom-Api-Key", "sk-test");
        return new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return HttpMethod.POST;
            }

            @Override
            public URI getURI() {
                return URI.create("https://api.openai.com/v1/chat/completions");
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Map.of();
            }
        };
    }

    private ClientRequest webRequest(String customBaseUrl) {
        return ClientRequest.create(HttpMethod.POST, URI.create("https://api.openai.com/v1/chat/completions"))
                .header("X-Custom-Base-Url", customBaseUrl)
                .header("X-Custom-Api-Key", "sk-test")
                .build();
    }

    private void assertBlocked(ThrowingRunnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IOException expected) {
            return;
        } catch (Exception unexpected) {
            fail("expected unsafe custom model request to be blocked with IllegalArgumentException/IOException but got " + unexpected);
        }
        fail("expected unsafe custom model request to be blocked");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
