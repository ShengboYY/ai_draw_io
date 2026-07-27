package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Rebuilds takeover input from the message row and the first-claim declaration payload. */
@Repository
public class MySqlTurnAttemptInputRecoveryAdapter implements TurnAttemptInputRecoveryPort {

    private static final String SELECT_INPUT = """
            SELECT e.diagram_id, e.current_attempt_id, e.attempt_epoch, e.lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now,
                   e.turn_input_binding_schema_version, e.turn_input_binding_digest,
                   e.turn_input_binding_json, e.status,
                   e.request_message_id, m.turn_id, m.client_message_id, m.content
            FROM turn_execution e
            JOIN diagram_conversation_message m ON m.id = e.request_message_id
            WHERE e.owner_key = ? AND e.conversation_id = ? AND e.turn_id = ?
              AND m.user_id = e.owner_key
              AND m.conversation_id = e.conversation_id
              AND m.turn_id = e.turn_id
              AND m.role = 'user'
            FOR UPDATE
    """;
    private static final String SELECT_ATTACHMENT_REFS = """
            SELECT conversation_file_ref
            FROM conversation_message_attachment
            WHERE message_id = ?
            ORDER BY attachment_order
            FOR UPDATE
            """;

    private final JdbcOperations jdbc;
    private final TurnInputBindingJsonCodec codec = new TurnInputBindingJsonCodec();

    public MySqlTurnAttemptInputRecoveryAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public TurnAttemptInputRecoveryPort.RecoveryOutcome recover(FencedAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        List<InputRow> rows = jdbc.query(
                SELECT_INPUT,
                (rs, rowNum) -> inputRow(rs),
                attempt.key().ownerKey(),
                attempt.key().canonicalConversationId(),
                attempt.key().turnId());
        if (rows.isEmpty()) {
            return new TurnAttemptInputRecoveryPort.Unavailable("TURN_INPUT_NOT_FOUND");
        }
        InputRow row = rows.get(0);
        if (!attempt.key().turnId().equals(row.turnId)
                || !"RUNNING".equals(row.status) || !attempt.attemptId().equals(row.attemptId)
                || attempt.attemptEpoch() != row.attemptEpoch
                || row.leaseExpiresAt == null || row.databaseNow == null
                || !row.leaseExpiresAt.isAfter(row.databaseNow)) {
            return new TurnAttemptInputRecoveryPort.FenceLost();
        }
        if (row.bindingSchemaVersion == null
                || row.bindingSchemaVersion != TurnInputBindingJsonCodec.SCHEMA_VERSION
                || row.bindingDigest == null || row.bindingJson == null) {
            return new TurnAttemptInputRecoveryPort.Unavailable("TURN_INPUT_BINDING_UNAVAILABLE");
        }
        if (!row.bindingDigest.equals(attempt.inputBindingDigest())) {
            return new TurnAttemptInputRecoveryPort.Unavailable("TURN_INPUT_BINDING_DIGEST_MISMATCH");
        }

        try {
            var declarations = codec.decode(row.bindingJson);
            List<String> durableAttachmentRefs = jdbc.query(
                    SELECT_ATTACHMENT_REFS,
                    (rs, rowNum) -> rs.getString("conversation_file_ref"),
                    row.messageId);
            List<String> declaredAttachmentRefs = declarations.currentTurnAttachments().stream()
                    .map(ref -> ref.value())
                    .toList();
            if (!durableAttachmentRefs.equals(declaredAttachmentRefs)) {
                return new TurnAttemptInputRecoveryPort.Unavailable(
                        "TURN_ATTACHMENT_BINDING_MISMATCH");
            }
            UserTurnCommand command = new UserTurnCommand(
                    attempt.key().turnId(),
                    attempt.key().canonicalConversationId(),
                    row.diagramId,
                    row.clientMessageId,
                    row.content,
                    null,
                    declarations);
            if (!row.bindingDigest.equals(TurnInputBindingDigestCalculator.current(command))) {
                return new TurnAttemptInputRecoveryPort.Unavailable(
                        "TURN_INPUT_BINDING_DIGEST_MISMATCH");
            }
            return new TurnAttemptInputRecoveryPort.Recovered(command);
        } catch (RuntimeException exception) {
            return new TurnAttemptInputRecoveryPort.Unavailable("TURN_INPUT_BINDING_UNAVAILABLE");
        }
    }

    private InputRow inputRow(ResultSet rs) throws SQLException {
        Object schema = rs.getObject("turn_input_binding_schema_version");
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        Timestamp databaseNow = rs.getTimestamp("database_now");
        return new InputRow(
                rs.getString("diagram_id"),
                rs.getString("current_attempt_id"),
                rs.getLong("attempt_epoch"),
                leaseExpiresAt == null ? null : leaseExpiresAt.toInstant(),
                databaseNow == null ? null : databaseNow.toInstant(),
                schema == null ? null : rs.getInt("turn_input_binding_schema_version"),
                rs.getString("turn_input_binding_digest"),
                rs.getString("turn_input_binding_json"),
                rs.getLong("request_message_id"),
                rs.getString("turn_id"),
                rs.getString("client_message_id"),
                rs.getString("content"),
                rs.getString("status"));
    }

    private record InputRow(
            String diagramId,
            String attemptId,
            long attemptEpoch,
            Instant leaseExpiresAt,
            Instant databaseNow,
            Integer bindingSchemaVersion,
            String bindingDigest,
            String bindingJson,
            long messageId,
            String turnId,
            String clientMessageId,
            String content,
            String status
    ) {
    }
}
