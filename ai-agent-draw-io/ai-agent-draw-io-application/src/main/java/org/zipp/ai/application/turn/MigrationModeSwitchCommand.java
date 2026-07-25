package org.zipp.ai.application.turn;

/** Expected durable migration state and the only mode transition requested by an operator. */
public record MigrationModeSwitchCommand(
        long expectedGeneration,
        TurnEngineMode expectedMode,
        TurnEngineMode targetMode
) {

    public MigrationModeSwitchCommand {
        if (expectedGeneration < 0 || expectedMode == null || targetMode == null) {
            throw new IllegalArgumentException("invalid migration mode switch command");
        }
    }
}
