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
import org.zipp.ai.application.turn.context.ContextPinState;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextReadSetCommitPort;
import org.zipp.ai.application.turn.context.ContextReadSetLoadOutcome;
import org.zipp.ai.application.turn.context.ContextReadSetOutcome;
import org.zipp.ai.application.turn.context.ContextReadSetQueryPort;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ProposedContextReadSet;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Fenced first-writer persistence for the immutable Context read-set. */
@Repository
public class MySqlTurnContextCheckpointAdapter implements
        ContextReadSetQueryPort,
        ContextReadSetCommitPort {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    private static final String SELECT = """
            SELECT current_attempt_id, attempt_epoch, context_message_high_water,
                   turn_input_binding_digest,
                   context_read_set_schema_version, context_read_set_json, context_read_set_digest,
                   lease_expires_at, CURRENT_TIMESTAMP(3) AS database_now,
                   status, terminal_code, terminal_payload_ref, updated_at
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            """;
    /** Current read after a failed pin CAS prevents REPEATABLE READ from hiding the winner. */
    private static final String SELECT_FOR_UPDATE = SELECT + "FOR UPDATE\n";

    private static final String PIN_CONTEXT = """
            UPDATE turn_execution
            SET context_read_set_schema_version = ?, context_read_set_json = ?,
                context_read_set_digest = ?, context_read_set_pinned_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
              AND context_read_set_json IS NULL AND context_read_set_digest IS NULL
            """;

    private final JdbcOperations jdbc;

    public MySqlTurnContextCheckpointAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public ContextReadSetLoadOutcome loadPinned(FencedAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        return loadPinned(attempt, SELECT);
    }

    private ContextReadSetLoadOutcome loadPinned(FencedAttempt attempt, String sql) {
        ExecutionRow row = find(attempt.key(), sql);
        if (row == null) {
            return new ContextReadSetLoadOutcome.Unavailable(
                    notFound(attempt.key()), TurnFailureCode.TERMINAL_UNAVAILABLE, RETRY_AFTER);
        }
        if (!row.currentFor(attempt)) {
            return new ContextReadSetLoadOutcome.FenceLost(new TurnStatusRef(attempt.key()));
        }
        if (row.contextJson == null || row.contextDigest == null) {
            return new ContextReadSetLoadOutcome.Missing();
        }
        try {
            ContextReadSet value = decodeContext(row.contextJson);
            if (value.schemaVersion() != row.contextSchemaVersion
                    || value.messageHighWater() != row.contextHighWater
                    || !value.digest().equals(row.contextDigest)) {
                return unavailable(attempt.key());
            }
            if (revoked(value)) {
                return new ContextReadSetLoadOutcome.Revoked("CONTEXT_PIN_REVOKED");
            }
            return new ContextReadSetLoadOutcome.Found(value);
        } catch (RuntimeException exception) {
            return unavailable(attempt.key());
        }
    }

    @Override
    @Transactional
    public ContextReadSetOutcome pinFirst(
            FencedAttempt attempt,
            ProposedContextReadSet proposal
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(proposal, "proposal");
        ContextReadSet value = proposal.value();
        if (value.messageHighWater() != attempt.contextMessageHighWater()) {
            return new ContextReadSetOutcome.Unavailable(
                    status(attempt.key()), TurnFailureCode.STALE_ATTEMPT, Duration.ZERO);
        }
        int updated = jdbc.update(
                PIN_CONTEXT,
                value.schemaVersion(),
                JSON.toJSONString(value),
                value.digest(),
                attempt.key().ownerKey(),
                attempt.key().canonicalConversationId(),
                attempt.key().turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (updated == 1) {
            return new ContextReadSetOutcome.Pinned(value);
        }
        return afterContextCas(attempt);
    }

    private ContextReadSetOutcome afterContextCas(FencedAttempt attempt) {
        ContextReadSetLoadOutcome loaded = loadPinned(attempt, SELECT_FOR_UPDATE);
        if (loaded instanceof ContextReadSetLoadOutcome.Found found) {
            return new ContextReadSetOutcome.Pinned(found.value());
        }
        if (loaded instanceof ContextReadSetLoadOutcome.Missing) {
            return new ContextReadSetOutcome.Retry();
        }
        if (loaded instanceof ContextReadSetLoadOutcome.FenceLost lost) {
            return new ContextReadSetOutcome.FenceLost(lost.status());
        }
        if (loaded instanceof ContextReadSetLoadOutcome.Revoked revoked) {
            return new ContextReadSetOutcome.Revoked(revoked.reason());
        }
        ContextReadSetLoadOutcome.Unavailable unavailable =
                (ContextReadSetLoadOutcome.Unavailable) loaded;
        return new ContextReadSetOutcome.Unavailable(
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
                resultSet.getLong("context_message_high_water"),
                resultSet.getString("turn_input_binding_digest"),
                resultSet.getInt("context_read_set_schema_version"),
                resultSet.getString("context_read_set_json"),
                resultSet.getString("context_read_set_digest"),
                instant(resultSet.getTimestamp("lease_expires_at")),
                instant(resultSet.getTimestamp("database_now")),
                TurnStatus.valueOf(resultSet.getString("status")));
    }

    private ContextReadSet decodeContext(String json) {
        JSONObject root = JSONObject.parseObject(json);
        return new ContextReadSet(
                root.getIntValue("schemaVersion"),
                root.getLongValue("messageHighWater"),
                pin(root.getJSONObject("summary")),
                pin(root.getJSONObject("membership")),
                pin(root.getJSONObject("profile")),
                pin(root.getJSONObject("memory")),
                root.getString("digest"));
    }

    private ContextSlicePin pin(JSONObject json) {
        if (json == null) {
            throw new IllegalArgumentException("context pin is missing");
        }
        return new ContextSlicePin(
                ContextSlice.valueOf(json.getString("slice")),
                ContextPinState.valueOf(json.getString("state")),
                json.getString("reference"),
                json.getLongValue("version"),
                json.getString("contentDigest"));
    }

    private boolean revoked(ContextReadSet value) {
        return List.of(value.summary(), value.membership(), value.profile(), value.memory())
                .stream().anyMatch(pin -> pin.state() == ContextPinState.REVOKED);
    }

    private ContextReadSetLoadOutcome.Unavailable unavailable(TurnKey key) {
        return new ContextReadSetLoadOutcome.Unavailable(
                new TurnStatusRef(key), TurnFailureCode.TERMINAL_UNAVAILABLE, RETRY_AFTER);
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
            long contextHighWater,
            String inputBindingDigest,
            int contextSchemaVersion,
            String contextJson,
            String contextDigest,
            java.time.Instant leaseExpiresAt,
            java.time.Instant databaseNow,
            TurnStatus turnStatus
    ) {
        boolean currentFor(FencedAttempt attempt) {
            return turnStatus == TurnStatus.RUNNING
                    && Objects.equals(attemptId, attempt.attemptId())
                    && attemptEpoch == attempt.attemptEpoch()
                    && leaseExpiresAt != null
                    && databaseNow != null
                    && leaseExpiresAt.isAfter(databaseNow);
        }

    }

    private static java.time.Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
