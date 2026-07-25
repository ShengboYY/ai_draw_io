package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.SelectedTurnEngine;
import org.zipp.ai.application.turn.TurnEngineAssignment;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStartCommand;
import org.zipp.ai.application.turn.TurnStartCommitPort;
import org.zipp.ai.application.turn.TurnStartOutcome;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TerminalOutcomeDecoder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Atomic V2 claim boundary for the first user message and its execution ledger row. */
@Repository
public class MySqlTurnStartCommitAdapter implements TurnStartCommitPort {
    private static final long LEASE_TTL_MILLIS = 30_000L;

    private static final String SELECT_ASSIGNMENT = """
            SELECT COUNT(*)
            FROM turn_engine_assignment
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND selected_engine = 'V2'
              AND request_fingerprint_schema_version = ?
              AND request_fingerprint = ?
            """;
    private static final String LOCK_CONVERSATION = """
            SELECT version
            FROM conversation
            WHERE id = ? AND owner_key = ? AND diagram_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """;
    private static final String SELECT_EXECUTION = """
            SELECT current_attempt_id, attempt_epoch, lease_ttl_ms, lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now,
                   request_message_id, request_fingerprint_schema_version, request_fingerprint,
                   execution_policy_schema_version, execution_policy_snapshot_json,
                   execution_policy_hash, context_message_high_water, status,
                   terminal_code, terminal_payload_type, terminal_payload_ref,
                   terminal_payload_schema_version, terminal_payload_json, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
    private static final String SELECT_EXECUTION_FOR_UPDATE = SELECT_EXECUTION + "FOR UPDATE\n";
    private static final String SELECT_CONVERSATION_FILE = """
            SELECT state
            FROM material_upload_session
            WHERE id = ? AND owner_key = ?
              AND target_scope_type = 'CONVERSATION'
              AND target_scope_key = ?
              AND (state <> 'CREATED' OR policy_expires_at > UTC_TIMESTAMP(3))
              AND state IN ('CREATED', 'OBJECT_VERSION_PINNED', 'PROCESSING', 'SUCCEEDED')
            FOR UPDATE
            """;
    private static final String INSERT_ATTACHMENT = """
            INSERT INTO conversation_message_attachment (
                owner_key, conversation_id, message_id, turn_id, attachment_order,
                conversation_file_ref, attachment_status_at_bind
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_MESSAGE = """
            INSERT INTO diagram_conversation_message (
                diagram_id, user_id, conversation_id, session_id, turn_id,
                client_message_id, role, content, message_sequence, message_status
            ) VALUES (?, ?, ?, NULL, ?, ?, 'user', ?, ?, 'COMMITTED')
            """;
    private static final String INSERT_EXECUTION = """
            INSERT INTO turn_execution (
                owner_key, conversation_id, diagram_id, turn_id,
                current_attempt_id, attempt_epoch, lease_policy_version,
                lease_ttl_ms, lease_expires_at, last_heartbeat_at,
                request_message_id, request_fingerprint_schema_version, request_fingerprint,
                migration_generation, migration_mode,
                execution_policy_schema_version, execution_policy_snapshot_json,
                execution_policy_hash, turn_input_binding_schema_version,
                turn_input_binding_digest, attachment_binding_digest,
                memory_write_schema_version, memory_write_declaration_json, memory_write_digest,
                context_message_high_water, status
            ) VALUES (?, ?, ?, ?, ?, 1, 'lease-v1', ?,
                      DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30000000 MICROSECOND),
                      CURRENT_TIMESTAMP(3), ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?,
                      ?, ?, ?, ?, 'RUNNING')
            """;
    private static final String ADVANCE_CONVERSATION = """
            UPDATE conversation
            SET version = version + 1
            WHERE id = ? AND owner_key = ?
            """;

    private final JdbcTemplate jdbc;
    private final TerminalOutcomeDecoder terminalDecoder = new TerminalOutcomeDecoder();

    public MySqlTurnStartCommitAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public TurnStartOutcome start(TurnStartCommand command) {
        Objects.requireNonNull(command, "command");
        if (!command.key().equals(command.assignment().key())) {
            return new TurnStartOutcome.Rejected("ASSIGNMENT_KEY_MISMATCH");
        }
        if (command.assignment().selectedEngine() != SelectedTurnEngine.V2) {
            return new TurnStartOutcome.Rejected("LEGACY_ASSIGNMENT");
        }
        TurnEngineAssignment assignment = command.assignment();
        if (jdbc.queryForObject(
                SELECT_ASSIGNMENT,
                Integer.class,
                command.key().ownerKey(),
                command.key().canonicalConversationId(),
                command.key().turnId(),
                assignment.fingerprint().schemaVersion(),
                assignment.fingerprint().digest()) != 1) {
            return new TurnStartOutcome.Rejected("ASSIGNMENT_NOT_CURRENT");
        }

        ExecutionRow existing = findExecution(command.key());
        if (existing != null) {
            return existing.startOutcome(command.key(), terminalDecoder);
        }

        Long conversationVersion = jdbc.queryForObject(
                LOCK_CONVERSATION,
                (rs, rowNum) -> rs.getLong("version"),
                command.key().canonicalConversationId(),
                command.key().ownerKey(),
                command.diagramId());
        if (conversationVersion == null) {
            return new TurnStartOutcome.Rejected("CONVERSATION_NOT_ACTIVE");
        }

        // The conversation row lock makes sequence allocation monotonic under concurrent starts.
        // MySQL's REPEATABLE READ can retain the pre-lock snapshot; use a locking current read
        // after the conversation lock so a concurrent committed claim is visible here.
        ExecutionRow afterLock = findExecutionForUpdate(command.key());
        if (afterLock != null) {
            return afterLock.startOutcome(command.key(), terminalDecoder);
        }
        AttachmentValidation attachmentValidation = validateAttachments(command);
        if (attachmentValidation.rejectionCode() != null) {
            return new TurnStartOutcome.Rejected(attachmentValidation.rejectionCode());
        }
        long messageSequence = conversationVersion + 1;
        jdbc.update(ADVANCE_CONVERSATION,
                command.key().canonicalConversationId(), command.key().ownerKey());
        long messageId = insertUserMessage(command, messageSequence);
        insertAttachments(command, messageId, attachmentValidation.bindings());
        String attemptId = "attempt_" + UUID.randomUUID();
        jdbc.update(
                INSERT_EXECUTION,
                command.key().ownerKey(),
                command.key().canonicalConversationId(),
                command.diagramId(),
                command.key().turnId(),
                attemptId,
                LEASE_TTL_MILLIS,
                messageId,
                assignment.fingerprint().schemaVersion(),
                assignment.fingerprint().digest(),
                assignment.migration().generation(),
                assignment.migration().mode().name(),
                assignment.policy().schemaVersion(),
                assignment.policy().snapshotJson(),
                assignment.policy().policyHash(),
                command.inputBindingDigest(),
                attachmentValidation.bindingDigest(),
                assignment.memoryWrite() instanceof org.zipp.ai.application.turn.NoMemoryWrite
                        ? 1 : ((org.zipp.ai.application.turn.RememberDecisionDeclaration) assignment.memoryWrite()).schemaVersion(),
                new MemoryWriteDeclarationJsonCodec().encode(assignment.memoryWrite()),
                assignment.memoryWrite() instanceof org.zipp.ai.application.turn.NoMemoryWrite
                        ? "NONE" : ((org.zipp.ai.application.turn.RememberDecisionDeclaration) assignment.memoryWrite()).digest().value(),
                messageSequence);

        ExecutionRow claimed = findExecution(command.key());
        if (claimed == null || claimed.attemptId == null || claimed.leaseExpiresAt == null) {
            throw new IllegalStateException("TURN_EXECUTION_NOT_CLAIMED");
        }
        return new TurnStartOutcome.Claimed(
                claimed.fencedAttempt(command.key(), assignment.policy(), command.inputBindingDigest()),
                messageId);
    }

    private record AttachmentBinding(String ref, String status) {
    }

    private record AttachmentValidation(List<AttachmentBinding> bindings, String bindingDigest,
                                        String rejectionCode) {
    }

    private AttachmentValidation validateAttachments(TurnStartCommand command) {
        StringBuilder canonical = new StringBuilder();
        List<AttachmentBinding> bindings = new java.util.ArrayList<>();
        for (OpaqueConversationFileRef attachment : command.currentTurnAttachments()) {
            List<String> states = jdbc.query(
                    SELECT_CONVERSATION_FILE,
                    (rs, rowNum) -> rs.getString("state"),
                    attachment.value(),
                    command.key().ownerKey(),
                    command.key().canonicalConversationId());
            if (states.isEmpty()) {
                return new AttachmentValidation(List.of(), null, "ATTACHMENT_NOT_FOUND_OR_NOT_OWNED");
            }
            bindings.add(new AttachmentBinding(attachment.value(), states.get(0)));
            appendCanonicalRef(canonical, attachment.value());
        }
        return new AttachmentValidation(bindings, sha256(canonical.toString()), null);
    }

    private void insertAttachments(TurnStartCommand command, long messageId,
                                   List<AttachmentBinding> bindings) {
        for (int index = 0; index < bindings.size(); index++) {
            AttachmentBinding binding = bindings.get(index);
            jdbc.update(
                    INSERT_ATTACHMENT,
                    command.key().ownerKey(),
                    command.key().canonicalConversationId(),
                    messageId,
                    command.key().turnId(),
                    index,
                    binding.ref(),
                    binding.status());
        }
    }

    private static void appendCanonicalRef(StringBuilder canonical, String value) {
        // Length framing prevents two ordered ref lists from sharing a digest by concatenation.
        canonical.append(value.length()).append(':').append(value).append('|');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private long insertUserMessage(TurnStartCommand command, long messageSequence) {
        jdbc.update(
                INSERT_MESSAGE,
                command.diagramId(),
                command.key().ownerKey(),
                command.key().canonicalConversationId(),
                command.key().turnId(),
                command.clientMessageId(),
                command.userMessage(),
                messageSequence);
        Long messageId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (messageId == null || messageId <= 0) {
            throw new IllegalStateException("TURN_MESSAGE_NOT_PERSISTED");
        }
        return messageId;
    }

    private ExecutionRow findExecution(TurnKey key) {
        return findExecution(key, SELECT_EXECUTION);
    }

    private ExecutionRow findExecutionForUpdate(TurnKey key) {
        return findExecution(key, SELECT_EXECUTION_FOR_UPDATE);
    }

    private ExecutionRow findExecution(TurnKey key, String sql) {
        List<ExecutionRow> rows = jdbc.query(
                sql,
                (rs, rowNum) -> execution(rs),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ExecutionRow execution(ResultSet rs) throws SQLException {
        return new ExecutionRow(
                rs.getString("current_attempt_id"),
                rs.getLong("attempt_epoch"),
                rs.getLong("lease_ttl_ms"),
                instant(rs.getTimestamp("lease_expires_at")),
                instant(rs.getTimestamp("database_now")),
                rs.getLong("request_message_id"),
                rs.getInt("request_fingerprint_schema_version"),
                rs.getString("request_fingerprint"),
                rs.getInt("execution_policy_schema_version"),
                rs.getString("execution_policy_snapshot_json"),
                rs.getString("execution_policy_hash"),
                rs.getLong("context_message_high_water"),
                TurnStatus.valueOf(rs.getString("status")),
                rs.getString("terminal_code"),
                rs.getString("terminal_payload_type"),
                rs.getString("terminal_payload_ref"),
                rs.getObject("terminal_payload_schema_version", Integer.class),
                rs.getString("terminal_payload_json"),
                instant(rs.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ExecutionRow(
            String attemptId,
            long attemptEpoch,
            long leaseTtlMillis,
            Instant leaseExpiresAt,
            Instant databaseNow,
            long requestMessageId,
            int fingerprintSchemaVersion,
            String fingerprint,
            int policySchemaVersion,
            String policyJson,
            String policyHash,
            long contextMessageHighWater,
            TurnStatus status,
            String terminalCode,
            String terminalPayloadType,
            String terminalPayloadRef,
            Integer terminalPayloadSchemaVersion,
            String terminalPayloadJson,
            Instant updatedAt
    ) {
        TurnStartOutcome startOutcome(TurnKey key, TerminalOutcomeDecoder decoder) {
            if (status == TurnStatus.RUNNING) {
                return new TurnStartOutcome.AlreadyRunning(statusView(key));
            }
            if (status == TurnStatus.ORPHANED_RETRYABLE) {
                return new TurnStartOutcome.Rejected("ORPHAN_RECONCILIATION_REQUIRED");
            }
            TerminalOutcomeDecoder.DecodeResult decoded = decode(decoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return new TurnStartOutcome.TerminalReplay(ready.outcome());
            }
            return new TurnStartOutcome.TerminalUnavailable(
                    statusView(key),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code());
        }

        FencedAttempt fencedAttempt(
                TurnKey key,
                org.zipp.ai.application.turn.ExecutionPolicySnapshot policy,
                String inputBindingDigest
        ) {
            return new FencedAttempt(
                    key,
                    AttemptLease.fromDatabaseClock(
                            attemptId, attemptEpoch, databaseNow, leaseExpiresAt, leaseTtlMillis),
                    contextMessageHighWater,
                    inputBindingDigest,
                    policy);
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
