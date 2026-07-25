package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.LegacyRetryExpiryPort;
import org.zipp.ai.application.turn.MigrationModeSwitchCommand;
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
public class MySqlTurnEngineMigrationControlAdapter
        implements TurnEngineMigrationControlPort, LegacyRetryExpiryPort {

    private static final String SELECT_FOR_UPDATE = """
            SELECT generation, mode, switched_at, tombstone_retain_until
            FROM turn_engine_migration_state
            WHERE state_name = 'DEFAULT'
            FOR UPDATE
            """;
    private static final String UPDATE_MODE = """
            UPDATE turn_engine_migration_state
            SET generation = generation + 1, mode = ?, switched_at = CURRENT_TIMESTAMP(3)
            WHERE state_name = 'DEFAULT' AND generation = ? AND mode = ?
            """;
    private static final String SELECT_DUE_ASSIGNMENTS = """
            SELECT owner_key, conversation_id, diagram_id, turn_id
            FROM turn_engine_assignment
            WHERE selected_engine = 'LEGACY'
              AND legacy_retirement_state = 'EXECUTABLE'
              AND legacy_retry_eligible_until IS NOT NULL
              AND legacy_retry_eligible_until <= CURRENT_TIMESTAMP(3)
            ORDER BY legacy_retry_eligible_until, owner_key, conversation_id, turn_id
            LIMIT ?
            FOR UPDATE
            """;
    private static final String BACKFILL_LEGACY_RETRY = """
            UPDATE turn_engine_assignment
            SET legacy_routing_kind = COALESCE(legacy_routing_kind, 'UNEVALUATED'),
                legacy_retry_policy_version = COALESCE(legacy_retry_policy_version, 'legacy-retry-v1'),
                legacy_retry_eligible_until = COALESCE(
                    legacy_retry_eligible_until,
                    DATE_ADD(created_at, INTERVAL 7 DAY)),
                legacy_retirement_state = COALESCE(legacy_retirement_state, 'EXECUTABLE')
            WHERE selected_engine = 'LEGACY'
              AND (legacy_retirement_state IS NULL OR legacy_retirement_state <> 'EXPIRED_GONE')
              AND (legacy_routing_kind IS NULL
                   OR legacy_retry_policy_version IS NULL
                   OR legacy_retry_eligible_until IS NULL
                   OR legacy_retirement_state IS NULL)
            ORDER BY created_at, owner_key, conversation_id, turn_id
            LIMIT ?
            """;
    private static final String INSERT_TOMBSTONE = """
            INSERT INTO legacy_turn_tombstone (
                owner_key, conversation_id, diagram_id, turn_id,
                migration_generation, reason, retain_until
            ) VALUES (?, ?, ?, ?, ?, 'LEGACY_RETRY_EXPIRED',
                      COALESCE(?, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 DAY)))
            ON DUPLICATE KEY UPDATE
                migration_generation = VALUES(migration_generation),
                reason = VALUES(reason),
                retain_until = VALUES(retain_until)
            """;
    private static final String MARK_EXPIRED = """
            UPDATE turn_engine_assignment
            SET legacy_retirement_state = 'EXPIRED_GONE',
                legacy_archived_at = CURRENT_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND selected_engine = 'LEGACY' AND legacy_retirement_state = 'EXECUTABLE'
            """;

    private final JdbcOperations jdbc;

    public MySqlTurnEngineMigrationControlAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public int backfillRetryable(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return jdbc.update(BACKFILL_LEGACY_RETRY, batchSize);
    }

    @Override
    @Transactional
    public MigrationModeSwitchOutcome switchMode(MigrationModeSwitchCommand command) {
        Objects.requireNonNull(command, "command");
        MigrationRow current = currentForUpdate();
        if (current == null) {
            return new MigrationModeSwitchOutcome.Rejected("TURN_MIGRATION_STATE_MISSING");
        }
        if (current.mode == command.targetMode()) {
            return new MigrationModeSwitchOutcome.AlreadyAtTarget(current.snapshot());
        }
        if (current.generation != command.expectedGeneration()) {
            return new MigrationModeSwitchOutcome.Rejected("MIGRATION_GENERATION_CHANGED");
        }
        if (current.mode != command.expectedMode()) {
            return new MigrationModeSwitchOutcome.Rejected("MIGRATION_MODE_CHANGED");
        }
        if (!isAllowedTransition(current.mode, command.targetMode())) {
            return new MigrationModeSwitchOutcome.Rejected("MIGRATION_MODE_TRANSITION_INVALID");
        }
        if (jdbc.update(
                UPDATE_MODE,
                command.targetMode().name(),
                command.expectedGeneration(),
                current.mode.name()) != 1) {
            return new MigrationModeSwitchOutcome.Rejected("MIGRATION_MODE_SWITCH_LOST");
        }
        MigrationRow switched = currentForUpdate();
        if (switched == null) {
            throw new IllegalStateException("TURN_MIGRATION_STATE_MISSING_AFTER_SWITCH");
        }
        return new MigrationModeSwitchOutcome.Changed(switched.snapshot());
    }

    private boolean isAllowedTransition(TurnEngineMode current, TurnEngineMode target) {
        return (current == TurnEngineMode.LEGACY && target == TurnEngineMode.V2_CANARY)
                || (current == TurnEngineMode.V2_CANARY
                && (target == TurnEngineMode.LEGACY || target == TurnEngineMode.ALL_V2))
                || (current == TurnEngineMode.ALL_V2 && target == TurnEngineMode.RETIRED);
    }

    @Override
    @Transactional
    public int expireDue(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        MigrationRow migration = currentForUpdate();
        if (migration == null) {
            throw new IllegalStateException("TURN_MIGRATION_STATE_MISSING");
        }
        var due = jdbc.query(
                SELECT_DUE_ASSIGNMENTS,
                (rs, rowNum) -> new LegacyAssignment(
                        rs.getString("owner_key"),
                        rs.getString("conversation_id"),
                        rs.getString("diagram_id"),
                        rs.getString("turn_id")),
                batchSize);
        int expired = 0;
        for (LegacyAssignment assignment : due) {
            jdbc.update(
                    INSERT_TOMBSTONE,
                    assignment.ownerKey,
                    assignment.conversationId,
                    assignment.diagramId,
                    assignment.turnId,
                    migration.generation,
                    migration.tombstoneRetainUntil);
            expired += jdbc.update(
                    MARK_EXPIRED,
                    assignment.ownerKey,
                    assignment.conversationId,
                    assignment.turnId);
        }
        return expired;
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
                rs.getTimestamp("switched_at"),
                rs.getTimestamp("tombstone_retain_until"));
    }

    private record MigrationRow(
            long generation,
            TurnEngineMode mode,
            Timestamp switchedAt,
            Timestamp tombstoneRetainUntil
    ) {
        MigrationStateSnapshot snapshot() {
            if (switchedAt == null) {
                throw new IllegalStateException("TURN_MIGRATION_SWITCHED_AT_MISSING");
            }
            return new MigrationStateSnapshot(generation, mode, switchedAt.toInstant());
        }
    }

    private record LegacyAssignment(
            String ownerKey,
            String conversationId,
            String diagramId,
            String turnId
    ) {
    }
}
