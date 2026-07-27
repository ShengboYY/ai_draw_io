package org.zipp.ai.application.turn.demand;

/** Untrusted evidence span proposed by the restricted demand interpreter. */
public record CurrentInstructionSpan(int startInclusive, int endExclusive, String digest) {

    public CurrentInstructionSpan {
        if (startInclusive < 0 || endExclusive <= startInclusive
                || digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("invalid current instruction span");
        }
    }
}
