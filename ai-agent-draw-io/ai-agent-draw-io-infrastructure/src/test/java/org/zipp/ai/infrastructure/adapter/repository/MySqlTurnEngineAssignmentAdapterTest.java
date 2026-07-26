package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AdmissionWriteOutcome;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.MatchedInstructionSpan;
import org.zipp.ai.application.turn.MemoryWriteRuleVersion;
import org.zipp.ai.application.turn.MemoryWriteSemanticDigest;
import org.zipp.ai.application.turn.MigrationStateSnapshot;
import org.zipp.ai.application.turn.NoMemoryWrite;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;
import org.zipp.ai.application.turn.SelectedTurnEngine;
import org.zipp.ai.application.turn.TurnEngineAssignmentCommand;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.VersionedRequestFingerprint;
import org.zipp.ai.application.turn.VersionedRequestFingerprintSet;

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

class MySqlTurnEngineAssignmentAdapterTest {

    @Test
    void canaryAssignmentPersistsTheSelectedV2Engine() {
        JdbcStub jdbc = new JdbcStub("V2_CANARY", false);

        AdmissionWriteOutcome.Assigned assigned = assertInstanceOf(
                AdmissionWriteOutcome.Assigned.class,
                new MySqlTurnEngineAssignmentAdapter(jdbc.proxy()).assignOrReuse(
                        command(new NoMemoryWrite(), TurnEngineMode.V2_CANARY, SelectedTurnEngine.V2)));

        assertEquals(SelectedTurnEngine.V2, assigned.assignment().selectedEngine());
        assertEquals(1, jdbc.updates.size());
    }

