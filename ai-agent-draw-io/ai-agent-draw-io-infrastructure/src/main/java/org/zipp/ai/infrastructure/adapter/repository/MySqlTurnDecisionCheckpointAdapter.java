package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.checkpoint.ProposedTurnDecisionCheckpoint;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointCommitPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointLoadOutcome;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointOutcome;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointQueryPort;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Fenced first-writer persistence for the immutable turn decision checkpoint. */
@Repository
public class MySqlTurnDecisionCheckpointAdapter implements
        TurnDecisionCheckpointQueryPort,
        TurnDecisionCheckpointCommitPort {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    private static final String SELECT = """
            SELECT current_attempt_id, attempt_epoch, context_read_set_digest,
                   turn_input_binding_digest, plan_payload_schema_version,
                   plan_payload_json, plan_payload_digest,
                   status, terminal_code, terminal_payload_ref, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
    private static final String SELECT_FOR_UPDATE = SELECT + "FOR UPDATE\n";

    private static final String PIN = """
            UPDATE turn_execution
            SET plan_payload_schema_version = ?, plan_payload_json = ?,
                plan_payload_digest = ?, plan_pinned_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND context_read_set_digest = ? AND turn_input_binding_digest = ?
              AND plan_payload_json IS NULL AND plan_payload_digest IS NULL
            """;

    private final JdbcOperations jdbc;

    public MySqlTurnDecisionCheckpointAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public TurnDecisionCheckpointLoadOutcome loadPinned(FencedAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        return loadPinned(attempt, SELECT);
    }

    private TurnDecisionCheckpointLoadOutcome loadPinned(FencedAttempt attempt, String sql) {
        ExecutionRow row = find(attempt.key(), sql);
        if (row == null) {
            return new TurnDecisionCheckpointLoadOutcome.Unavailable(
                    notFound(attempt.key()), TurnFailureCode.TERMINAL_UNAVAILABLE, RETRY_AFTER);
        }
        if (!row.currentFor(attempt)) {
            return new TurnDecisionCheckpointLoadOutcome.FenceLost(new TurnStatusRef(attempt.key()));
        }
        if (row.planJson == null || row.planDigest == null) {
            return new TurnDecisionCheckpointLoadOutcome.Missing();
        }
        try {
            TurnDecisionCheckpoint value = decode(row.planJson);
            if (value.schemaVersion() != row.planSchemaVersion
                    || !value.digest().equals(row.planDigest)
                    || !value.inputBindingDigest().equals(row.inputBindingDigest)
                    || row.contextDigest == null
                    || !value.contextReadSetDigest().equals(row.contextDigest)) {
                return unavailable(attempt.key());
            }
            return new TurnDecisionCheckpointLoadOutcome.Found(value);
        } catch (RuntimeException exception) {
            return unavailable(attempt.key());
        }
    }

    @Override
    @Transactional
    public TurnDecisionCheckpointOutcome pinFirst(
            FencedAttempt attempt,
            ProposedTurnDecisionCheckpoint proposal
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(proposal, "proposal");
        TurnDecisionCheckpoint value = proposal.value();
        if (!attempt.inputBindingDigest().equals(value.inputBindingDigest())) {
            return new TurnDecisionCheckpointOutcome.Unavailable(
                    status(attempt.key()), TurnFailureCode.STALE_ATTEMPT, Duration.ZERO);
        }
        int updated;
        try {
            // JSON is persisted in a native JSON column; reject malformed payloads before SQL.
            if (JSONObject.parseObject(value.decisionJson()) == null) {
                throw new IllegalArgumentException("decision JSON must be an object");
            }
            updated = jdbc.update(
                    PIN,
                    value.schemaVersion(),
                    value.decisionJson(),
                    value.digest(),
                    attempt.key().ownerKey(),
                    attempt.key().canonicalConversationId(),
                    attempt.key().turnId(),
                    attempt.attemptId(),
                    attempt.attemptEpoch(),
                    value.contextReadSetDigest(),
                    value.inputBindingDigest());
        } catch (RuntimeException exception) {
            return new TurnDecisionCheckpointOutcome.Unavailable(
                    status(attempt.key()), TurnFailureCode.TERMINAL_UNAVAILABLE, RETRY_AFTER);
        }
        if (updated == 1) {
            return new TurnDecisionCheckpointOutcome.Pinned(value);
        }
        return afterCas(attempt);
    }

    private TurnDecisionCheckpointOutcome afterCas(FencedAttempt attempt) {
        TurnDecisionCheckpointLoadOutcome loaded = loadPinned(attempt, SELECT_FOR_UPDATE);
        if (loaded instanceof TurnDecisionCheckpointLoadOutcome.Found found) {
            return new TurnDecisionCheckpointOutcome.Pinned(found.value());
        }
        if (loaded instanceof TurnDecisionCheckpointLoadOutcome.Missing) {
            return new TurnDecisionCheckpointOutcome.Retry();
        }
        if (loaded instanceof TurnDecisionCheckpointLoadOutcome.FenceLost lost) {
            return new TurnDecisionCheckpointOutcome.FenceLost(lost.status());
        }
        TurnDecisionCheckpointLoadOutcome.Unavailable unavailable =
                (TurnDecisionCheckpointLoadOutcome.Unavailable) loaded;
        return new TurnDecisionCheckpointOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
    }

    private ExecutionRow find(TurnKey key) {
        return find(key, SELECT);
    }

    private ExecutionRow find(TurnKey key, String sql) {
        List<ExecutionRow> rows = jdbc.query(
                sql,
                (resultSet, rowNum) -> row(resultSet),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ExecutionRow row(ResultSet resultSet) throws SQLException {
        return new ExecutionRow(
                resultSet.getString("current_attempt_id"),
                resultSet.getLong("attempt_epoch"),
                resultSet.getString("context_read_set_digest"),
                resultSet.getString("turn_input_binding_digest"),
                resultSet.getInt("plan_payload_schema_version"),
                resultSet.getString("plan_payload_json"),
                resultSet.getString("plan_payload_digest"),
                TurnStatus.valueOf(resultSet.getString("status")));
    }

    private TurnDecisionCheckpoint decode(String json) {
        JSONObject root = JSON.parseObject(json);
        return new TurnDecisionCheckpoint(
                root.getIntValue("schemaVersion"),
                root.getString("contextReadSetDigest"),
                root.getString("inputBindingDigest"),
                root.getString("decisionKind"),
                root.getString("decisionJson"),
                root.getString("digest"));
    }

    private TurnDecisionCheckpointLoadOutcome.Unavailable unavailable(TurnKey key) {
        return new TurnDecisionCheckpointLoadOutcome.Unavailable(
                status(key), TurnFailureCode.TERMINAL_UNAVAILABLE, RETRY_AFTER);
    }

    private TurnStatusRef status(TurnKey key) {
        return new TurnStatusRef(key);
    }

    private TurnStatusRef notFound(TurnKey key) {
        return new TurnStatusRef(key);
    }

    private record ExecutionRow(
            String attemptId,
            long attemptEpoch,
            String contextDigest,
            String inputBindingDigest,
            int planSchemaVersion,
            String planJson,
            String planDigest,
            TurnStatus turnStatus
    ) {
        boolean currentFor(FencedAttempt attempt) {
            return turnStatus == TurnStatus.RUNNING
                    && Objects.equals(attemptId, attempt.attemptId())
                    && attemptEpoch == attempt.attemptEpoch();
        }

    }
}
