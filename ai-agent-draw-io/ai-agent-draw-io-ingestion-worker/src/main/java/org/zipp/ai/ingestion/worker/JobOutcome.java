package org.zipp.ai.ingestion.worker;

public record JobOutcome(Kind kind, String errorCode) {
    public enum Kind { SUCCEEDED, PERMANENT_FAILURE, TRANSIENT_FAILURE }

    public static JobOutcome succeeded() { return new JobOutcome(Kind.SUCCEEDED, null); }
    public static JobOutcome permanent(String code) { return new JobOutcome(Kind.PERMANENT_FAILURE, code); }
    public static JobOutcome transientFailure(String code) { return new JobOutcome(Kind.TRANSIENT_FAILURE, code); }
}