    @Test
    void matchingFingerprintAndDeclarationReuseThePersistedAssignment() {
        JdbcStub jdbc = new JdbcStub();

        AdmissionWriteOutcome.Reused reused = assertInstanceOf(
                AdmissionWriteOutcome.Reused.class,
                new MySqlTurnEngineAssignmentAdapter(jdbc.proxy()).assignOrReuse(command(new NoMemoryWrite())));

        assertEquals(SelectedTurnEngine.V2, reused.assignment().selectedEngine());
        assertEquals(3, reused.assignment().migration().generation());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void matchingFingerprintWithDifferentMemoryDeclarationFailsClosed() {
        JdbcStub jdbc = new JdbcStub();
        RememberDecisionDeclaration changed = new RememberDecisionDeclaration(
                1,
                new MemoryWriteRuleVersion("rule-1"),
                new MatchedInstructionSpan("remember this"),
                new MemoryWriteSemanticDigest("digest-1"));

        AdmissionWriteOutcome.Rejected rejected = assertInstanceOf(
                AdmissionWriteOutcome.Rejected.class,
                new MySqlTurnEngineAssignmentAdapter(jdbc.proxy()).assignOrReuse(command(changed)));

        // A retry cannot change the declaration pinned by the first assignment.
        assertEquals("MEMORY_DECLARATION_CONFLICT", rejected.code());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void existingAssignmentCannotBeReboundToAnotherDiagram() {
        JdbcStub jdbc = new JdbcStub();

        AdmissionWriteOutcome.Rejected rejected = assertInstanceOf(
                AdmissionWriteOutcome.Rejected.class,
                new MySqlTurnEngineAssignmentAdapter(jdbc.proxy()).assignOrReuse(
                        command(new NoMemoryWrite(), "diagram-2", new ExecutionPolicySnapshot(
                                1, TurnEngineMode.ALL_V2, "{}", "live-policy-hash"))));

        assertEquals("DIAGRAM_BINDING_MISMATCH", rejected.code());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void retryKeepsThePersistedPolicyWhenTheLiveFlagChanges() {
        JdbcStub jdbc = new JdbcStub();

        AdmissionWriteOutcome.Reused reused = assertInstanceOf(
                AdmissionWriteOutcome.Reused.class,
                new MySqlTurnEngineAssignmentAdapter(jdbc.proxy()).assignOrReuse(
                        command(new NoMemoryWrite(), "diagram-1", new ExecutionPolicySnapshot(
                                1, TurnEngineMode.ALL_V2, "{\"flag\":\"new\"}", "live-policy-hash"))));

        // A retry must execute the first assignment's policy, not the current live flag.
        assertEquals("policy-hash", reused.assignment().policy().policyHash());
        assertEquals("{}", reused.assignment().policy().snapshotJson());
        assertEquals(List.of(), jdbc.updates);
    }

    private static TurnEngineAssignmentCommand command(
            org.zipp.ai.application.turn.MemoryWriteDeclaration memoryWrite
    ) {
        return command(memoryWrite, "diagram-1", new ExecutionPolicySnapshot(
                1, TurnEngineMode.ALL_V2, "{}", "policy-hash"));
    }

    private static TurnEngineAssignmentCommand command(
            org.zipp.ai.application.turn.MemoryWriteDeclaration memoryWrite,
            TurnEngineMode mode
    ) {
        return command(memoryWrite, mode, SelectedTurnEngine.V2);
    }

    private static TurnEngineAssignmentCommand command(
            org.zipp.ai.application.turn.MemoryWriteDeclaration memoryWrite,
            TurnEngineMode mode,
            SelectedTurnEngine selectedEngine
    ) {
        return new TurnEngineAssignmentCommand(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "diagram-1",
                new VersionedRequestFingerprintSet(
                        List.of(new VersionedRequestFingerprint(1, "fingerprint"))),
                new ExecutionPolicySnapshot(1, mode, "{}", "policy-hash"),
                new MigrationStateSnapshot(3, mode, Instant.parse("2026-07-26T00:00:00Z")),
                memoryWrite,
                selectedEngine);
    }

    private static TurnEngineAssignmentCommand command(
            org.zipp.ai.application.turn.MemoryWriteDeclaration memoryWrite,
            String diagramId,
            ExecutionPolicySnapshot policy
    ) {
        TurnKey key = new TurnKey("owner-1", "conversation-1", "turn-1");
        return new TurnEngineAssignmentCommand(
                key,
                diagramId,
                new VersionedRequestFingerprintSet(
                        List.of(new VersionedRequestFingerprint(1, "fingerprint"))),
                policy,
                new MigrationStateSnapshot(
                        3, TurnEngineMode.ALL_V2, Instant.parse("2026-07-26T00:00:00Z")),
                memoryWrite,
                SelectedTurnEngine.V2);
    }

    private static final class JdbcStub {
        private final List<String> updates = new java.util.ArrayList<>();
        private final String migrationMode;
        private boolean existing;

        private JdbcStub() {
            this("ALL_V2", true);
        }

        private JdbcStub(String migrationMode, boolean existing) {
            this.migrationMode = migrationMode;
            this.existing = existing;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("queryForObject".equals(method.getName())) {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    return map(mapper, migrationRow());
                }
                if ("query".equals(method.getName())) {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    return existing ? List.of(map(mapper, assignmentRow())) : List.of();
                }
                if ("update".equals(method.getName())) {
                    updates.add((String) args[0]);
                    existing = true;
                    return 1;
                }
                return defaultValue(method.getReturnType());
            };
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(), new Class<?>[]{JdbcOperations.class}, handler);
        }

        private Map<String, Object> migrationRow() {
            return values(
                    "generation", 3L,
                    "mode", migrationMode,
                    "switched_at", Timestamp.from(Instant.parse("2026-07-26T00:00:00Z")));
        }

        private static Map<String, Object> assignmentRow() {
            return values(
                    "owner_key", "owner-1",
                    "conversation_id", "conversation-1",
                    "diagram_id", "diagram-1",
                    "turn_id", "turn-1",
                    "request_fingerprint_schema_version", 1,
                    "request_fingerprint", "fingerprint",
                    "selected_engine", "V2",
                    "migration_generation", 3L,
                    "migration_mode", "ALL_V2",
                    "execution_policy_schema_version", 1,
                    "execution_policy_snapshot_json", "{}",
                    "execution_policy_hash", "policy-hash",
                    "memory_write_schema_version", 1,
                    "memory_write_declaration_json", "{\"kind\":\"NONE\"}",
                    "memory_write_digest", "NONE",
                    "legacy_retirement_state", null,
                    "created_at", Timestamp.from(Instant.parse("2026-07-26T00:00:00Z")));
        }

        private static Map<String, Object> values(Object... pairs) {
            Map<String, Object> values = new HashMap<>();
            for (int index = 0; index < pairs.length; index += 2) {
                values.put((String) pairs[index], pairs[index + 1]);
            }
            return values;
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
                if ("getInt".equals(method.getName())) {
                    Object value = values.get(args[0]);
                    return value == null ? 0 : ((Number) value).intValue();
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
