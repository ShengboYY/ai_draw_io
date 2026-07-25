package org.zipp.ai.application.turn;

import java.util.Objects;

/** Pauses local admission around the durable migration singleton mode switch. */
public final class TurnEngineMigrationCoordinator {

    private final AdmissionBarrier admissionBarrier;
    private final TurnEngineMigrationControlPort migrationControl;

    public TurnEngineMigrationCoordinator(
            AdmissionBarrier admissionBarrier,
            TurnEngineMigrationControlPort migrationControl
    ) {
        this.admissionBarrier = Objects.requireNonNull(admissionBarrier, "admissionBarrier");
        this.migrationControl = Objects.requireNonNull(migrationControl, "migrationControl");
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
}
