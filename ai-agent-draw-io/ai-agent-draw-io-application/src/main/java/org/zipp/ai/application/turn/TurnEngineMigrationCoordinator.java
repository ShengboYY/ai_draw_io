package org.zipp.ai.application.turn;

import java.util.Objects;

/** Pauses local admission around the durable migration singleton mode switch. */
public final class TurnEngineMigrationCoordinator {

    private static final int MIGRATION_BATCH_SIZE = 100;

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

    // Serialize migration windows so one operation cannot resume admission for another.
    public synchronized MigrationModeSwitchOutcome switchMode(
            MigrationStateSnapshot expectedState,
            TurnEngineMode targetMode
    ) {
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(targetMode, "targetMode");
        if (targetMode == TurnEngineMode.V2_CANARY) {
            // M1 has no stable cohort selector; refusing the switch is safer than a canary
            // state whose assignments all still execute on the legacy engine.
            return new MigrationModeSwitchOutcome.Rejected("V2_CANARY_UNSUPPORTED");
        }
        admissionBarrier.pauseAndDrain();
        try {
            // Complete durable legacy-horizon preparation before changing the singleton mode row.
            drainRetryPreparation();
            return migrationControl.switchMode(new MigrationModeSwitchCommand(
                    expectedState.generation(), expectedState.mode(), targetMode));
        } finally {
            // A failed compare-and-switch must leave the serving instance available in the old mode.
            admissionBarrier.resume();
        }
    }

    public synchronized int expireLegacyRetries(int batchSize) {
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

    private void drainRetryPreparation() {
        while (expiry.backfillRetryable(MIGRATION_BATCH_SIZE) > 0) {
            // Each batch is committed independently so a large legacy inventory stays bounded.
        }
        while (expiry.expireDue(MIGRATION_BATCH_SIZE) > 0) {
            // Expired rows are durably tombstoned before the mode switch is attempted.
        }
    }
}
