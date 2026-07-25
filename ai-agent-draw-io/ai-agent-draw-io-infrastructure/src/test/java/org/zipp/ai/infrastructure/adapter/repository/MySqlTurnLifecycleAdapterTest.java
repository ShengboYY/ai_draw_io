package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptDeadlineReason;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.CancelTurnCommand;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlTurnLifecycleAdapterTest {

    @Test
    void explicitCancelReloadsAConcurrentTerminalWinnerWithAForUpdateRead() {
        TurnKey key = key();
        JdbcStub jdbc = new JdbcStub(terminalExecutionRow());

        CancelTurnOutcome outcome = new MySqlTurnLifecycleAdapter(jdbc.proxy()).cancel(
                new AuthenticatedActor("owner-1", "cohort-1"),
                new CancelTurnCommand(key, "user requested"));

        assertInstanceOf(CancelTurnOutcome.AlreadyTerminal.class, outcome);
        assertTrue(jdbc.lastQuery.contains("FOR UPDATE"));
    }

    @Test
    void deadlineCancelReloadsAConcurrentTerminalWinnerWithAForUpdateRead() {
        FencedAttempt attempt = attempt();
        JdbcStub jdbc = new JdbcStub(terminalExecutionRow());

        DeadlineCancelOutcome outcome = new MySqlTurnLifecycleAdapter(jdbc.proxy()).cancel(
                attempt, AttemptDeadlineReason.EXECUTION_DEADLINE);

        assertInstanceOf(DeadlineCancelOutcome.AlreadyTerminal.class, outcome);
        assertTrue(jdbc.lastQuery.contains("FOR UPDATE"));
    }

    @Test
    void successfulExplicitCancelReturnsTheDecodedPersistedOutcome() {
        JdbcStub jdbc = new JdbcStub(userCancelledExecutionRow(), 1);

        CancelTurnOutcome.Cancelled outcome = assertInstanceOf(
                CancelTurnOutcome.Cancelled.class,
                new MySqlTurnLifecycleAdapter(jdbc.proxy()).cancel(
                        new AuthenticatedActor("owner-1", "cohort-1"),
                        new CancelTurnCommand(key(), "user requested")));

        assertEquals(TurnStatus.CANCELLED, outcome.outcome().status());
        assertEquals("CANCELLED_BY_USER", outcome.outcome().terminalCode());
        assertTrue(jdbc.lastQuery.contains("FOR UPDATE"));
    }

    @Test
    void successfulDeadlineCancelReturnsTheDecodedPersistedOutcome() {
        JdbcStub jdbc = new JdbcStub(cancelledExecutionRow(), 1);

        DeadlineCancelOutcome.Cancelled outcome = assertInstanceOf(
                DeadlineCancelOutcome.Cancelled.class,
                new MySqlTurnLifecycleAdapter(jdbc.proxy()).cancel(
                        attempt(), AttemptDeadlineReason.EXECUTION_DEADLINE));

        assertEquals(TurnStatus.CANCELLED, outcome.outcome().status());
        assertEquals("EXECUTION_DEADLINE", outcome.outcome().terminalCode());
        assertTrue(jdbc.lastQuery.contains("FOR UPDATE"));
    }

    @Test
    void statusReturnsTypedUnavailableWhenTerminalSchemaCannotBeDecoded() {
        TurnKey key = key();
        Map<String, Object> row = terminalExecutionRow();
        row.put("terminal_payload_schema_version", 2);

        TurnStatusQueryOutcome outcome = new MySqlTurnLifecycleAdapter(new JdbcStub(row).proxy()).get(
                new AuthenticatedActor("owner-1", "cohort-1"),
                new org.zipp.ai.application.turn.TurnStatusQuery(key));

        assertInstanceOf(TurnStatusQueryOutcome.TerminalUnavailable.class, outcome);
    }

    @Test
    void executionStateReturnsAlreadyTerminalUsingTheCurrentForUpdateRead() {
        JdbcStub jdbc = new JdbcStub(terminalExecutionRow());

        TurnAttemptExecutionStatePort.StateOutcome outcome =
                new MySqlTurnLifecycleAdapter(jdbc.proxy()).check(attempt());

        assertInstanceOf(TurnAttemptExecutionStatePort.StateOutcome.AlreadyTerminal.class, outcome);
        assertTrue(jdbc.lastQuery.contains("FOR UPDATE"));
    }

    @Test
    void executionStateReturnsActiveForTheCurrentRunningAttempt() {
        JdbcStub jdbc = new JdbcStub(runningExecutionRow());

        TurnAttemptExecutionStatePort.StateOutcome outcome =
                new MySqlTurnLifecycleAdapter(jdbc.proxy()).check(attempt());

        assertInstanceOf(TurnAttemptExecutionStatePort.StateOutcome.Active.class, outcome);
    }

    @Test
    void executionStateReturnsFenceLostForAStaleAttempt() {
        JdbcStub jdbc = new JdbcStub(runningExecutionRow());

        TurnAttemptExecutionStatePort.StateOutcome outcome =
                new MySqlTurnLifecycleAdapter(jdbc.proxy()).check(attempt("attempt-2", 2));

        assertInstanceOf(TurnAttemptExecutionStatePort.StateOutcome.FenceLost.class, outcome);
    }

    @Test
    void executionStateReturnsFenceLostWhenTheCurrentLeaseHasExpired() {
        Map<String, Object> row = runningExecutionRow();
        row.put("lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:00Z")));

        TurnAttemptExecutionStatePort.StateOutcome outcome =
                new MySqlTurnLifecycleAdapter(new JdbcStub(row).proxy()).check(attempt());

        assertInstanceOf(TurnAttemptExecutionStatePort.StateOutcome.FenceLost.class, outcome);
    }

    private static TurnKey key() {
        return new TurnKey("owner-1", "conversation-1", "turn-1");
    }

    private static FencedAttempt attempt() {
        return attempt("attempt-1", 1);
    }

    private static FencedAttempt attempt(String attemptId, long attemptEpoch) {
        return new FencedAttempt(
                key(),
                new AttemptLease(attemptId, attemptEpoch,
                        Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private static Map<String, Object> runningExecutionRow() {
        return values(
                "current_attempt_id", "attempt-1",
                "attempt_epoch", 1L,
                "lease_ttl_ms", 30_000L,
                "lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:30Z")),
                "database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")),
                "status", "RUNNING",
                "terminal_code", null,
                "terminal_payload_type", null,
                "terminal_payload_ref", null,
                "terminal_payload_schema_version", null,
                "terminal_payload_json", null,
                "updated_at", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")));
    }

    private static Map<String, Object> terminalExecutionRow() {
        return values(
                "current_attempt_id", "attempt-2",
                "attempt_epoch", 2L,
                "lease_ttl_ms", 30_000L,
                "lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:30Z")),
                "database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")),
                "status", "COMPLETED",
                "terminal_code", "COMPLETED",
                "terminal_payload_type", "plain",
                "terminal_payload_ref", "payload-1",
                "terminal_payload_schema_version", 1,
                "terminal_payload_json", "{}",
                "updated_at", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")));
    }

    private static Map<String, Object> cancelledExecutionRow() {
        Map<String, Object> row = terminalExecutionRow();
        row.put("current_attempt_id", "attempt-1");
        row.put("attempt_epoch", 1L);
        row.put("status", "CANCELLED");
        row.put("terminal_code", "EXECUTION_DEADLINE");
        row.put("terminal_payload_type", "deadline");
        row.put("terminal_payload_ref", null);
        return row;
    }

    private static Map<String, Object> userCancelledExecutionRow() {
        Map<String, Object> row = cancelledExecutionRow();
        row.put("terminal_code", "CANCELLED_BY_USER");
        row.put("terminal_payload_type", "cancel");
        return row;
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    private static final class JdbcStub {
        private final Map<String, Object> row;
        private final int updateResult;
        private String lastQuery = "";

        private JdbcStub(Map<String, Object> row) {
            this(row, 0);
        }

        private JdbcStub(Map<String, Object> row, int updateResult) {
            this.row = row;
            this.updateResult = updateResult;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("update".equals(method.getName())) {
                    // The fixture selects either the CAS loser or winner path.
                    return updateResult;
                }
                if ("query".equals(method.getName())) {
                    lastQuery = (String) args[0];
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    return List.of(map(mapper, row));
                }
                return defaultValue(method.getReturnType());
            };
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(), new Class<?>[]{JdbcOperations.class}, handler);
        }

        private static <T> T map(RowMapper<T> mapper, Map<String, Object> row) {
            try {
                return mapper.mapRow(resultSet(row), 0);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        private static ResultSet resultSet(Map<String, Object> values) {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("getString".equals(method.getName())) {
                    Object value = values.get(args[0]);
                    return value == null ? null : value.toString();
                }
                if ("getLong".equals(method.getName())) {
                    Object value = values.get(args[0]);
                    return value == null ? 0L : ((Number) value).longValue();
                }
                if ("getTimestamp".equals(method.getName())) {
                    return values.get(args[0]);
                }
                if ("getObject".equals(method.getName())) {
                    return values.get(args[0]);
                }
                return defaultValue(method.getReturnType());
            };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, handler);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            return 0D;
        }
    }
}
