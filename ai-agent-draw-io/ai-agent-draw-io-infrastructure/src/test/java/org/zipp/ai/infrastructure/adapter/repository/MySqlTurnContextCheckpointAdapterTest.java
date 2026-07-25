package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextReadSetLoadOutcome;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ProposedContextReadSet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlTurnContextCheckpointAdapterTest {

    @Test
    void expiredAttemptCannotLoadOrPinAContextReadSet() {
        FencedAttempt attempt = attempt();
        JdbcStub jdbc = new JdbcStub(expiredExecutionRow());
        MySqlTurnContextCheckpointAdapter adapter = new MySqlTurnContextCheckpointAdapter(jdbc.proxy());

        assertInstanceOf(ContextReadSetLoadOutcome.FenceLost.class, adapter.loadPinned(attempt));
        assertInstanceOf(
                org.zipp.ai.application.turn.context.ContextReadSetOutcome.FenceLost.class,
                adapter.pinFirst(attempt, new ProposedContextReadSet(readSet())));
        assertTrue(jdbc.lastQuery.contains("FOR UPDATE"));
        assertTrue(jdbc.lastUpdate.contains("lease_expires_at > CURRENT_TIMESTAMP(3)"));
    }

    private static FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1,
                        Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private static ContextReadSet readSet() {
        return ContextReadSet.create(
                1,
                2,
                ContextSlicePin.absent(ContextSlice.SUMMARY, "SUMMARY_NOT_AVAILABLE"),
                ContextSlicePin.absent(ContextSlice.MEMBERSHIP, "MEMBERSHIP_NOT_AVAILABLE"),
                ContextSlicePin.absent(ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE"),
                ContextSlicePin.absent(ContextSlice.MEMORY, "MEMORY_NOT_AVAILABLE"));
    }

    private static Map<String, Object> expiredExecutionRow() {
        return values(
                "current_attempt_id", "attempt-1",
                "attempt_epoch", 1L,
                "context_message_high_water", 2L,
                "turn_input_binding_digest", "input-digest",
                "context_read_set_schema_version", 0,
                "context_read_set_json", null,
                "context_read_set_digest", null,
                "lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:30Z")),
                "database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:31Z")),
                "status", "RUNNING");
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
        private String lastQuery = "";
        private String lastUpdate = "";

        private JdbcStub(Map<String, Object> row) {
            this.row = row;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("update".equals(method.getName())) {
                    // Force the CAS loser path so an expired row must remain fenced.
                    lastUpdate = (String) args[0];
                    return 0;
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

        private static <T> T map(RowMapper<T> mapper, Map<String, Object> values) {
            try {
                return mapper.mapRow(resultSet(values), 0);
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
                if ("getInt".equals(method.getName())) {
                    Object value = values.get(args[0]);
                    return value == null ? 0 : ((Number) value).intValue();
                }
                if ("getLong".equals(method.getName())) {
                    Object value = values.get(args[0]);
                    return value == null ? 0L : ((Number) value).longValue();
                }
                if ("getTimestamp".equals(method.getName())) {
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
