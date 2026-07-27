package org.zipp.ai.application.turn.checkpoint;

import java.util.Objects;

/**
 * Durable closed-decision envelope. The payload is canonical JSON produced by a typed
 * application decision codec; it is never interpreted as authorization by persistence.
 */
public record TurnDecisionCheckpoint(
        int schemaVersion,
        String contextReadSetDigest,
        String inputBindingDigest,
        String decisionKind,
        String decisionJson,
        String digest
) {

    public TurnDecisionCheckpoint {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("checkpoint schemaVersion must be positive");
        }
        requireDigest(contextReadSetDigest, "contextReadSetDigest");
        requireDigest(inputBindingDigest, "inputBindingDigest");
        Objects.requireNonNull(decisionKind, "decisionKind");
        if (decisionKind.isBlank() || decisionKind.length() > 64) {
            throw new IllegalArgumentException("decisionKind must be bounded");
        }
        Objects.requireNonNull(decisionJson, "decisionJson");
        if (decisionJson.isBlank() || decisionJson.length() > 128_000
                || decisionJson.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("decisionJson must be bounded and non-empty");
        }
        requireDigest(digest, "digest");
        if (!digest.equals(TurnDecisionCheckpointDigestCalculator.digestFor(
                schemaVersion, contextReadSetDigest, inputBindingDigest,
                decisionKind, decisionJson))) {
            throw new IllegalArgumentException("checkpoint digest does not match its payload");
        }
    }

    public static TurnDecisionCheckpoint create(
            int schemaVersion,
            String contextReadSetDigest,
            String inputBindingDigest,
            String decisionKind,
            String decisionJson
    ) {
        return new TurnDecisionCheckpoint(
                schemaVersion,
                contextReadSetDigest,
                inputBindingDigest,
                decisionKind,
                decisionJson,
                TurnDecisionCheckpointDigestCalculator.digestFor(
                        schemaVersion, contextReadSetDigest, inputBindingDigest,
                        decisionKind, decisionJson));
    }

    private static void requireDigest(String value, String field) {
        if (value == null || value.length() != 64
                || value.chars().anyMatch(character -> !isLowerHex(character))) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
    }

    private static boolean isLowerHex(int character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f');
    }
}
