package org.zipp.ai.application.turn;

import java.time.Instant;

public record MigrationStateSnapshot(
        long generation,
        TurnEngineMode mode,
        Instant switchedAt
) {

    public MigrationStateSnapshot {
        if (generation < 0 || mode == null || switchedAt == null) {
            throw new IllegalArgumentException("invalid migration state snapshot");
        }
    }
}
