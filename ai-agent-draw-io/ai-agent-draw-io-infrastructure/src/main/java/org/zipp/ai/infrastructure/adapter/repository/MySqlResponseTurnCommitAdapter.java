package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorkPort;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.ResponseTurnCommit;
import org.zipp.ai.application.turn.ResponseTurnCommitPort;
import org.zipp.ai.application.turn.TerminalOutcomeDecoder;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;

import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Atomic response/review commit: assistant message and terminal outcome share one transaction. */
@Repository
public class MySqlResponseTurnCommitAdapter implements ResponseTurnCommitPort {

    private static final String LOCK_EXECUTION = """
            SELECT current_attempt_id, attempt_epoch, lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now, status, terminal_code,
                   terminal_payload_type, terminal_payload_ref,
                   terminal_payload_schema_version, terminal_payload_json, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_CONVERSATION = """
            SELECT version
            FROM conversation
            WHERE id = ? AND owner_key = ? AND diagram_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """;
    private static final String ADVANCE_CONVERSATION = """
            UPDATE conversation
            SET version = version + 1
            WHERE id = ? AND owner_key = ? AND diagram_id = ? AND status = 'ACTIVE'
            """;
    private static final String INSERT_MESSAGE = """
            INSERT INTO diagram_conversation_message (
                diagram_id, conversation_id, user_id, session_id, turn_id,
                client_message_id, role, content, message_sequence, message_status
            ) VALUES (?, ?, ?, NULL, ?, ?, 'agent', ?, ?, 'COMMITTED')
            """;
    private static final String COMMIT_EXECUTION = """
            UPDATE turn_execution
            SET response_message_id = ?, status = 'COMPLETED', terminal_code = 'COMPLETED',
                terminal_payload_type = 'response', terminal_payload_schema_version = 1,
                terminal_payload_ref = ?, terminal_payload_json = ?,
                completed_at = UTC_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
            """;

    private final JdbcOperations jdbc;
    private final AutoMemoryExtractionWorkPort memoryExtractionWork;
    private final TerminalOutcomeDecoder terminalDecoder = new TerminalOutcomeDecoder();

    public MySqlResponseTurnCommitAdapter(JdbcOperations jdbc) {
        this(jdbc, AutoMemoryExtractionWorkPort.NOOP);
    }

    @Autowired
    public MySqlResponseTurnCommitAdapter(
            JdbcOperations jdbc,
            AutoMemoryExtractionWorkPort memoryExtractionWork
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.memoryExtractionWork =
                Objects.requireNonNull(memoryExtractionWork, "memoryExtractionWork");
    }

    @Override
    @Transactional
    public FencedCommitOutcome commit(ResponseTurnCommit command) {
        Objects.requireNonNull(command, "command");
        FencedAttempt attempt = command.attempt();
        ExecutionRow execution = findExecution(attempt.key());
        if (execution == null) {
            return new FencedCommitOutcome.Rejected("TURN_EXECUTION_NOT_FOUND");
        }
        if (execution.status().isTerminal()) {
            TerminalOutcomeDecoder.DecodeResult decoded = execution.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new FencedCommitOutcome.AlreadyTerminal(ready.outcome());
            }
            return new FencedCommitOutcome.TerminalUnavailable(
                    execution.statusView(attempt.key()),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }
        if (execution.status() != TurnStatus.RUNNING
                || !Objects.equals(execution.attemptId(), attempt.attemptId())
                || execution.attemptEpoch() != attempt.attemptEpoch()
                || execution.leaseExpiresAt() == null
                || execution.databaseNow() == null
                || !execution.leaseExpiresAt().isAfter(execution.databaseNow())) {
            return new FencedCommitOutcome.FenceLost(execution.statusView(attempt.key()));
        }

        Long conversationVersion = lockConversation(attempt.key(), command.diagramId());
        if (conversationVersion == null) {
            return new FencedCommitOutcome.Rejected("CONVERSATION_NOT_ACTIVE");
        }
        jdbc.update(
                ADVANCE_CONVERSATION,
                attempt.key().canonicalConversationId(),
                attempt.key().ownerKey(),
                command.diagramId());
        long assistantSequence = conversationVersion + 1;
        long responseMessageId = insertAssistantMessage(command, assistantSequence);
        String terminalPayloadJson = JSON.toJSONString(Map.of(
                "responseMessageId", responseMessageId,
                "payloadRef", command.payloadRef()));
        int updated = jdbc.update(
                COMMIT_EXECUTION,
                responseMessageId,
                command.payloadRef(),
                terminalPayloadJson,
                attempt.key().ownerKey(),
                attempt.key().canonicalConversationId(),
                attempt.key().turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (updated != 1) {
            // The transaction rolls back the message and conversation increment if the lease
            // fence expires between the initial check and the terminal CAS.
            throw new IllegalStateException("RESPONSE_COMMIT_FENCE_NOT_APPLIED");
        }
        // The extractor is intentionally outside the terminal transaction.
        memoryExtractionWork.enqueue(attempt.key(), command.diagramId());
        return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                TurnStatus.COMPLETED,
                "COMPLETED",
                "response",
                command.payloadRef(),
                terminalPayloadJson));
    }

    private Long lockConversation(TurnKey key, String diagramId) {
        List<Long> rows = jdbc.query(
                LOCK_CONVERSATION,
                (resultSet, rowNum) -> resultSet.getLong("version"),
                key.canonicalConversationId(), key.ownerKey(), diagramId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insertAssistantMessage(ResponseTurnCommit command, long sequence) {
        jdbc.update(
                INSERT_MESSAGE,
                command.diagramId(),
                command.attempt().key().canonicalConversationId(),
                command.attempt().key().ownerKey(),
                command.attempt().key().turnId(),
                assistantClientMessageId(command.attempt().key()),
                command.assistantMessage(),
                sequence);
        Long messageId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (messageId == null) {
            throw new IllegalStateException("RESPONSE_MESSAGE_NOT_CREATED");
        }
        return messageId;
    }

    private ExecutionRow findExecution(TurnKey key) {
        List<ExecutionRow> rows = jdbc.query(
                LOCK_EXECUTION,
                (resultSet, rowNum) -> new ExecutionRow(
                        resultSet.getString("current_attempt_id"),
                        resultSet.getLong("attempt_epoch"),
                        instant(resultSet.getTimestamp("lease_expires_at")),
                        instant(resultSet.getTimestamp("database_now")),
                        TurnStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("terminal_code"),
                        resultSet.getString("terminal_payload_type"),
                        resultSet.getString("terminal_payload_ref"),
                        resultSet.getObject("terminal_payload_schema_version", Integer.class),
                        resultSet.getString("terminal_payload_json"),
                        resultSet.getTimestamp("updated_at").toInstant()),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String assistantClientMessageId(TurnKey key) {
        return "assistant_" + digest(key.ownerKey(), key.canonicalConversationId(), key.turnId());
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '|');
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ExecutionRow(
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
