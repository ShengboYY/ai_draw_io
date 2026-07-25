package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommit;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommitPort;
import org.zipp.ai.application.turn.TerminalOutcomeDecoder;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Fenced terminal-only persistence for clarification, rejection, and cancellation outcomes. */
@Repository
public class MySqlTerminalOnlyTurnCommitAdapter implements TerminalOnlyTurnCommitPort {
    private static final String COMMIT = """
            UPDATE turn_execution
            SET status = ?, terminal_code = ?, terminal_payload_type = ?,
                terminal_payload_schema_version = 1, terminal_payload_ref = ?,
                terminal_payload_json = ?, completed_at = CURRENT_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
            """;
    private static final String SELECT = """
            SELECT current_attempt_id, attempt_epoch, status, terminal_code,
                   terminal_payload_type, terminal_payload_ref,
                   terminal_payload_schema_version, terminal_payload_json, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
    /** Current read used after a failed CAS so a concurrent terminal winner is observable. */
    private static final String SELECT_FOR_UPDATE = SELECT + "FOR UPDATE\n";

    private final JdbcOperations jdbc;
    private final TerminalOutcomeDecoder terminalDecoder = new TerminalOutcomeDecoder();

    public MySqlTerminalOnlyTurnCommitAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public FencedCommitOutcome commit(TerminalOnlyTurnCommit command) {
        Objects.requireNonNull(command, "command");
        FencedAttempt attempt = command.attempt();
        int updated = jdbc.update(
                COMMIT,
                command.terminalStatus().name(),
                command.terminalCode(),
                command.terminalPayloadType(),
                command.terminalPayloadRef(),
                command.terminalPayloadJson(),
                attempt.key().ownerKey(),
                attempt.key().canonicalConversationId(),
                attempt.key().turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (updated == 1) {
            return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                    command.terminalStatus(),
                    command.terminalCode(),
                    command.terminalPayloadType(),
                    command.terminalPayloadRef(),
                    command.terminalPayloadJson()));
        }

        ExecutionRow current = findForUpdate(attempt.key());
        if (current == null) {
            return new FencedCommitOutcome.Rejected("TURN_EXECUTION_NOT_FOUND");
        }
        if (isTerminal(current.status)) {
            TerminalOutcomeDecoder.DecodeResult decoded = current.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new FencedCommitOutcome.AlreadyTerminal(ready.outcome());
            }
            return new FencedCommitOutcome.TerminalUnavailable(
                    current.statusView(attempt.key()),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }
        return new FencedCommitOutcome.FenceLost(current.statusView(attempt.key()));
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

    private ExecutionRow row(ResultSet rs) throws SQLException {
        return new ExecutionRow(
                rs.getString("current_attempt_id"),
                rs.getLong("attempt_epoch"),
                TurnStatus.valueOf(rs.getString("status")),
                rs.getString("terminal_code"),
                rs.getString("terminal_payload_type"),
                rs.getString("terminal_payload_ref"),
                rs.getObject("terminal_payload_schema_version", Integer.class),
                rs.getString("terminal_payload_json"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
    }

    private boolean isTerminal(TurnStatus status) {
        return status.isTerminal();
    }

    private record ExecutionRow(
            String attemptId,
            long attemptEpoch,
            TurnStatus status,
            String terminalCode,
            String terminalPayloadType,
            String terminalPayloadRef,
            Integer terminalPayloadSchemaVersion,
            String terminalPayloadJson,
            Instant updatedAt
    ) {
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
