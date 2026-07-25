package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.AdmissionWriteOutcome;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.MemoryWriteDeclaration;
import org.zipp.ai.application.turn.MigrationStateSnapshot;
import org.zipp.ai.application.turn.NoMemoryWrite;
import org.zipp.ai.application.turn.SelectedTurnEngine;
import org.zipp.ai.application.turn.TurnEngineAssignment;
import org.zipp.ai.application.turn.TurnEngineAssignmentCommand;
import org.zipp.ai.application.turn.TurnEngineAssignmentPort;
import org.zipp.ai.application.turn.TurnEngineMigrationStatePort;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.VersionedRequestFingerprint;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/** MySQL sticky assignment adapter; the migration singleton is the linearization row. */
@Repository
public class MySqlTurnEngineAssignmentAdapter
        implements TurnEngineAssignmentPort, TurnEngineMigrationStatePort {

    private static final String SELECT_MIGRATION = """
            SELECT generation, mode, switched_at
            FROM turn_engine_migration_state
            WHERE state_name = 'DEFAULT'
            FOR UPDATE
            """;
    private static final String SELECT_MIGRATION_WITHOUT_LOCK = """
            SELECT generation, mode, switched_at
            FROM turn_engine_migration_state
            WHERE state_name = 'DEFAULT'
            """;
    private static final String SELECT_ASSIGNMENT = """
            SELECT owner_key, conversation_id, diagram_id, turn_id,
                   request_fingerprint_schema_version, request_fingerprint,
                   selected_engine, migration_generation, migration_mode,
                   execution_policy_schema_version, execution_policy_snapshot_json,
                   execution_policy_hash, memory_write_schema_version,
                   memory_write_declaration_json, memory_write_digest,
                   legacy_retirement_state, created_at
            FROM turn_engine_assignment
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
    private static final String INSERT_ASSIGNMENT = """
            INSERT INTO turn_engine_assignment (
                owner_key, conversation_id, diagram_id, turn_id,
                request_fingerprint_schema_version, request_fingerprint,
                selected_engine, migration_generation, migration_mode,
                execution_policy_schema_version, execution_policy_snapshot_json,
                execution_policy_hash, memory_write_schema_version,
                memory_write_declaration_json, memory_write_digest,
                legacy_routing_kind, legacy_retry_policy_version,
                legacy_retry_eligible_until, legacy_retirement_state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      IF(? = 'LEGACY', 'UNEVALUATED', NULL),
                      IF(? = 'LEGACY', 'legacy-retry-v1', NULL),
                      IF(? = 'LEGACY', DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 7 DAY), NULL),
                      IF(? = 'LEGACY', 'EXECUTABLE', NULL))
            ON DUPLICATE KEY UPDATE updated_at = updated_at
            """;

    private final JdbcOperations jdbc;
    private final MemoryWriteDeclarationJsonCodec memoryCodec;

    public MySqlTurnEngineAssignmentAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.memoryCodec = new MemoryWriteDeclarationJsonCodec();
    }

    @Override
    public MigrationStateSnapshot current() {
        MigrationStateRow row = jdbc.queryForObject(
                SELECT_MIGRATION_WITHOUT_LOCK, (rs, rowNum) -> migrationState(rs));
        if (row == null) {
            throw new IllegalStateException("TURN_MIGRATION_STATE_MISSING");
        }
        return row.snapshot();
    }

    @Override
    @Transactional
    public AdmissionWriteOutcome assignOrReuse(TurnEngineAssignmentCommand command) {
        MigrationStateRow migration = jdbc.queryForObject(
                SELECT_MIGRATION, (rs, rowNum) -> migrationState(rs));
        if (migration == null) {
            throw new IllegalStateException("TURN_MIGRATION_STATE_MISSING");
        }
        if (migration.generation != command.migration().generation()
                || migration.mode != command.migration().mode()) {
            return new AdmissionWriteOutcome.Rejected(command.key(), "MIGRATION_GENERATION_CHANGED");
        }

        AssignmentRow existing = find(command.key());
        if (existing != null) {
            if (!existing.diagramId.equals(command.diagramId())) {
                // A canonical TurnKey cannot be rebound to another diagram on retry.
                return new AdmissionWriteOutcome.Rejected(command.key(), "DIAGRAM_BINDING_MISMATCH");
            }
            if ("EXPIRED_GONE".equals(existing.legacyRetirementState)) {
                return new AdmissionWriteOutcome.LegacyRetryGone(command.key(), "LEGACY_RETRY_EXPIRED");
            }
            VersionedRequestFingerprint matched = command.fingerprints().candidates().stream()
                    .filter(candidate -> candidate.schemaVersion() == existing.fingerprintSchemaVersion)
                    .filter(candidate -> candidate.digest().equals(existing.fingerprint))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                return new AdmissionWriteOutcome.Rejected(command.key(), "REQUEST_FINGERPRINT_CONFLICT");
            }
            if (!memoryCodec.decode(existing.memoryWriteJson).equals(command.memoryWrite())) {
                return new AdmissionWriteOutcome.Rejected(command.key(), "MEMORY_DECLARATION_CONFLICT");
            }
            return new AdmissionWriteOutcome.Reused(existing.toAssignment(memoryCodec));
        }

        SelectedTurnEngine selectedEngine = selectEngine(migration.mode);
        if (selectedEngine == null) {
            return new AdmissionWriteOutcome.Rejected(command.key(), "TURN_ENGINE_RETIRED");
        }
        MemoryWriteValues memory = memoryValues(command.memoryWrite());
        jdbc.update(
                INSERT_ASSIGNMENT,
                command.key().ownerKey(),
                command.key().canonicalConversationId(),
                command.diagramId(),
                command.key().turnId(),
                command.fingerprints().current().schemaVersion(),
                command.fingerprints().current().digest(),
                selectedEngine.name(),
                migration.generation,
                migration.mode.name(),
                command.policy().schemaVersion(),
                command.policy().snapshotJson(),
                command.policy().policyHash(),
                memory.schemaVersion,
                memory.json,
                memory.digest,
                selectedEngine.name(),
                selectedEngine.name(),
                selectedEngine.name(),
                selectedEngine.name());

        AssignmentRow persisted = find(command.key());
        if (persisted == null) {
            throw new IllegalStateException("TURN_ASSIGNMENT_NOT_PERSISTED");
        }
        return new AdmissionWriteOutcome.Assigned(persisted.toAssignment(memoryCodec));
    }

    private AssignmentRow find(TurnKey key) {
        List<AssignmentRow> rows = jdbc.query(
                SELECT_ASSIGNMENT,
                (rs, rowNum) -> assignment(rs),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SelectedTurnEngine selectEngine(TurnEngineMode mode) {
        return switch (mode) {
            case LEGACY, V2_CANARY -> SelectedTurnEngine.LEGACY;
            case ALL_V2 -> SelectedTurnEngine.V2;
            case RETIRED -> null;
        };
    }

    private MemoryWriteValues memoryValues(MemoryWriteDeclaration declaration) {
        String json = memoryCodec.encode(declaration);
        String digest = declaration instanceof NoMemoryWrite
                ? "NONE"
                : ((org.zipp.ai.application.turn.RememberDecisionDeclaration) declaration).digest().value();
        return new MemoryWriteValues(1, json, digest);
    }

    private MigrationStateRow migrationState(ResultSet rs) throws SQLException {
        return new MigrationStateRow(
                rs.getLong("generation"),
                TurnEngineMode.valueOf(rs.getString("mode")),
                rs.getTimestamp("switched_at"));
    }

    private AssignmentRow assignment(ResultSet rs) throws SQLException {
        return new AssignmentRow(
                rs.getString("owner_key"),
                rs.getString("conversation_id"),
                rs.getString("diagram_id"),
                rs.getString("turn_id"),
                rs.getInt("request_fingerprint_schema_version"),
                rs.getString("request_fingerprint"),
                SelectedTurnEngine.valueOf(rs.getString("selected_engine")),
                new MigrationStateSnapshot(
                        rs.getLong("migration_generation"),
                        TurnEngineMode.valueOf(rs.getString("migration_mode")),
                        InstantSupport.requireUtc(rs.getTimestamp("created_at"))),
                new ExecutionPolicySnapshot(
                        rs.getInt("execution_policy_schema_version"),
                        TurnEngineMode.valueOf(rs.getString("migration_mode")),
                        rs.getString("execution_policy_snapshot_json"),
                        rs.getString("execution_policy_hash")),
                rs.getInt("memory_write_schema_version"),
                rs.getString("memory_write_declaration_json"),
                rs.getString("memory_write_digest"),
                rs.getString("legacy_retirement_state"));
    }

    private record MemoryWriteValues(Integer schemaVersion, String json, String digest) {
    }

    private record MigrationStateRow(long generation, TurnEngineMode mode, Timestamp switchedAt) {
        MigrationStateSnapshot snapshot() {
            return new MigrationStateSnapshot(generation, mode, InstantSupport.requireUtc(switchedAt));
        }
    }

    private record AssignmentRow(
            String ownerKey,
            String conversationId,
            String diagramId,
            String turnId,
            int fingerprintSchemaVersion,
            String fingerprint,
            SelectedTurnEngine selectedEngine,
            MigrationStateSnapshot migration,
            ExecutionPolicySnapshot policy,
            int memoryWriteSchemaVersion,
            String memoryWriteJson,
            String memoryWriteDigest,
            String legacyRetirementState
    ) {
        TurnEngineAssignment toAssignment(MemoryWriteDeclarationJsonCodec codec) {
            return new TurnEngineAssignment(
                    new TurnKey(ownerKey, conversationId, turnId),
                    diagramId,
                    new VersionedRequestFingerprint(fingerprintSchemaVersion, fingerprint),
                    selectedEngine,
                    migration,
                    policy,
                    codec.decode(memoryWriteJson));
        }
    }

    private static final class InstantSupport {
        private InstantSupport() {
        }

        static java.time.Instant requireUtc(Timestamp timestamp) {
            if (timestamp == null) {
                throw new IllegalStateException("TURN_TIMESTAMP_MISSING");
            }
            return timestamp.toInstant();
        }
    }
}
