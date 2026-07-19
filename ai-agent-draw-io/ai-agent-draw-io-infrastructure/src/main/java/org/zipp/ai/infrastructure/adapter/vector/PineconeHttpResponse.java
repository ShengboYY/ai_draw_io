package org.zipp.ai.infrastructure.adapter.vector;

record PineconeHttpResponse(int statusCode, String body, String retryAfter) {
    PineconeHttpResponse(int statusCode, String body) {
        this(statusCode, body, null);
    }
}
