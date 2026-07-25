package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
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
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.StartupOrphanReconciler;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort.HeartbeatOutcome;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQuery;
import org.zipp.ai.application.turn.TurnStatusQueryPort;
import org.zipp.ai.application.turn.TurnStatusView;

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
        AttemptDeadlineCancellationPort,
        StartupOrphanReconciler {

    private static final String SELECT = """
            SELECT current_attempt_id, attempt_epoch, lease_ttl_ms, lease_expires_at,
                   status, terminal_code, terminal_payload_type, terminal_payload_ref,
                   terminal_payload_json, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
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

    private final JdbcTemplate jdbc;

    public MySqlTurnLifecycleAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public TurnStatusView get(AuthenticatedActor actor, TurnStatusQuery query) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(query, "query");
        if (!actor.ownerKey().equals(query.key().ownerKey())) {
            throw new IllegalStateException("TURN_NOT_FOUND");
        }
        ExecutionRow row = find(query.key());
        if (row == null) {
            throw new IllegalStateException("TURN_NOT_FOUND");
        }
        return row.statusView(query.key());
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
        ExecutionRow current = find(command.key());
        if (current == null) {
            return new CancelTurnOutcome.Rejected("TURN_NOT_FOUND");
        }
        if (isTerminal(current.status)) {
            return new CancelTurnOutcome.AlreadyTerminal(current.outcome());
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
            return new TurnAttemptLeasePort.LeaseRenewed(new AttemptLease(
                    renewed.attemptId, renewed.attemptEpoch, renewed.leaseExpiresAt, renewed.leaseTtlMillis));
        }
        ExecutionRow current = find(attempt.key());
        if (current == null) {
            return new TurnAttemptLeasePort.LeaseTerminalUnavailable(
                    new TurnStatusView(attempt.key(), TurnStatus.FAILED, null, 0,
                            "TURN_NOT_FOUND", null, Instant.now()),
                    TurnFailureCode.TERMINAL_UNAVAILABLE,
                    Duration.ofSeconds(1));
        }
        if (isTerminal(current.status)) {
            return new TurnAttemptLeasePort.LeaseAlreadyTerminal(current.outcome());
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
            return new DeadlineCancelOutcome.Cancelled(status(attempt.key()));
        }
        ExecutionRow current = find(attempt.key());
        if (current == null) {
            return new DeadlineCancelOutcome.TransientFailure("TURN_NOT_FOUND");
        }
        if (isTerminal(current.status)) {
            return new DeadlineCancelOutcome.AlreadyTerminal(current.outcome());
        }
        return new DeadlineCancelOutcome.FenceLost(current.statusView(attempt.key()));
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
        List<ExecutionRow> rows = jdbc.query(
                SELECT,
                (rs, rowNum) -> row(rs),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ExecutionRow row(ResultSet rs) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        return new ExecutionRow(
                rs.getString("current_attempt_id"),
                rs.getLong("attempt_epoch"),
                rs.getLong("lease_ttl_ms"),
                leaseExpiresAt == null ? null : leaseExpiresAt.toInstant(),
                TurnStatus.valueOf(rs.getString("status")),
                rs.getString("terminal_code"),
                rs.getString("terminal_payload_type"),
                rs.getString("terminal_payload_ref"),
                rs.getString("terminal_payload_json"),
                updatedAt == null ? Instant.now() : updatedAt.toInstant());
    }

    private boolean isTerminal(TurnStatus status) {
        return status != TurnStatus.RUNNING && status != TurnStatus.ORPHANED_RETRYABLE;
    }

    private record ExecutionRow(
            String attemptId,
            long attemptEpoch,
            long leaseTtlMillis,
            Instant leaseExpiresAt,
            TurnStatus status,
            String terminalCode,
            String terminalPayloadType,
            String terminalPayloadRef,
            String terminalPayloadJson,
            Instant updatedAt
    ) {
        TurnStatusView statusView(TurnKey key) {
            return new TurnStatusView(
                    key, status, attemptId, attemptEpoch, terminalCode, terminalPayloadRef, updatedAt);
        }

        PersistedTurnOutcome outcome() {
            if (terminalCode == null || terminalPayloadType == null) {
                throw new IllegalStateException("TURN_TERMINAL_PAYLOAD_UNAVAILABLE");
            }
            return new PersistedTurnOutcome(
                    status, terminalCode, terminalPayloadType, terminalPayloadRef, terminalPayloadJson);
        }
    }

}
