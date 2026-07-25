package org.zipp.ai.application.turn;

import java.util.Objects;

/** Pauses local admission around the durable migration singleton mode switch. */
public final class TurnEngineMigrationCoordinator {

    private final AdmissionBarrier admissionBarrier;
    private final TurnEngineMigrationControlPort migrationControl;
    private final LegacyRetryExpiryPort expiry;

    public TurnEngineMigrationCoordinator(
            AdmissionBarrier admissionBarrier,
            TurnEngineMigrationControlPort migrationControl,
            LegacyRetryExpiryPort expiry
    ) {
        this.admissionBarrier = Objects.requireNonNull(admissionBarrier, "admissionBarrier");
        this.migrationControl = Objects.requireNonNull(migrationControl, "migrationControl");
        this.expiry = Objects.requireNonNull(expiry, "expiry");
    }

    public MigrationModeSwitchOutcome switchMode(
            TurnEngineMode expectedMode,
            TurnEngineMode targetMode
    ) {
        Objects.requireNonNull(expectedMode, "expectedMode");
        Objects.requireNonNull(targetMode, "targetMode");
        admissionBarrier.pauseAndDrain();
        try {
            return migrationControl.switchMode(expectedMode, targetMode);
        } finally {
            // A failed compare-and-switch must leave the serving instance available in the old mode.
            admissionBarrier.resume();
        }
    }

    public int expireLegacyRetries(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        admissionBarrier.pauseAndDrain();
        try {
            return expiry.expireDue(batchSize);
        } finally {
            // Scanner failures must not leave a healthy serving instance permanently paused.
            admissionBarrier.resume();
        }
    }
}
