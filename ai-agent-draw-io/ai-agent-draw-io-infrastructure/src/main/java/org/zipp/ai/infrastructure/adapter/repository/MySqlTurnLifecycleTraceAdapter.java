package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.TurnLifecycleTraceEvent;
import org.zipp.ai.application.turn.TurnLifecycleTracePort;

import java.sql.Timestamp;
import java.util.Objects;

/** Persists only the redacted lifecycle fields defined by the application trace contract. */
@Repository
public class MySqlTurnLifecycleTraceAdapter implements TurnLifecycleTracePort {

    private static final String INSERT = """
            INSERT INTO turn_lifecycle_trace (
                owner_key, conversation_id, turn_id, event_type,
                attempt_id, attempt_epoch, policy_hash, input_binding_digest,
                decision_digest, outcome_code, outcome_status, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcOperations jdbc;

    public MySqlTurnLifecycleTraceAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(TurnLifecycleTraceEvent event) {
        Objects.requireNonNull(event, "event");
        jdbc.update(
                INSERT,
                event.key().ownerKey(),
                event.key().canonicalConversationId(),
                event.key().turnId(),
                event.type().name(),
                event.attemptId(),
                event.attemptEpoch(),
                event.policyHash(),
                event.inputBindingDigest(),
                event.decisionDigest(),
                event.outcomeCode(),
                event.outcomeStatus() == null ? null : event.outcomeStatus().name(),
                Timestamp.from(event.occurredAt()));
    }
}
