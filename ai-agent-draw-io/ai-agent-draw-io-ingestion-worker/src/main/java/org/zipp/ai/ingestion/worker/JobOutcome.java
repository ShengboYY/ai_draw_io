package org.zipp.ai.ingestion.worker;

import java.time.Duration;

public record JobOutcome(Kind kind, String errorCode, Duration retryAfter) {
    public enum Kind { SUCCEEDED, PERMANENT_FAILURE, TRANSIENT_FAILURE }

    public static JobOutcome succeeded() { return new JobOutcome(Kind.SUCCEEDED, null, null); }
    public static JobOutcome permanent(String code) { return new JobOutcome(Kind.PERMANENT_FAILURE, code, null); }
    public static JobOutcome transientFailure(String code) {
        return new JobOutcome(Kind.TRANSIENT_FAILURE, code, null);
    }
    public static JobOutcome transientFailure(String code, Duration retryAfter) {
        return new JobOutcome(Kind.TRANSIENT_FAILURE, code, retryAfter);
    }
}
