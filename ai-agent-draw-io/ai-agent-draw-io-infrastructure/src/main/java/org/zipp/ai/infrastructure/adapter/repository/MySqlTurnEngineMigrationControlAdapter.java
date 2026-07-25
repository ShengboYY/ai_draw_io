package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.MigrationModeSwitchOutcome;
import org.zipp.ai.application.turn.MigrationStateSnapshot;
import org.zipp.ai.application.turn.TurnEngineMigrationControlPort;
import org.zipp.ai.application.turn.TurnEngineMode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;

/** Serializes migration mode changes on the same singleton row used by assignment admission. */
@Repository
public class MySqlTurnEngineMigrationControlAdapter implements TurnEngineMigrationControlPort {

    private static final String SELECT_FOR_UPDATE = """
            SELECT generation, mode, switched_at
            FROM turn_engine_migration_state
            WHERE state_name = 'DEFAULT'
            FOR UPDATE
            """;
    private static final String UPDATE_MODE = """
            UPDATE turn_engine_migration_state
            SET generation = generation + 1, mode = ?, switched_at = CURRENT_TIMESTAMP(3)
            WHERE state_name = 'DEFAULT' AND mode = ?
            """;

    private final JdbcTemplate jdbc;

    public MySqlTurnEngineMigrationControlAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public MigrationModeSwitchOutcome switchMode(TurnEngineMode expectedMode, TurnEngineMode targetMode) {
        Objects.requireNonNull(expectedMode, "expectedMode");
        Objects.requireNonNull(targetMode, "targetMode");
        MigrationRow current = currentForUpdate();
        if (current == null) {
            return new MigrationModeSwitchOutcome.Rejected("TURN_MIGRATION_STATE_MISSING");
        }
        if (current.mode == targetMode) {
            return new MigrationModeSwitchOutcome.AlreadyAtTarget(current.snapshot());
        }
        if (current.mode != expectedMode) {
            return new MigrationModeSwitchOutcome.Rejected("MIGRATION_MODE_CHANGED");
        }
        if (jdbc.update(UPDATE_MODE, targetMode.name(), expectedMode.name()) != 1) {
            return new MigrationModeSwitchOutcome.Rejected("MIGRATION_MODE_SWITCH_LOST");
        }
        MigrationRow switched = currentForUpdate();
        if (switched == null) {
            throw new IllegalStateException("TURN_MIGRATION_STATE_MISSING_AFTER_SWITCH");
        }
        return new MigrationModeSwitchOutcome.Changed(switched.snapshot());
    }

    private MigrationRow currentForUpdate() {
        return jdbc.query(SELECT_FOR_UPDATE, (rs, rowNum) -> row(rs))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private MigrationRow row(ResultSet rs) throws SQLException {
        return new MigrationRow(
                rs.getLong("generation"),
                TurnEngineMode.valueOf(rs.getString("mode")),
                rs.getTimestamp("switched_at"));
    }

    private record MigrationRow(long generation, TurnEngineMode mode, Timestamp switchedAt) {
        MigrationStateSnapshot snapshot() {
            if (switchedAt == null) {
                throw new IllegalStateException("TURN_MIGRATION_SWITCHED_AT_MISSING");
            }
            return new MigrationStateSnapshot(generation, mode, switchedAt.toInstant());
        }
    }
}
