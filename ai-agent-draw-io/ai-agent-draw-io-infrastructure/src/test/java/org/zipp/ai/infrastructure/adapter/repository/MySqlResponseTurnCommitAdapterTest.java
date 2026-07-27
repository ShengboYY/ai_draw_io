package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.ResponseTurnCommit;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlResponseTurnCommitAdapterTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void responseCommitWritesAssistantMessageAndTerminalTogether() {
        FencedAttempt attempt = attempt();
        StubJdbc jdbc = new StubJdbc(
                executionRow(attempt, "RUNNING", null, null),
                values("version", 4L));

        FencedCommitOutcome.Committed committed = assertInstanceOf(
                FencedCommitOutcome.Committed.class,
                new MySqlResponseTurnCommitAdapter(jdbc.proxy()).commit(command(attempt)));

        assertEquals(TurnStatus.COMPLETED, committed.outcome().status());
        assertEquals("response", committed.outcome().terminalPayloadType());
        assertEquals(3, jdbc.updates.size());
        assertTrue(jdbc.updates.get(0).contains("UPDATE conversation"));
        assertTrue(jdbc.updates.get(1).contains("INSERT INTO diagram_conversation_message"));
        assertTrue(jdbc.updates.get(2).contains("terminal_payload_type = 'response'"));
    }

    @Test
    void terminalReplayDoesNotWriteAnotherAssistantMessage() {
        FencedAttempt attempt = attempt();
        StubJdbc jdbc = new StubJdbc(
                executionRow(attempt, "COMPLETED", "COMPLETED", "response"),
                null);

        FencedCommitOutcome outcome = new MySqlResponseTurnCommitAdapter(jdbc.proxy()).commit(command(attempt));

        assertInstanceOf(FencedCommitOutcome.AlreadyTerminal.class, outcome);
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void staleAttemptCannotWriteResponseMessage() {
        FencedAttempt attempt = attempt();
        StubJdbc jdbc = new StubJdbc(
                executionRow(attempt, "RUNNING", null, null, "other-attempt", 2L),
                values("version", 4L));

        FencedCommitOutcome.FenceLost lost = assertInstanceOf(
                FencedCommitOutcome.FenceLost.class,
                new MySqlResponseTurnCommitAdapter(jdbc.proxy()).commit(command(attempt)));

        assertEquals("other-attempt", lost.status().attemptId());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void expiredAttemptCannotWriteResponseMessage() {
        FencedAttempt attempt = attempt();
        Map<String, Object> execution = executionRow(attempt, "RUNNING", null, null);
        execution.put("lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:00Z")));
        execution.put("database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")));
        StubJdbc jdbc = new StubJdbc(execution, values("version", 4L));

        FencedCommitOutcome outcome = new MySqlResponseTurnCommitAdapter(jdbc.proxy()).commit(command(attempt));

        assertInstanceOf(FencedCommitOutcome.FenceLost.class, outcome);
        assertEquals(List.of(), jdbc.updates);
    }

    private ResponseTurnCommit command(FencedAttempt attempt) {
        return new ResponseTurnCommit(attempt, "diagram-1", "reviewed", "payload-response");
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, UPDATED_AT, 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private Map<String, Object> executionRow(
            FencedAttempt attempt,
            String status,
            String terminalCode,
            String terminalPayloadType
    ) {
        return executionRow(attempt, status, terminalCode, terminalPayloadType, attempt.attemptId(), attempt.attemptEpoch());
    }

    private Map<String, Object> executionRow(
            FencedAttempt attempt,
            String status,
            String terminalCode,
            String terminalPayloadType,
            String attemptId,
            long attemptEpoch
    ) {
        return values(
                "current_attempt_id", attemptId,
                "attempt_epoch", attemptEpoch,
                "lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:30Z")),
                "database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")),
                "status", status,
                "terminal_code", terminalCode,
                "terminal_payload_type", terminalPayloadType,
                "terminal_payload_ref", "payload-response",
                "terminal_payload_schema_version", 1,
                "terminal_payload_json", "{}",
                "updated_at", Timestamp.from(UPDATED_AT));
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    private static class StubJdbc {
        private final Map<String, Object> execution;
        private final Map<String, Object> conversation;
        private final List<String> updates = new ArrayList<>();

        private StubJdbc(Map<String, Object> execution, Map<String, Object> conversation) {
            this.execution = execution;
            this.conversation = conversation;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("query".equals(method.getName())) {
                    String sql = (String) args[0];
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    Map<String, Object> row = sql.contains("FROM turn_execution") ? execution : conversation;
                    return row == null ? List.of() : List.of(map(mapper, row));
                }
                if ("queryForObject".equals(method.getName())) {
                    return 42L;
                }
                if ("update".equals(method.getName())) {
                    updates.add((String) args[0]);
                    return 1;
                }
                return defaultValue(method.getReturnType());
            };
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(), new Class<?>[]{JdbcOperations.class}, handler);
        }

        private static <T> T map(RowMapper<T> mapper, Map<String, Object> values) {
            try {
                return mapper.mapRow(resultSet(values), 0);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        private static ResultSet resultSet(Map<String, Object> values) {
            InvocationHandler handler = new InvocationHandler() {
                private boolean wasNull;

                @Override
                public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                    String name = method.getName();
                    if ("getString".equals(name)) {
                        Object value = values.get(args[0]);
                        wasNull = value == null;
                        return value == null ? null : value.toString();
                    }
                    if ("getLong".equals(name)) {
                        Object value = values.get(args[0]);
                        wasNull = value == null;
                        return value == null ? 0L : ((Number) value).longValue();
                    }
                    if ("getTimestamp".equals(name)) {
                        Object value = values.get(args[0]);
                        wasNull = value == null;
                        return value;
                    }
                    if ("getObject".equals(name)) {
                        Object value = values.get(args[0]);
                        wasNull = value == null;
                        return value;
                    }
                    if ("wasNull".equals(name)) {
                        return wasNull;
                    }
                    return defaultValue(method.getReturnType());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, handler);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
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
