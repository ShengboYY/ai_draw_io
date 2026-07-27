package org.zipp.ai.infrastructure.adapter.vector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** JDK HTTP implementation; retry/backoff remains an outer application policy. */
final class JdkPineconeHttpTransport implements PineconeHttpTransport {

    private final HttpClient httpClient;

    JdkPineconeHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    JdkPineconeHttpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public PineconeHttpResponse exchange(String method, URI uri,
                                         Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30));
        headers.forEach(request::header);
        request.method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofString());
        return new PineconeHttpResponse(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").orElse(null),
                response.headers().firstValue("x-pinecone-request-id").orElse(null));
    }
}
