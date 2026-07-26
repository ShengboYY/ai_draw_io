package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.SourceCommitBinding;
import org.zipp.ai.application.turn.SourceExecutionBindingOutcome;
import org.zipp.ai.application.turn.SourceExecutionBindingPort;
import org.zipp.ai.application.turn.TerminalOutcomeDecoder;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Pins the exact freeze output under the current attempt fence before generation. */
@Repository
public class MySqlSourceExecutionBindingAdapter implements SourceExecutionBindingPort {

    private static final String LOCK_EXECUTION = """
            SELECT current_attempt_id, attempt_epoch, lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now, status, terminal_code,
                   terminal_payload_type, terminal_payload_ref,
                   terminal_payload_schema_version, terminal_payload_json, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            FOR UPDATE
            """;
    private static final String SELECT_BINDING = """
            SELECT plan_fingerprint, source_snapshot_ref, snapshot_binding_digest,
                   execution_entry_id
            FROM turn_source_execution_binding
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
    private static final String INSERT_BINDING = """
            INSERT INTO turn_source_execution_binding (
                owner_key, conversation_id, turn_id, plan_fingerprint,
                source_snapshot_ref, snapshot_binding_digest, execution_entry_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String LINK_EXECUTION = """
            UPDATE turn_execution
            SET source_snapshot_ref = ?
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
            """;

    private final JdbcOperations jdbc;
    private final TerminalOutcomeDecoder terminalDecoder = new TerminalOutcomeDecoder();

    public MySqlSourceExecutionBindingAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public SourceExecutionBindingOutcome pin(
            FencedAttempt attempt,
            SourceCommitBinding binding
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(binding, "binding");
        ExecutionRow execution = lockExecution(attempt.key());
        if (execution == null) {
            return new SourceExecutionBindingOutcome.Rejected("TURN_EXECUTION_NOT_FOUND");
        }
        if (execution.status().isTerminal()) {
            TerminalOutcomeDecoder.DecodeResult decoded = execution.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Unavailable unavailable) {
                return new SourceExecutionBindingOutcome.TerminalUnavailable(
                        execution.view(attempt.key()), unavailable.code());
            }
            return new SourceExecutionBindingOutcome.FenceLost(execution.view(attempt.key()));
        }
        if (!execution.active(attempt)) {
            return new SourceExecutionBindingOutcome.FenceLost(execution.view(attempt.key()));
        }
        PinnedRow existing = findBinding(attempt.key());
        PinnedRow proposed = PinnedRow.from(binding);
        if (existing != null) {
            return existing.equals(proposed)
                    ? new SourceExecutionBindingOutcome.AlreadyPinned()
                    : new SourceExecutionBindingOutcome.Rejected(
                    "SOURCE_EXECUTION_BINDING_CONFLICT");
        }
        TurnKey key = attempt.key();
        jdbc.update(
                INSERT_BINDING,
                key.ownerKey(),
                key.canonicalConversationId(),
                key.turnId(),
                proposed.planFingerprint(),
                proposed.sourceSnapshotRef(),
                proposed.snapshotBindingDigest(),
                proposed.executionEntryId());
        int linked = jdbc.update(
                LINK_EXECUTION,
                binding.sourceSnapshotRef(),
                key.ownerKey(),
                key.canonicalConversationId(),
                key.turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (linked != 1) {
            throw new IllegalStateException("SOURCE_EXECUTION_BINDING_FENCE_NOT_APPLIED");
        }
        return new SourceExecutionBindingOutcome.Pinned();
    }

    private ExecutionRow lockExecution(TurnKey key) {
        List<ExecutionRow> rows = jdbc.query(
                LOCK_EXECUTION,
                (rs, rowNum) -> new ExecutionRow(
                        rs.getString("current_attempt_id"),
                        rs.getLong("attempt_epoch"),
                        instant(rs.getTimestamp("lease_expires_at")),
                        instant(rs.getTimestamp("database_now")),
                        TurnStatus.valueOf(rs.getString("status")),
                        rs.getString("terminal_code"),
                        rs.getString("terminal_payload_type"),
                        rs.getString("terminal_payload_ref"),
                        rs.getObject("terminal_payload_schema_version", Integer.class),
                        rs.getString("terminal_payload_json"),
                        instant(rs.getTimestamp("updated_at"))),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PinnedRow findBinding(TurnKey key) {
        List<PinnedRow> rows = jdbc.query(
                SELECT_BINDING,
                (rs, rowNum) -> new PinnedRow(
                        rs.getString("plan_fingerprint"),
                        rs.getString("source_snapshot_ref"),
                        rs.getString("snapshot_binding_digest"),
                        rs.getString("execution_entry_id")),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record PinnedRow(
            String planFingerprint,
            String sourceSnapshotRef,
            String snapshotBindingDigest,
            String executionEntryId
    ) {
        static PinnedRow from(SourceCommitBinding binding) {
            return new PinnedRow(
                    binding.planIdentity().planFingerprint(),
                    binding.sourceSnapshotRef(),
                    binding.snapshotBindingDigest(),
                    binding.executionEntryId());
        }
    }

    private record ExecutionRow(
            String attemptId,
            long attemptEpoch,
            Instant leaseExpiresAt,
            Instant databaseNow,
            TurnStatus status,
            String terminalCode,
            String payloadType,
            String payloadRef,
            Integer payloadSchema,
            String payloadJson,
            Instant updatedAt
    ) {
        boolean active(FencedAttempt attempt) {
            return status == TurnStatus.RUNNING
                    && Objects.equals(attemptId, attempt.attemptId())
                    && attemptEpoch == attempt.attemptEpoch()
                    && leaseExpiresAt != null
                    && databaseNow != null
                    && leaseExpiresAt.isAfter(databaseNow);
        }

        TurnStatusView view(TurnKey key) {
            return new TurnStatusView(
                    key, status, attemptId, attemptEpoch, terminalCode, payloadRef, updatedAt);
        }

        TerminalOutcomeDecoder.DecodeResult decode(TerminalOutcomeDecoder decoder) {
            return decoder.decode(
                    status,
                    terminalCode,
                    payloadSchema,
                    payloadType,
                    payloadRef,
                    payloadJson);
        }
    }
}
