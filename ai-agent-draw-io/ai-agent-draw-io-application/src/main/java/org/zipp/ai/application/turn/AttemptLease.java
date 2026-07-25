package org.zipp.ai.application.turn;

import java.time.Instant;

public record AttemptLease(
        String attemptId,
        long epoch,
        Instant leaseExpiresAt,
        long leaseTtlMillis
) {

    public AttemptLease {
        ContractValues.requiredText(attemptId, "attemptId");
        if (epoch <= 0 || leaseExpiresAt == null || leaseTtlMillis <= 0) {
            throw new IllegalArgumentException("invalid attempt lease");
        }
    }
}
