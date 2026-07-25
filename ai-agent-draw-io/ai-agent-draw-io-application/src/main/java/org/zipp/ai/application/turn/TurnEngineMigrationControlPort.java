package org.zipp.ai.application.turn;

public interface TurnEngineMigrationControlPort {

    MigrationModeSwitchOutcome switchMode(TurnEngineMode expectedMode, TurnEngineMode targetMode);
}
