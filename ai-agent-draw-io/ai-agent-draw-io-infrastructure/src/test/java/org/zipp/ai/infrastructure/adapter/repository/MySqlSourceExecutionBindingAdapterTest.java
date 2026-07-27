package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.SourceCommitBinding;
import org.zipp.ai.application.turn.SourceExecutionBindingOutcome;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;

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

class MySqlSourceExecutionBindingAdapterTest {

    @Test
    void firstPinWritesBindingThenLinksExecution() {
        StubJdbc jdbc = readyJdbc(null);

        SourceExecutionBindingOutcome outcome =
                new MySqlSourceExecutionBindingAdapter(jdbc.proxy())
                        .pin(attempt(), binding());

        assertInstanceOf(SourceExecutionBindingOutcome.Pinned.class, outcome);
        assertEquals(2, jdbc.updates.size());
        assertEquals(true, jdbc.updates.get(0)
                .contains("INSERT INTO turn_source_execution_binding"));
        assertEquals(true, jdbc.updates.get(1).contains("UPDATE turn_execution"));
    }

    @Test
    void samePinIsIdempotentAndConflictingPinIsRejectedWithoutWrites() {
        StubJdbc same = readyJdbc(bindingRow(binding()));
        assertInstanceOf(
                SourceExecutionBindingOutcome.AlreadyPinned.class,
                new MySqlSourceExecutionBindingAdapter(same.proxy())
                        .pin(attempt(), binding()));
        assertEquals(List.of(), same.updates);

        Map<String, Object> conflict = bindingRow(binding());
        conflict.put("source_snapshot_ref", "other-snapshot");
        StubJdbc conflicting = readyJdbc(conflict);
        SourceExecutionBindingOutcome.Rejected rejected = assertInstanceOf(
                SourceExecutionBindingOutcome.Rejected.class,
                new MySqlSourceExecutionBindingAdapter(conflicting.proxy())
                        .pin(attempt(), binding()));
        assertEquals("SOURCE_EXECUTION_BINDING_CONFLICT", rejected.code());
        assertEquals(List.of(), conflicting.updates);
    }

    @Test
    void expiredFenceCannotCreateABinding() {
        StubJdbc jdbc = readyJdbc(null);
        jdbc.execution.put("database_now", Timestamp.from(
                Instant.parse("2026-07-26T00:01:00Z")));

        assertInstanceOf(
                SourceExecutionBindingOutcome.FenceLost.class,
                new MySqlSourceExecutionBindingAdapter(jdbc.proxy())
                        .pin(attempt(), binding()));
        assertEquals(List.of(), jdbc.updates);
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease(
                        "attempt-1",
                        1,
                        Instant.parse("2026-07-26T00:00:30Z"),
                        30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(
                        1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private SourceCommitBinding binding() {
        return new SourceCommitBinding(
                new SourcePlanIdentity(
                        new PlanningLineageFingerprint("1".repeat(64)),
                        "2".repeat(64)),
                "snapshot-1",
                "3".repeat(64),
                "4".repeat(64));
    }

    private StubJdbc readyJdbc(Map<String, Object> binding) {
        return new StubJdbc(values(
                "current_attempt_id", "attempt-1",
                "attempt_epoch", 1L,
                "lease_expires_at", Timestamp.from(
                        Instant.parse("2026-07-26T00:00:30Z")),
                "database_now", Timestamp.from(
                        Instant.parse("2026-07-26T00:00:01Z")),
                "status", "RUNNING",
                "terminal_code", null,
                "terminal_payload_type", null,
                "terminal_payload_ref", null,
                "terminal_payload_schema_version", null,
                "terminal_payload_json", null,
                "updated_at", Timestamp.from(
                        Instant.parse("2026-07-26T00:00:00Z"))), binding);
    }

    private Map<String, Object> bindingRow(SourceCommitBinding value) {
        return values(
                "plan_fingerprint", value.planIdentity().planFingerprint(),
                "source_snapshot_ref", value.sourceSnapshotRef(),
                "snapshot_binding_digest", value.snapshotBindingDigest(),
                "execution_entry_id", value.executionEntryId());
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static final class StubJdbc {
        private final Map<String, Object> execution;
        private final Map<String, Object> binding;
        private final List<String> updates = new ArrayList<>();

        private StubJdbc(
                Map<String, Object> execution,
                Map<String, Object> binding
        ) {
            this.execution = execution;
            this.binding = binding;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("query".equals(method.getName())) {
                    String sql = (String) args[0];
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    Map<String, Object> row = sql.contains(
                            "FROM turn_source_execution_binding")
                            ? binding : execution;
                    return row == null ? List.of() : List.of(map(mapper, row));
                }
                if ("update".equals(method.getName())) {
                    updates.add((String) args[0]);
                    return 1;
                }
                return defaultValue(method.getReturnType());
            };
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(),
                    new Class<?>[]{JdbcOperations.class},
                    handler);
        }
    }

    private static <T> T map(RowMapper<T> mapper, Map<String, Object> values) {
        try {
            return mapper.mapRow(resultSet(values), 0);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static ResultSet resultSet(Map<String, Object> values) {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    Object value = args == null || args.length == 0
                            ? null : values.get(args[0]);
                    return switch (method.getName()) {
                        case "getString" -> value == null ? null : value.toString();
                        case "getLong" -> value == null
                                ? 0L : ((Number) value).longValue();
                        case "getTimestamp", "getObject" -> value;
                        case "wasNull" -> value == null;
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
