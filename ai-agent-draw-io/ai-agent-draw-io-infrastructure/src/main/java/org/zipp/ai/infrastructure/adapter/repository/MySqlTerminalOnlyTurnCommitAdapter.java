package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.DurableClarification;
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
import java.util.Map;
import java.util.Objects;

/** Fenced terminal-only persistence, including durable needs-user-input authority. */
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
    private static final String COMMIT_CLARIFICATION = """
            UPDATE turn_execution
            SET response_message_id = ?, status = ?, terminal_code = ?,
                terminal_payload_type = ?, terminal_payload_schema_version = 1,
                terminal_payload_ref = ?, terminal_payload_json = ?,
                completed_at = CURRENT_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
            """;
    private static final String SELECT = """
            SELECT diagram_id, current_attempt_id, attempt_epoch, lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now, status, terminal_code,
                   terminal_payload_type, terminal_payload_ref,
                   terminal_payload_schema_version, terminal_payload_json,
                   created_at, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
    /** Current read used after a failed CAS so a concurrent terminal winner is observable. */
    private static final String SELECT_FOR_UPDATE = SELECT + "FOR UPDATE\n";
    private static final String LOCK_CONVERSATION = """
            SELECT version
            FROM conversation
            WHERE id = ? AND owner_key = ? AND diagram_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """;
    private static final String ADVANCE_CONVERSATION = """
            UPDATE conversation SET version = version + 1
            WHERE id = ? AND owner_key = ? AND diagram_id = ? AND status = 'ACTIVE'
            """;
    private static final String INSERT_MESSAGE = """
            INSERT INTO diagram_conversation_message (
                diagram_id, conversation_id, user_id, session_id, turn_id,
                client_message_id, role, content, message_sequence, message_status
            ) VALUES (?, ?, ?, NULL, ?, ?, 'agent', ?, ?, 'COMMITTED')
            """;
    private static final String INSERT_CLARIFICATION = """
            INSERT INTO turn_clarification (
                owner_key, conversation_id, turn_id, clarification_id,
                safe_message, option_set_digest, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_OPTION = """
            INSERT INTO turn_clarification_option (
                owner_key, conversation_id, turn_id, clarification_id,
                option_id, option_ordinal, safe_label, candidate_ref,
                candidate_origin, observation_fingerprint, clarification_ref,
                lineage_fingerprint, declaration_digest, context_read_set_digest,
                input_binding_digest
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcOperations jdbc;
    private final TerminalOutcomeDecoder terminalDecoder = new TerminalOutcomeDecoder();

    public MySqlTerminalOnlyTurnCommitAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public FencedCommitOutcome commit(TerminalOnlyTurnCommit command) {
        Objects.requireNonNull(command, "command");
        if (command.clarification().isPresent()) {
            return commitClarification(command, command.clarification().orElseThrow());
        }
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

    private FencedCommitOutcome commitClarification(
            TerminalOnlyTurnCommit command,
            DurableClarification clarification
    ) {
        FencedAttempt attempt = command.attempt();
        ExecutionRow current = findForUpdate(attempt.key());
        if (current == null) {
            return new FencedCommitOutcome.Rejected("TURN_EXECUTION_NOT_FOUND");
        }
        if (current.status().isTerminal()) {
            TerminalOutcomeDecoder.DecodeResult decoded = current.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new FencedCommitOutcome.AlreadyTerminal(ready.outcome());
            }
            return new FencedCommitOutcome.TerminalUnavailable(
                    current.statusView(attempt.key()),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }
        if (!current.active(attempt)) {
            return new FencedCommitOutcome.FenceLost(current.statusView(attempt.key()));
        }
        for (DurableClarification.Option option : clarification.options()) {
            if (!option.candidate().binding().turn().equals(attempt.key())) {
                return new FencedCommitOutcome.Rejected(
                        "CLARIFICATION_CANDIDATE_TURN_MISMATCH");
            }
        }
        Long conversationVersion = lockConversation(
                attempt.key(), current.diagramId());
        if (conversationVersion == null) {
            return new FencedCommitOutcome.Rejected("CONVERSATION_NOT_ACTIVE");
        }
        TurnKey key = attempt.key();
        requireOne(jdbc.update(
                INSERT_CLARIFICATION,
                key.ownerKey(),
                key.canonicalConversationId(),
                key.turnId(),
                clarification.clarificationId().value(),
                clarification.safeMessage(),
                clarification.optionSetDigest(),
                Timestamp.from(current.createdAt().plus(clarification.validity()))),
                "CLARIFICATION_HEADER_NOT_CREATED");
        for (int index = 0; index < clarification.options().size(); index++) {
            DurableClarification.Option option = clarification.options().get(index);
            var candidate = option.candidate();
            var binding = candidate.binding();
            requireOne(jdbc.update(
                    INSERT_OPTION,
                    key.ownerKey(),
                    key.canonicalConversationId(),
                    key.turnId(),
                    clarification.clarificationId().value(),
                    option.optionId(),
                    index,
                    option.safeLabel(),
                    candidate.candidateRef(),
                    candidate.origin().name(),
                    candidate.observationFingerprint(),
                    candidate.clarificationRef(),
                    binding.lineage().value(),
                    binding.declarationDigest(),
                    binding.contextReadSetDigest(),
                    binding.inputBindingDigest()),
                    "CLARIFICATION_OPTION_NOT_CREATED");
        }
        requireOne(jdbc.update(
                ADVANCE_CONVERSATION,
                key.canonicalConversationId(),
                key.ownerKey(),
                current.diagramId()),
                "CLARIFICATION_CONVERSATION_NOT_ADVANCED");
        requireOne(jdbc.update(
                INSERT_MESSAGE,
                current.diagramId(),
                key.canonicalConversationId(),
                key.ownerKey(),
                key.turnId(),
                clarificationMessageId(key),
                clarification.safeMessage(),
                conversationVersion + 1),
                "CLARIFICATION_MESSAGE_NOT_CREATED");
        Long responseMessageId = jdbc.queryForObject(
                "SELECT LAST_INSERT_ID()", Long.class);
        if (responseMessageId == null) {
            throw new IllegalStateException("CLARIFICATION_MESSAGE_NOT_CREATED");
        }
        String payloadRef = clarification.clarificationId().value();
        String payloadJson = JSON.toJSONString(Map.of(
                "clarificationId", payloadRef,
                "safeMessage", clarification.safeMessage(),
                "optionSetDigest", clarification.optionSetDigest(),
                "expiresAt", current.createdAt().plus(
                        clarification.validity()).toString(),
                "options", clarification.options().stream()
                        .map(option -> Map.of(
                                "optionId", option.optionId(),
                                "safeLabel", option.safeLabel()))
                        .toList()));
        int updated = jdbc.update(
                COMMIT_CLARIFICATION,
                responseMessageId,
                command.terminalStatus().name(),
                command.terminalCode(),
                command.terminalPayloadType(),
                payloadRef,
                payloadJson,
                key.ownerKey(),
                key.canonicalConversationId(),
                key.turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (updated != 1) {
            throw new IllegalStateException("CLARIFICATION_COMMIT_FENCE_NOT_APPLIED");
        }
        return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                command.terminalStatus(),
                command.terminalCode(),
                command.terminalPayloadType(),
                payloadRef,
                payloadJson));
    }

    private Long lockConversation(TurnKey key, String diagramId) {
        List<Long> rows = jdbc.query(
                LOCK_CONVERSATION,
                (rs, rowNum) -> rs.getLong("version"),
                key.canonicalConversationId(), key.ownerKey(), diagramId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String clarificationMessageId(TurnKey key) {
        return "turn-v2-clarification-"
                + Integer.toUnsignedString(Objects.hash(
                key.ownerKey(), key.canonicalConversationId(), key.turnId()), 16);
    }

    private void requireOne(int changed, String code) {
        if (changed != 1) {
            throw new IllegalStateException(code);
        }
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
                rs.getString("diagram_id"),
                rs.getString("current_attempt_id"),
                rs.getLong("attempt_epoch"),
                rs.getTimestamp("lease_expires_at") == null
                        ? null : rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getTimestamp("database_now") == null
                        ? null : rs.getTimestamp("database_now").toInstant(),
                TurnStatus.valueOf(rs.getString("status")),
                rs.getString("terminal_code"),
                rs.getString("terminal_payload_type"),
                rs.getString("terminal_payload_ref"),
                rs.getObject("terminal_payload_schema_version", Integer.class),
                rs.getString("terminal_payload_json"),
                rs.getTimestamp("created_at") == null
                        ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());
    }

    private boolean isTerminal(TurnStatus status) {
        return status.isTerminal();
    }

    private record ExecutionRow(
            String diagramId,
            String attemptId,
            long attemptEpoch,
            Instant leaseExpiresAt,
            Instant databaseNow,
            TurnStatus status,
            String terminalCode,
            String terminalPayloadType,
            String terminalPayloadRef,
            Integer terminalPayloadSchemaVersion,
            String terminalPayloadJson,
            Instant createdAt,
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
