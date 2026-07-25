package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.checkpoint.ProposedTurnDecisionCheckpoint;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointOutcome;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlTurnDecisionCheckpointAdapterTest {

    private static final String INPUT_DIGEST = "b".repeat(64);

    @Test
    void failedPinReloadsTheCurrentTerminalWinnerWithAForUpdateRead() {
        JdbcStub jdbc = new JdbcStub(terminalExecutionRow());
        TurnDecisionCheckpoint proposal = TurnDecisionCheckpoint.create(
                1, "a".repeat(64), INPUT_DIGEST, "PLAIN", "{}");

        TurnDecisionCheckpointOutcome outcome = new MySqlTurnDecisionCheckpointAdapter(jdbc.proxy()).pinFirst(
                attempt(), new ProposedTurnDecisionCheckpoint(proposal));

        assertInstanceOf(TurnDecisionCheckpointOutcome.FenceLost.class, outcome);
        assertTrue(jdbc.lastQuery.contains("FOR UPDATE"));
        assertTrue(jdbc.lastUpdate.contains("lease_expires_at > CURRENT_TIMESTAMP(3)"));
    }

    private static FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1,
                        java.time.Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                2,
                INPUT_DIGEST,
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private static Map<String, Object> terminalExecutionRow() {
        return values(
                "current_attempt_id", "attempt-1",
                "attempt_epoch", 1L,
                "context_read_set_digest", "a".repeat(64),
                "turn_input_binding_digest", INPUT_DIGEST,
                "plan_payload_schema_version", 0,
                "plan_payload_json", null,
                "plan_payload_digest", null,
                "lease_expires_at", null,
                "database_now", null,
                "status", "CANCELLED",
                "terminal_code", "CANCELLED_BY_USER",
                "terminal_payload_ref", null,
                "updated_at", null);
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
                    // Force the CAS loser path so the adapter must read the winner.
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

        private static <T> T map(RowMapper<T> mapper, Map<String, Object> row) {
            try {
                return mapper.mapRow(resultSet(row), 0);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        private static java.sql.ResultSet resultSet(Map<String, Object> values) {
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
                return defaultValue(method.getReturnType());
            };
            return (java.sql.ResultSet) Proxy.newProxyInstance(
                    java.sql.ResultSet.class.getClassLoader(), new Class<?>[]{java.sql.ResultSet.class}, handler);
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
