package org.zipp.ai.application.turn;

import java.util.Objects;

/** Pauses local admission around the durable migration singleton mode switch. */
public final class TurnEngineMigrationCoordinator {

    private static final int MIGRATION_BATCH_SIZE = 100;

    private final AdmissionBarrier admissionBarrier;
    private final TurnEngineMigrationControlPort migrationControl;
    private final LegacyRetryExpiryPort expiry;
    private final LegacyRetirementGatePort retirement;

    public TurnEngineMigrationCoordinator(
            AdmissionBarrier admissionBarrier,
            TurnEngineMigrationControlPort migrationControl,
            LegacyRetryExpiryPort expiry
    ) {
        this(admissionBarrier, migrationControl, expiry, LegacyRetirementGatePort.unavailable());
    }

    public TurnEngineMigrationCoordinator(
            AdmissionBarrier admissionBarrier,
            TurnEngineMigrationControlPort migrationControl,
            LegacyRetryExpiryPort expiry,
            LegacyRetirementGatePort retirement
    ) {
        this.admissionBarrier = Objects.requireNonNull(admissionBarrier, "admissionBarrier");
        this.migrationControl = Objects.requireNonNull(migrationControl, "migrationControl");
        this.expiry = Objects.requireNonNull(expiry, "expiry");
        this.retirement = Objects.requireNonNull(retirement, "retirement");
    }

    // Serialize migration windows so one operation cannot resume admission for another.
    public synchronized MigrationModeSwitchOutcome switchMode(
            MigrationStateSnapshot expectedState,
            TurnEngineMode targetMode
    ) {
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(targetMode, "targetMode");
        if (targetMode == TurnEngineMode.RETIRED) {
            // The v1 HTTP ingress is still active; retirement must remain unavailable until it
            // is removed or explicitly routed through the V2 admission boundary.
            return new MigrationModeSwitchOutcome.Rejected("LEGACY_INGRESS_NOT_REMOVED");
        }
        if (!isAllowedTransition(expectedState.mode(), targetMode)) {
            // Keep the cutover fail-closed even when a non-MySQL control port is composed.
            return new MigrationModeSwitchOutcome.Rejected("MIGRATION_MODE_TRANSITION_INVALID");
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

    private boolean isAllowedTransition(TurnEngineMode current, TurnEngineMode target) {
        return current == target
                || (current == TurnEngineMode.LEGACY && target == TurnEngineMode.V2_CANARY)
                || (current == TurnEngineMode.V2_CANARY
                && (target == TurnEngineMode.LEGACY || target == TurnEngineMode.ALL_V2));
    }
}
