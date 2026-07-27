package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnKey;

import java.util.List;
import java.util.Objects;

/** Locks and verifies the current RUNNING attempt before an intermediate durable write. */
final class MySqlTurnAttemptFenceGuard {

    private static final String LOCK_ACTIVE_ATTEMPT = """
            SELECT current_attempt_id
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING'
              AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
            FOR UPDATE
            """;

    private final JdbcOperations jdbc;

    MySqlTurnAttemptFenceGuard(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    void lockActive(FencedAttempt attempt, String failureCode) {
        Objects.requireNonNull(attempt, "attempt");
        TurnKey key = attempt.key();
        List<String> active = jdbc.query(
                LOCK_ACTIVE_ATTEMPT,
                (resultSet, rowNum) -> resultSet.getString("current_attempt_id"),
                key.ownerKey(),
                key.canonicalConversationId(),
                key.turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch());
        if (active.size() != 1) {
            throw new IllegalStateException(failureCode);
        }
    }
}
