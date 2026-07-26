package org.zipp.ai.application.turn;

import java.time.Duration;
import java.time.Instant;

public record AttemptLease(
        String attemptId,
        long epoch,
        Instant databaseNow,
        Instant leaseExpiresAt,
        long leaseTtlMillis,
        Duration expiresWithin,
        Duration renewWithin
) {

    /** Compatibility constructor for pure application tests; adapters use the DB-clock factory. */
    public AttemptLease(String attemptId, long epoch, Instant leaseExpiresAt, long leaseTtlMillis) {
        this(attemptId, epoch, leaseExpiresAt.minusMillis(leaseTtlMillis), leaseExpiresAt,
                leaseTtlMillis, Duration.ofMillis(leaseTtlMillis),
                renewBudget(Duration.ofMillis(leaseTtlMillis)));
    }

    public AttemptLease {
        ContractValues.requiredText(attemptId, "attemptId");
        if (epoch <= 0 || databaseNow == null || leaseExpiresAt == null || leaseTtlMillis <= 0
                || expiresWithin == null || renewWithin == null
                || expiresWithin.isZero() || expiresWithin.isNegative()
                || renewWithin.isZero() || renewWithin.isNegative()
                || renewWithin.compareTo(expiresWithin) >= 0) {
            throw new IllegalArgumentException("invalid attempt lease");
        }
    }

    /** Computes relative safety durations from the same database clock used for the deadline. */
    public static AttemptLease fromDatabaseClock(
            String attemptId,
            long epoch,
            Instant databaseNow,
            Instant leaseExpiresAt,
            long leaseTtlMillis
    ) {
        Duration expiresWithin = Duration.between(databaseNow, leaseExpiresAt);
        return new AttemptLease(
                attemptId,
                epoch,
                databaseNow,
                leaseExpiresAt,
                leaseTtlMillis,
                expiresWithin,
                renewBudget(expiresWithin));
    }

    /** Schedule the first heartbeat while the database lease is still safely valid. */
    private static Duration renewBudget(Duration expiresWithin) {
        Duration half = expiresWithin.dividedBy(2);
        return half.isZero() ? Duration.ofNanos(1) : half;
    }
}
