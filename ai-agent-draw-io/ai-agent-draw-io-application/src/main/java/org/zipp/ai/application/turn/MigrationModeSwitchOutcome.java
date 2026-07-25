package org.zipp.ai.application.turn;

public sealed interface MigrationModeSwitchOutcome
        permits MigrationModeSwitchOutcome.Changed,
        MigrationModeSwitchOutcome.AlreadyAtTarget,
        MigrationModeSwitchOutcome.Rejected {

    record Changed(MigrationStateSnapshot state) implements MigrationModeSwitchOutcome {
    }

    record AlreadyAtTarget(MigrationStateSnapshot state) implements MigrationModeSwitchOutcome {
    }

    record Rejected(String code) implements MigrationModeSwitchOutcome {

        public Rejected {
            ContractValues.requiredText(code, "code");
        }
    }
}
