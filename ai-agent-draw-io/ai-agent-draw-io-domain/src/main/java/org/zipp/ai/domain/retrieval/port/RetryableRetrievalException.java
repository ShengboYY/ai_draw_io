package org.zipp.ai.domain.retrieval.port;

import java.time.Duration;

/** Transient retrieval dependency failure with an optional provider-directed retry delay. */
public final class RetryableRetrievalException extends RuntimeException {
    private final Duration retryAfter;

    public RetryableRetrievalException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
