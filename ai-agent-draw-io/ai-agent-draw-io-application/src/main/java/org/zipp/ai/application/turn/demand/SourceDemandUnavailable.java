package org.zipp.ai.application.turn.demand;

// Unavailable is typed so callers cannot silently turn model failure into Plain.

import java.time.Duration;

public record SourceDemandUnavailable(String code, Duration retryAfter)
        implements SourceDemandResolution {

    public SourceDemandUnavailable {
        if (code == null || code.isBlank() || retryAfter == null || retryAfter.isNegative()) {
            throw new IllegalArgumentException("invalid demand unavailable outcome");
        }
    }
}
