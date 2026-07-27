package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainTurnCommit;
import org.zipp.ai.application.turn.PlainTurnCommitPort;
import org.zipp.ai.application.turn.TerminalOutcomeDecoder;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.domain.agent.service.canvas.CanvasXmlContentHasher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Atomic source-free commit: Canvas CAS, assistant message, and terminal outcome share one transaction. */
@Repository
public class MySqlPlainTurnCommitAdapter implements PlainTurnCommitPort {

    private static final String LOCK_EXECUTION = """
            SELECT current_attempt_id, attempt_epoch, lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now, status, terminal_code,
                   terminal_payload_type, terminal_payload_ref,
                   terminal_payload_schema_version, terminal_payload_json,
                   response_message_id, updated_at
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
    private static final String LOCK_CANVAS = """
            SELECT c.version, c.content_hash, c.summary, c.analysis_json
            FROM diagram d
            LEFT JOIN diagram_canvas_state c ON c.diagram_id = d.id AND c.user_id = d.user_id
            WHERE d.id = ? AND d.user_id = ? AND d.deleted = 0
            FOR UPDATE
            """;
    private static final String INSERT_CANVAS = """
            INSERT INTO diagram_canvas_state
                (diagram_id, user_id, current_xml, content_hash, summary, analysis_json, version)
            VALUES (?, ?, ?, ?, ?, ?, 1)
            """;
    private static final String UPDATE_CANVAS = """
            UPDATE diagram_canvas_state
            SET current_xml = ?, content_hash = ?, summary = ?, analysis_json = ?,
                version = version + 1,
                updated_at = UTC_TIMESTAMP(3)
            WHERE diagram_id = ? AND user_id = ? AND version = ?
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
            SET response_message_id = ?, canvas_version_before = ?, canvas_version_after = ?,
                status = 'COMPLETED', terminal_code = 'COMPLETED',
                terminal_payload_type = 'plain', terminal_payload_schema_version = 1,
                terminal_payload_ref = ?, terminal_payload_json = ?,
                completed_at = UTC_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
            """;

    private final JdbcOperations jdbc;
    private final MySqlCompletedTurnMemoryProposalWriter memoryProposals;
    private final CanvasXmlContentHasher canvasHasher = new CanvasXmlContentHasher();
    private final TerminalOutcomeDecoder terminalDecoder = new TerminalOutcomeDecoder();

    public MySqlPlainTurnCommitAdapter(JdbcOperations jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public MySqlPlainTurnCommitAdapter(
            JdbcOperations jdbc,
            MySqlCompletedTurnMemoryProposalWriter memoryProposals
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.memoryProposals = memoryProposals;
    }

    @Override
    @Transactional
    public FencedCommitOutcome commit(PlainTurnCommit command) {
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
        CanvasRow canvas = lockCanvas(attempt.key().ownerKey(), command.diagramId());
        if (canvas == null) {
            return new FencedCommitOutcome.Rejected("DIAGRAM_NOT_ACTIVE");
        }
        CanvasWriteOutcome canvasWrite = validateAndWriteCanvas(command, canvas);
        if (canvasWrite instanceof CanvasWriteOutcome.Rejected rejected) {
            return new FencedCommitOutcome.Rejected(rejected.code());
        }
        long canvasVersionBefore = ((CanvasWriteOutcome.Written) canvasWrite).versionBefore();
        long assistantSequence = conversationVersion + 1;
        jdbc.update(ADVANCE_CONVERSATION,
                attempt.key().canonicalConversationId(),
                attempt.key().ownerKey(),
                command.diagramId());
        long responseMessageId = insertAssistantMessage(command, assistantSequence);
        String terminalPayloadJson = JSON.toJSONString(Map.of(
                "responseMessageId", responseMessageId,
                "canvasVersionBefore", canvasVersionBefore,
                "canvasVersionAfter", canvasVersionBefore + 1,
                "payloadRef", command.payloadRef()));
        int updated = jdbc.update(
                COMMIT_EXECUTION,
                responseMessageId,
                canvasVersionBefore,
                canvasVersionBefore + 1,
                command.payloadRef(),
                terminalPayloadJson,
                attempt.key().ownerKey(),
                attempt.key().canonicalConversationId(),
                attempt.key().turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (updated != 1) {
            throw new IllegalStateException("PLAIN_COMMIT_FENCE_NOT_APPLIED");
        }
        if (memoryProposals != null) {
            memoryProposals.write(attempt, command.diagramId());
        }
        return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                TurnStatus.COMPLETED,
                "COMPLETED",
                "plain",
                command.payloadRef(),
                terminalPayloadJson));
    }

    private CanvasWriteOutcome validateAndWriteCanvas(PlainTurnCommit command, CanvasRow canvas) {
        CanvasContextMetadata metadata = CanvasContextMetadata.fromXml(command.canvasXml());
        // A drawing commit must contain at least one real cell. This protects every edit command,
        // including future ones, from replacing a valid canvas with an empty model response.
        if (!metadata.hasElements()) {
            return new CanvasWriteOutcome.Rejected("CANVAS_EMPTY_CANDIDATE");
        }
        boolean hasCanvas = canvas.version() != null;
        if (command.expectedCanvasVersion() == 0) {
            if (hasCanvas) {
                return new CanvasWriteOutcome.Rejected("CANVAS_VERSION_CONFLICT");
            }
            if (command.action() != PlainDrawAction.CREATE) {
                return new CanvasWriteOutcome.Rejected("CANVAS_REQUIRED");
            }
            jdbc.update(INSERT_CANVAS,
                    command.diagramId(),
                    command.attempt().key().ownerKey(),
                    command.canvasXml(),
                    canvasHasher.hash(command.canvasXml()),
                    metadata.summary(),
                    metadata.analysisJson());
            return new CanvasWriteOutcome.Written(0);
        }
        if (!hasCanvas || canvas.version() != command.expectedCanvasVersion()) {
            return new CanvasWriteOutcome.Rejected("CANVAS_VERSION_CONFLICT");
        }
        String actualDigest = canvasDigest(command.diagramId(), canvas);
        if (!actualDigest.equals(command.expectedCanvasContextDigest())) {
            return new CanvasWriteOutcome.Rejected("CANVAS_CONTEXT_CONFLICT");
        }
        int updated = jdbc.update(
                UPDATE_CANVAS,
                command.canvasXml(),
                canvasHasher.hash(command.canvasXml()),
                metadata.summary(),
                metadata.analysisJson(),
                command.diagramId(),
                command.attempt().key().ownerKey(),
                command.expectedCanvasVersion());
        if (updated != 1) {
            return new CanvasWriteOutcome.Rejected("CANVAS_VERSION_CONFLICT");
        }
        return new CanvasWriteOutcome.Written(command.expectedCanvasVersion());
    }

    private Long lockConversation(TurnKey key, String diagramId) {
        List<Long> rows = jdbc.query(
                LOCK_CONVERSATION,
                (resultSet, rowNum) -> resultSet.getLong("version"),
                key.canonicalConversationId(), key.ownerKey(), diagramId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CanvasRow lockCanvas(String ownerKey, String diagramId) {
        List<CanvasRow> rows = jdbc.query(
                LOCK_CANVAS,
                (resultSet, rowNum) -> new CanvasRow(
                        nullableLong(resultSet, "version"),
                        resultSet.getString("content_hash"),
                        resultSet.getString("summary"),
                        resultSet.getString("analysis_json")),
                diagramId, ownerKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insertAssistantMessage(PlainTurnCommit command, long sequence) {
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
            throw new IllegalStateException("PLAIN_RESPONSE_MESSAGE_NOT_CREATED");
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

    private String canvasDigest(String diagramId, CanvasRow canvas) {
        return digest("canvas", diagramId, String.valueOf(canvas.version()),
                canvas.contentHash(), canvas.summary(), canvas.analysisJson());
    }

    private String assistantClientMessageId(TurnKey key) {
        return "assistant_" + digest(key.ownerKey(), key.canonicalConversationId(), key.turnId());
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String normalized = value == null ? "" : value;
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
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

    private record CanvasRow(Long version, String contentHash, String summary, String analysisJson) {
    }

    private sealed interface CanvasWriteOutcome
            permits CanvasWriteOutcome.Written, CanvasWriteOutcome.Rejected {
        record Written(long versionBefore) implements CanvasWriteOutcome {
        }

        record Rejected(String code) implements CanvasWriteOutcome {
        }
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

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

}
