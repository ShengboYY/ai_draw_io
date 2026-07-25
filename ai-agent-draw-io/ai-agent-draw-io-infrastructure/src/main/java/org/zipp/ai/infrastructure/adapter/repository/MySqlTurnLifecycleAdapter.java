package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.AttemptDeadlineReason;
import org.zipp.ai.application.turn.AttemptDeadlineCancellationPort;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.CancelTurnCommand;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.ExplicitTurnCancellationPort;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.StartupOrphanReconciler;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort.HeartbeatOutcome;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQuery;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.zipp.ai.application.turn.TurnStatusQueryPort;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TerminalOutcomeDecoder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Shared durable lifecycle adapter for owner-fenced status, cancel, heartbeat, and restart repair. */
@Repository
public class MySqlTurnLifecycleAdapter implements
        TurnStatusQueryPort,
        ExplicitTurnCancellationPort,
        TurnAttemptLeasePort,
        TurnAttemptExecutionStatePort,
        AttemptDeadlineCancellationPort,
        StartupOrphanReconciler,
        TurnAttemptTakeoverPort {

    private static final String SELECT = """
            SELECT current_attempt_id, attempt_epoch, lease_ttl_ms, lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now,
                   status, terminal_code, terminal_payload_type, terminal_payload_ref,
                   terminal_payload_schema_version, terminal_payload_json, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
    /** Current read used after a failed CAS so REPEATABLE READ cannot hide the winner. */
    private static final String SELECT_FOR_UPDATE = SELECT + "FOR UPDATE\n";
    private static final String HEARTBEAT = """
            UPDATE turn_execution
            SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL (lease_ttl_ms * 1000) MICROSECOND),
                last_heartbeat_at = CURRENT_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
            """;
    private static final String CANCEL = """
            UPDATE turn_execution
            SET status = 'CANCELLED', terminal_code = 'CANCELLED_BY_USER',
                terminal_payload_type = 'cancel', terminal_payload_schema_version = 1,
                terminal_payload_json = '{}', cancelled_at = CURRENT_TIMESTAMP(3),
                cancel_reason = ?, completed_at = CURRENT_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING'
            """;
    private static final String ORPHAN = """
            UPDATE turn_execution
            SET status = 'ORPHANED_RETRYABLE', updated_at = CURRENT_TIMESTAMP(3)
            WHERE status = 'RUNNING'
            """;
    private static final String SELECT_TAKEOVER = """
            SELECT current_attempt_id, attempt_epoch, lease_ttl_ms, lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now,
                   execution_policy_schema_version, migration_mode,
                   execution_policy_snapshot_json, execution_policy_hash,
                   turn_input_binding_digest, context_message_high_water,
                   (status = 'RUNNING' AND lease_expires_at > CURRENT_TIMESTAMP(3)) AS lease_active,
                   status, terminal_code, terminal_payload_type, terminal_payload_ref,
                   terminal_payload_schema_version, terminal_payload_json, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            FOR UPDATE
            """;

    private final JdbcOperations jdbc;
    private final TerminalOutcomeDecoder terminalDecoder = new TerminalOutcomeDecoder();

    public MySqlTurnLifecycleAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public TurnStatusQueryOutcome get(AuthenticatedActor actor, TurnStatusQuery query) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(query, "query");
        if (!actor.ownerKey().equals(query.key().ownerKey())) {
            throw new IllegalStateException("TURN_NOT_FOUND");
        }
        ExecutionRow row = find(query.key());
        if (row == null) {
            throw new IllegalStateException("TURN_NOT_FOUND");
        }
        return row.statusOutcome(query.key(), terminalDecoder);
    }

    @Override
    @Transactional
    public TurnAttemptExecutionStatePort.StateOutcome check(FencedAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        ExecutionRow current = findForUpdate(attempt.key());
        if (current == null) {
            return new TurnAttemptExecutionStatePort.StateOutcome.Unavailable(
                    new TurnStatusView(attempt.key(), TurnStatus.FAILED, null, 0,
                            "TURN_NOT_FOUND", null, Instant.now()),
                    "TURN_NOT_FOUND", Duration.ofSeconds(1));
        }
        if (isTerminal(current.status)) {
            TerminalOutcomeDecoder.DecodeResult decoded = current.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new TurnAttemptExecutionStatePort.StateOutcome.AlreadyTerminal(ready.outcome());
            }
            return new TurnAttemptExecutionStatePort.StateOutcome.Unavailable(
                    current.statusView(attempt.key()),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code(),
                    Duration.ofSeconds(1));
        }
        if (current.status != TurnStatus.RUNNING
                || current.leaseExpiresAt == null
                || !current.leaseExpiresAt.isAfter(current.databaseNow)
                || !attempt.attemptId().equals(current.attemptId)
                || attempt.attemptEpoch() != current.attemptEpoch) {
            return new TurnAttemptExecutionStatePort.StateOutcome.FenceLost(
                    current.statusView(attempt.key()));
        }
        return new TurnAttemptExecutionStatePort.StateOutcome.Active();
    }

    @Override
    @Transactional
    public CancelTurnOutcome cancel(AuthenticatedActor actor, CancelTurnCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        if (!actor.ownerKey().equals(command.key().ownerKey())) {
            return new CancelTurnOutcome.Rejected("OWNER_MISMATCH");
        }
        int updated = jdbc.update(
                CANCEL,
                command.reason(),
                command.key().ownerKey(),
                command.key().canonicalConversationId(),
                command.key().turnId());
        if (updated == 1) {
            return new CancelTurnOutcome.Cancelled(status(command.key()));
        }
        ExecutionRow current = findForUpdate(command.key());
        if (current == null) {
            return new CancelTurnOutcome.Rejected("TURN_NOT_FOUND");
        }
        if (isTerminal(current.status)) {
            return current.cancelOutcome(command.key(), terminalDecoder);
        }
        return new CancelTurnOutcome.FenceLost(current.statusView(command.key()));
    }

    @Override
    @Transactional
    public HeartbeatOutcome heartbeat(FencedAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        int updated = jdbc.update(
                HEARTBEAT,
                attempt.key().ownerKey(),
                attempt.key().canonicalConversationId(),
                attempt.key().turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (updated == 1) {
            ExecutionRow renewed = find(attempt.key());
            if (renewed == null || renewed.leaseExpiresAt == null) {
                return new TurnAttemptLeasePort.LeaseTerminalUnavailable(
                        new TurnStatusView(attempt.key(), TurnStatus.RUNNING,
                                attempt.attemptId(), attempt.attemptEpoch(), null, null, Instant.now()),
                        TurnFailureCode.TERMINAL_UNAVAILABLE, Duration.ofSeconds(1));
            }
            return new TurnAttemptLeasePort.LeaseRenewed(renewed.lease());
        }
        ExecutionRow current = findForUpdate(attempt.key());
        if (current == null) {
            return new TurnAttemptLeasePort.LeaseTerminalUnavailable(
                    new TurnStatusView(attempt.key(), TurnStatus.FAILED, null, 0,
                            "TURN_NOT_FOUND", null, Instant.now()),
                    TurnFailureCode.TERMINAL_UNAVAILABLE,
                    Duration.ofSeconds(1));
        }
        if (isTerminal(current.status)) {
            TerminalOutcomeDecoder.DecodeResult decoded = current.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new TurnAttemptLeasePort.LeaseAlreadyTerminal(ready.outcome());
            }
            return new TurnAttemptLeasePort.LeaseTerminalUnavailable(
                    current.statusView(attempt.key()),
                    TurnFailureCode.TERMINAL_UNAVAILABLE,
                    Duration.ofSeconds(1));
        }
        if (!attempt.attemptId().equals(current.attemptId)
                || attempt.attemptEpoch() != current.attemptEpoch) {
            return new TurnAttemptLeasePort.LeaseFenceLost(current.statusView(attempt.key()));
        }
        return new TurnAttemptLeasePort.LeaseTerminalUnavailable(
                current.statusView(attempt.key()),
                TurnFailureCode.TERMINAL_UNAVAILABLE,
                Duration.ofSeconds(1));
    }

    @Override
    @Transactional
    public DeadlineCancelOutcome cancel(FencedAttempt attempt, AttemptDeadlineReason reason) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(reason, "reason");
        int updated = jdbc.update(
                """
                UPDATE turn_execution
                SET status = 'CANCELLED', terminal_code = ?, terminal_payload_type = 'deadline',
                    terminal_payload_schema_version = 1, terminal_payload_json = '{}',
                    cancel_reason = ?, cancelled_at = CURRENT_TIMESTAMP(3),
                    completed_at = CURRENT_TIMESTAMP(3)
                WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
                  AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
                """,
                reason.name(),
                reason.name(),
                attempt.key().ownerKey(),
                attempt.key().canonicalConversationId(),
                attempt.key().turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (updated == 1) {
            ExecutionRow cancelled = findForUpdate(attempt.key());
            if (cancelled == null) {
                return new DeadlineCancelOutcome.TransientFailure("TURN_NOT_FOUND_AFTER_UPDATE");
            }
            TerminalOutcomeDecoder.DecodeResult decoded = cancelled.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new DeadlineCancelOutcome.Cancelled(ready.outcome());
            }
            return new DeadlineCancelOutcome.TerminalUnavailable(
                    cancelled.statusView(attempt.key()),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }
        ExecutionRow current = findForUpdate(attempt.key());
        if (current == null) {
            return new DeadlineCancelOutcome.TransientFailure("TURN_NOT_FOUND");
        }
        if (isTerminal(current.status)) {
            TerminalOutcomeDecoder.DecodeResult decoded = current.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new DeadlineCancelOutcome.AlreadyTerminal(ready.outcome());
            }
            return new DeadlineCancelOutcome.TerminalUnavailable(
                    current.statusView(attempt.key()),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }
        return new DeadlineCancelOutcome.FenceLost(current.statusView(attempt.key()));
    }

    @Override
    @Transactional
    public TurnAttemptTakeoverPort.TakeoverOutcome takeover(TurnKey key) {
        Objects.requireNonNull(key, "key");
        TakeoverRow current = findTakeover(key);
        if (current == null) {
            return new TurnAttemptTakeoverPort.Rejected("TURN_NOT_FOUND");
        }
        if (isTerminal(current.status)) {
            TerminalOutcomeDecoder.DecodeResult decoded = current.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new TurnAttemptTakeoverPort.AlreadyTerminal(ready.outcome());
            }
            return new TurnAttemptTakeoverPort.TerminalUnavailable(
                    current.statusView(key),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }
        if (current.inputBindingDigest == null || current.policyJson == null || current.policyHash == null) {
            return new TurnAttemptTakeoverPort.Rejected("TAKEOVER_SNAPSHOT_UNAVAILABLE");
        }
        String nextAttemptId = "attempt_" + java.util.UUID.randomUUID();
        int updated = jdbc.update(
                """
                UPDATE turn_execution
                SET current_attempt_id = ?, attempt_epoch = attempt_epoch + 1,
                    lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL (lease_ttl_ms * 1000) MICROSECOND),
                    last_heartbeat_at = CURRENT_TIMESTAMP(3), status = 'RUNNING',
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
                  AND (status = 'ORPHANED_RETRYABLE'
                    OR (status = 'RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP(3))))
                """,
                nextAttemptId,
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        if (updated != 1) {
            TakeoverRow raced = findTakeover(key);
            if (raced != null && isTerminal(raced.status)) {
                TerminalOutcomeDecoder.DecodeResult decoded = raced.decode(terminalDecoder);
                if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                    return new TurnAttemptTakeoverPort.AlreadyTerminal(ready.outcome());
                }
                return new TurnAttemptTakeoverPort.TerminalUnavailable(
                        raced.statusView(key),
                        ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
            }
            if (raced != null && raced.leaseActive) {
                return new TurnAttemptTakeoverPort.LeaseActive(raced.statusView(key));
            }
            return new TurnAttemptTakeoverPort.Rejected("TAKEOVER_RACE_LOST");
        }
        TakeoverRow claimed = findTakeover(key);
        if (claimed == null || claimed.leaseExpiresAt == null || claimed.attemptId == null) {
            throw new IllegalStateException("TURN_TAKEOVER_NOT_CLAIMED");
        }
        return new TurnAttemptTakeoverPort.Claimed(claimed.fencedAttempt(key));
    }

    @Override
    @Transactional
    public int reconcile(org.zipp.ai.application.turn.InstanceBootId currentBootId) {
        Objects.requireNonNull(currentBootId, "currentBootId");
        return jdbc.update(ORPHAN);
    }

    private TurnStatusView status(TurnKey key) {
        ExecutionRow row = find(key);
        if (row == null) {
            throw new IllegalStateException("TURN_NOT_FOUND_AFTER_UPDATE");
        }
        return row.statusView(key);
    }

    private ExecutionRow find(TurnKey key) {
        return find(key, SELECT);
    }

    private ExecutionRow findForUpdate(TurnKey key) {
        return find(key, SELECT_FOR_UPDATE);
    }

    private ExecutionRow find(TurnKey key, String sql) {
        List<ExecutionRow> rows = jdbc.query(
                sql,
                (rs, rowNum) -> row(rs),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TakeoverRow findTakeover(TurnKey key) {
        List<TakeoverRow> rows = jdbc.query(
                SELECT_TAKEOVER,
                (rs, rowNum) -> takeoverRow(rs),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TakeoverRow takeoverRow(ResultSet rs) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        return new TakeoverRow(
                rs.getString("current_attempt_id"),
                rs.getLong("attempt_epoch"),
                rs.getLong("lease_ttl_ms"),
                leaseExpiresAt == null ? null : leaseExpiresAt.toInstant(),
                rs.getTimestamp("database_now").toInstant(),
                rs.getInt("execution_policy_schema_version"),
                TurnEngineMode.valueOf(rs.getString("migration_mode")),
                rs.getString("execution_policy_snapshot_json"),
                rs.getString("execution_policy_hash"),
                rs.getString("turn_input_binding_digest"),
                rs.getLong("context_message_high_water"),
                rs.getBoolean("lease_active"),
                TurnStatus.valueOf(rs.getString("status")),
                rs.getString("terminal_code"),
                rs.getString("terminal_payload_type"),
                rs.getString("terminal_payload_ref"),
                rs.getObject("terminal_payload_schema_version", Integer.class),
                rs.getString("terminal_payload_json"),
                updatedAt == null ? Instant.now() : updatedAt.toInstant());
    }

    private ExecutionRow row(ResultSet rs) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        return new ExecutionRow(
                rs.getString("current_attempt_id"),
                rs.getLong("attempt_epoch"),
                rs.getLong("lease_ttl_ms"),
                leaseExpiresAt == null ? null : leaseExpiresAt.toInstant(),
                rs.getTimestamp("database_now").toInstant(),
                TurnStatus.valueOf(rs.getString("status")),
                rs.getString("terminal_code"),
                rs.getString("terminal_payload_type"),
                rs.getString("terminal_payload_ref"),
                rs.getObject("terminal_payload_schema_version", Integer.class),
                rs.getString("terminal_payload_json"),
                updatedAt == null ? Instant.now() : updatedAt.toInstant());
    }

    private boolean isTerminal(TurnStatus status) {
        return status.isTerminal();
    }

    private record ExecutionRow(
            String attemptId,
            long attemptEpoch,
            long leaseTtlMillis,
            Instant leaseExpiresAt,
            Instant databaseNow,
            TurnStatus status,
            String terminalCode,
            String terminalPayloadType,
            String terminalPayloadRef,
            Integer terminalPayloadSchemaVersion,
            String terminalPayloadJson,
            Instant updatedAt
        ) {
        AttemptLease lease() {
            return AttemptLease.fromDatabaseClock(
                    attemptId, attemptEpoch, databaseNow, leaseExpiresAt, leaseTtlMillis);
        }

        TurnStatusView statusView(TurnKey key) {
            return new TurnStatusView(
                    key, status, attemptId, attemptEpoch, terminalCode, terminalPayloadRef, updatedAt);
        }

        TurnStatusQueryOutcome statusOutcome(TurnKey key, TerminalOutcomeDecoder decoder) {
            if (!status.isTerminal()) {
                return new TurnStatusQueryOutcome.Available(statusView(key));
            }
            TerminalOutcomeDecoder.DecodeResult decoded = decode(decoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded) {
                return new TurnStatusQueryOutcome.Available(statusView(key));
            }
            return new TurnStatusQueryOutcome.TerminalUnavailable(
                    statusView(key),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }

        CancelTurnOutcome cancelOutcome(TurnKey key, TerminalOutcomeDecoder decoder) {
            TerminalOutcomeDecoder.DecodeResult decoded = decode(decoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new CancelTurnOutcome.AlreadyTerminal(ready.outcome());
            }
            return new CancelTurnOutcome.TerminalUnavailable(
                    statusView(key),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }

        TerminalOutcomeDecoder.DecodeResult decode(TerminalOutcomeDecoder decoder) {
            return decoder.decode(
                    status,
                    terminalCode,
                    terminalPayloadSchemaVersion,
                    terminalPayloadType,
                    terminalPayloadRef,
                    terminalPayloadJson);
        }
    }

    private record TakeoverRow(
            String attemptId,
            long attemptEpoch,
            long leaseTtlMillis,
            Instant leaseExpiresAt,
            Instant databaseNow,
            int policySchemaVersion,
            TurnEngineMode migrationMode,
            String policyJson,
            String policyHash,
            String inputBindingDigest,
            long contextMessageHighWater,
            boolean leaseActive,
            TurnStatus status,
            String terminalCode,
            String terminalPayloadType,
            String terminalPayloadRef,
            Integer terminalPayloadSchemaVersion,
            String terminalPayloadJson,
            Instant updatedAt
    ) {
        FencedAttempt fencedAttempt(TurnKey key) {
            return new FencedAttempt(
                    key,
                    AttemptLease.fromDatabaseClock(
                            attemptId, attemptEpoch, databaseNow, leaseExpiresAt, leaseTtlMillis),
                    contextMessageHighWater,
                    inputBindingDigest,
                    new ExecutionPolicySnapshot(policySchemaVersion, migrationMode, policyJson, policyHash));
        }

        TurnStatusView statusView(TurnKey key) {
            return new TurnStatusView(
                    key, status, attemptId, attemptEpoch, terminalCode, terminalPayloadRef, updatedAt);
        }

        TerminalOutcomeDecoder.DecodeResult decode(TerminalOutcomeDecoder decoder) {
            return decoder.decode(
                    status,
                    terminalCode,
                    terminalPayloadSchemaVersion,
                    terminalPayloadType,
                    terminalPayloadRef,
                    terminalPayloadJson);
        }
    }

}
