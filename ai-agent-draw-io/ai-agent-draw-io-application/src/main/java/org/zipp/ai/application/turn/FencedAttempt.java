package org.zipp.ai.application.turn;

/** Attempt capability; every mutable execution write must carry this fence. */
public record FencedAttempt(
        TurnKey key,
        AttemptLease lease,
        long contextMessageHighWater,
        String inputBindingDigest,
        ExecutionPolicySnapshot policy
) {

    public FencedAttempt {
        if (key == null || lease == null || policy == null || contextMessageHighWater < 0) {
            throw new IllegalArgumentException("invalid fenced attempt");
        }
        ContractValues.requiredText(inputBindingDigest, "inputBindingDigest");
    }

    public String attemptId() {
        return lease.attemptId();
    }

    public long attemptEpoch() {
        return lease.epoch();
    }
}
