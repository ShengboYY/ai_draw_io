package org.zipp.ai.application.turn.agent;

import org.zipp.ai.application.turn.FencedAttempt;

import java.time.Instant;

/** Redacted Plain agent trace fact; bodies, XML, prompts, labels and messages are absent. */
public record PlainAgentTraceEvent(
        FencedAttempt attempt,
        int stepNumber,
        PlainAgentTraceType type,
        String actionType,
        String toolName,
        String argumentsDigest,
        String beforeDraftDigest,
        String afterDraftDigest,
        String outcomeCode,
        int issueCount,
        long latencyMs,
        Instant occurredAt
) {

    public PlainAgentTraceEvent {
        if (attempt == null || stepNumber < 0 || type == null
                || issueCount < 0 || latencyMs < 0 || occurredAt == null) {
            throw new IllegalArgumentException("plain agent trace event is invalid");
        }
        actionType = text(actionType);
        toolName = text(toolName);
        argumentsDigest = text(argumentsDigest);
        beforeDraftDigest = text(beforeDraftDigest);
        afterDraftDigest = text(afterDraftDigest);
        outcomeCode = text(outcomeCode);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
