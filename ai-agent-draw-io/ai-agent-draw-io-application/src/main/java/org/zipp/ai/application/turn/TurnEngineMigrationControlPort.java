package org.zipp.ai.application.turn;

public interface TurnEngineMigrationControlPort {

    MigrationModeSwitchOutcome switchMode(MigrationModeSwitchCommand command);
}
