package org.zipp.ai.infrastructure.adapter.vector;

record PineconeHttpResponse(int statusCode, String body, String retryAfter, String requestId) {
    PineconeHttpResponse(int statusCode, String body) {
        this(statusCode, body, null, null);
    }

    PineconeHttpResponse(int statusCode, String body, String retryAfter) {
        this(statusCode, body, retryAfter, null);
    }
}
