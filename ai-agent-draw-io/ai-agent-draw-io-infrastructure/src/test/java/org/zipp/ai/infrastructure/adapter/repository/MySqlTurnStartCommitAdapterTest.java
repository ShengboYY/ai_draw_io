package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.MigrationStateSnapshot;
import org.zipp.ai.application.turn.NoMemoryWrite;
import org.zipp.ai.application.turn.SelectedTurnEngine;
import org.zipp.ai.application.turn.TerminalOutcomeDecoder;
import org.zipp.ai.application.turn.TurnEngineAssignment;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStartCommand;
import org.zipp.ai.application.turn.TurnStartOutcome;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.VersionedRequestFingerprint;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MySqlTurnStartCommitAdapterTest {

    @Test
    void existingTerminalWithSupportedSchemaReplaysThroughStartBoundary() {
        JdbcStub jdbc = new JdbcStub(terminalExecutionRow(1));

        TurnStartOutcome.TerminalReplay replay = assertInstanceOf(
                TurnStartOutcome.TerminalReplay.class,
                new MySqlTurnStartCommitAdapter(jdbc.proxy()).start(command()));

        assertEquals(TurnStatus.COMPLETED, replay.outcome().status());
        assertEquals("COMPLETED", replay.outcome().terminalCode());
    }

    @Test
    void existingTerminalWithUnknownSchemaReturnsTypedUnavailable() {
        JdbcStub jdbc = new JdbcStub(terminalExecutionRow(2));

        TurnStartOutcome.TerminalUnavailable unavailable = assertInstanceOf(
                TurnStartOutcome.TerminalUnavailable.class,
                new MySqlTurnStartCommitAdapter(jdbc.proxy()).start(command()));

        // An undecodable terminal must not fall through to a second claim or any write.
        assertEquals(TerminalOutcomeDecoder.UNAVAILABLE_CODE, unavailable.code());
        assertEquals(List.of(), jdbc.updates);
    }

    private static TurnStartCommand command() {
        TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
        return new TurnStartCommand(
                key,
                "diagram-1",
                new TurnEngineAssignment(
                        key,
                        "diagram-1",
                        new VersionedRequestFingerprint(1, "fingerprint"),
                        SelectedTurnEngine.V2,
                        new MigrationStateSnapshot(
                                3, TurnEngineMode.ALL_V2, Instant.parse("2026-07-26T00:00:00Z")),
                        new ExecutionPolicySnapshot(1, TurnEngineMode.ALL_V2, "{}", "policy-hash"),
                        new NoMemoryWrite()),
                "draw a box",
                "client-1",
                List.of(),
                "input-digest");
    }

    private static Map<String, Object> terminalExecutionRow(int schemaVersion) {
        return values(
                "current_attempt_id", "attempt-1",
                "attempt_epoch", 1L,
                "lease_ttl_ms", 30_000L,
                "lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:30Z")),
                "database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")),
                "request_message_id", 42L,
                "request_fingerprint_schema_version", 1,
                "request_fingerprint", "fingerprint",
                "execution_policy_schema_version", 1,
                "execution_policy_snapshot_json", "{}",
                "execution_policy_hash", "policy-hash",
                "context_message_high_water", 1L,
                "status", "COMPLETED",
                "terminal_code", "COMPLETED",
                "terminal_payload_type", "plain",
                "terminal_payload_ref", "payload-1",
                "terminal_payload_schema_version", schemaVersion,
                "terminal_payload_json", "{}",
                "updated_at", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")));
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
        private final List<String> updates = new java.util.ArrayList<>();

        private JdbcStub(Map<String, Object> row) {
            this.row = row;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("queryForObject".equals(method.getName())) {
                    return 1;
                }
                if ("query".equals(method.getName())) {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    return List.of(map(mapper, row));
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
