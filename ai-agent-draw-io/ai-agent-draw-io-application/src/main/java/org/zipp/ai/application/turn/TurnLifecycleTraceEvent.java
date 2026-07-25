package org.zipp.ai.application.turn;

import java.time.Instant;

/**
 * Redacted lifecycle evidence. The event intentionally has no user message, payload, source
 * body, or terminal payload fields; only opaque identifiers, hashes, codes, and status are
 * available to a trace adapter.
 */
public record TurnLifecycleTraceEvent(
        TurnLifecycleTraceType type,
        TurnKey key,
        String attemptId,
        long attemptEpoch,
        String policyHash,
        String inputBindingDigest,
        String decisionDigest,
        String outcomeCode,
        TurnStatus outcomeStatus,
        Instant occurredAt
) {

    public TurnLifecycleTraceEvent {
        if (type == null || key == null || occurredAt == null || attemptEpoch < 0) {
            throw new IllegalArgumentException("invalid lifecycle trace event");
        }
        optionalText(attemptId, "attemptId");
        optionalText(policyHash, "policyHash");
        optionalText(inputBindingDigest, "inputBindingDigest");
        optionalText(decisionDigest, "decisionDigest");
        optionalText(outcomeCode, "outcomeCode");
    }

    public static TurnLifecycleTraceEvent of(
            TurnLifecycleTraceType type,
            TurnKey key,
            String attemptId,
            long attemptEpoch,
            String policyHash,
            String inputBindingDigest,
            String outcomeCode,
            TurnStatus outcomeStatus
    ) {
        return new TurnLifecycleTraceEvent(
                type, key, attemptId, attemptEpoch, policyHash, inputBindingDigest,
                null, outcomeCode, outcomeStatus, Instant.now());
    }

    public static TurnLifecycleTraceEvent fromAttempt(
            TurnLifecycleTraceType type,
            FencedAttempt attempt,
            String outcomeCode,
            TurnStatus outcomeStatus
    ) {
        return of(type, attempt.key(), attempt.attemptId(), attempt.attemptEpoch(),
                attempt.policy().policyHash(), attempt.inputBindingDigest(), outcomeCode,
                outcomeStatus);
    }

    private static void optionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must be null or non-blank");
        }
    }
}
