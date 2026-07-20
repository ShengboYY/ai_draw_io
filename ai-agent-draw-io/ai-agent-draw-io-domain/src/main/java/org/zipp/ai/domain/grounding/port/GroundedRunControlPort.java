package org.zipp.ai.domain.grounding.port;

/** Durable run fence established before retrieval and checked at the canvas commit linearization point. */
public interface GroundedRunControlPort {
    void start(RunIdentity identity);
    CancelResult cancel(RunIdentity identity);

    enum CancelResult { CANCELLED, ALREADY_CANCELLED, ALREADY_COMPLETED }

    record RunIdentity(String ownerKey, String requestId, String runId, long generation) {
        public RunIdentity {
            ownerKey = required(ownerKey, "ownerKey");
            requestId = required(requestId, "requestId");
            runId = required(runId, "runId");
            if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        }

        public RunIdentity(String ownerKey, String requestId, String runId) {
            this(ownerKey, requestId, runId, 1L);
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
            return value.trim();
        }
    }
}
