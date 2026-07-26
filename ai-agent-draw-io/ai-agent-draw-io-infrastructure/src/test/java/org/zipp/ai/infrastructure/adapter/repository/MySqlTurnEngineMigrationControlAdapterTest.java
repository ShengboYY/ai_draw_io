package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.MigrationModeSwitchCommand;
import org.zipp.ai.application.turn.MigrationModeSwitchOutcome;
import org.zipp.ai.application.turn.LegacyRetirementGatePort;
import org.zipp.ai.application.turn.TurnEngineMode;

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

class MySqlTurnEngineMigrationControlAdapterTest {

    @Test
    void successfulModeSwitchReturnsThePostCasGeneration() {
        JdbcStub jdbc = new JdbcStub(
                List.of(migrationRow(4, TurnEngineMode.LEGACY), migrationRow(5, TurnEngineMode.V2_CANARY)),
                1);

        MigrationModeSwitchOutcome.Changed changed = assertInstanceOf(
                MigrationModeSwitchOutcome.Changed.class,
                new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).switchMode(
                        new MigrationModeSwitchCommand(4, TurnEngineMode.LEGACY, TurnEngineMode.V2_CANARY)));

        assertEquals(5, changed.state().generation());
        assertEquals(TurnEngineMode.V2_CANARY, changed.state().mode());
        assertEquals(1, jdbc.updates.size());
        assertTrue(jdbc.updates.get(0).contains("generation = ? AND mode = ?"));
    }

    @Test
    void compareAndSwitchRejectsWhenTheDurableModeAlreadyChanged() {
        JdbcStub jdbc = new JdbcStub(
                List.of(migrationRow(4, TurnEngineMode.V2_CANARY)),
                1);

        MigrationModeSwitchOutcome.Rejected rejected = assertInstanceOf(
                MigrationModeSwitchOutcome.Rejected.class,
                new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).switchMode(
                        new MigrationModeSwitchCommand(4, TurnEngineMode.LEGACY, TurnEngineMode.ALL_V2)));

        assertEquals("MIGRATION_MODE_CHANGED", rejected.code());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void compareAndSwitchReportsLostCasWithoutReloadingAState() {
        JdbcStub jdbc = new JdbcStub(List.of(migrationRow(4, TurnEngineMode.V2_CANARY)), 0);

        MigrationModeSwitchOutcome.Rejected rejected = assertInstanceOf(
                MigrationModeSwitchOutcome.Rejected.class,
                new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).switchMode(
                        new MigrationModeSwitchCommand(4, TurnEngineMode.V2_CANARY, TurnEngineMode.ALL_V2)));

        assertEquals("MIGRATION_MODE_SWITCH_LOST", rejected.code());
        assertEquals(1, jdbc.updates.size());
        assertEquals(1, jdbc.stateReads);
    }

    @Test
    void compareAndSwitchRejectsStaleExpectedGeneration() {
        JdbcStub jdbc = new JdbcStub(List.of(migrationRow(5, TurnEngineMode.LEGACY)), 1);

        MigrationModeSwitchOutcome.Rejected rejected = assertInstanceOf(
                MigrationModeSwitchOutcome.Rejected.class,
                new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).switchMode(
                        new MigrationModeSwitchCommand(4, TurnEngineMode.LEGACY, TurnEngineMode.V2_CANARY)));

        assertEquals("MIGRATION_GENERATION_CHANGED", rejected.code());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void compareAndSwitchRejectsIllegalModeJump() {
        JdbcStub jdbc = new JdbcStub(List.of(migrationRow(4, TurnEngineMode.LEGACY)), 1);

        MigrationModeSwitchOutcome.Rejected rejected = assertInstanceOf(
                MigrationModeSwitchOutcome.Rejected.class,
                new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).switchMode(
                        new MigrationModeSwitchCommand(4, TurnEngineMode.LEGACY, TurnEngineMode.ALL_V2)));

        assertEquals("MIGRATION_MODE_TRANSITION_INVALID", rejected.code());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void allowsCanaryToAdvanceToAllV2() {
        JdbcStub jdbc = new JdbcStub(
                List.of(migrationRow(4, TurnEngineMode.V2_CANARY), migrationRow(5, TurnEngineMode.ALL_V2)),
                1);

        MigrationModeSwitchOutcome.Changed changed = assertInstanceOf(
                MigrationModeSwitchOutcome.Changed.class,
                new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).switchMode(
                        new MigrationModeSwitchCommand(4, TurnEngineMode.V2_CANARY, TurnEngineMode.ALL_V2)));

        assertEquals(TurnEngineMode.ALL_V2, changed.state().mode());
    }

    @Test
    void allowsAllV2ToRetireTheLegacyExecutor() {
        JdbcStub jdbc = new JdbcStub(
                List.of(migrationRow(4, TurnEngineMode.ALL_V2), migrationRow(5, TurnEngineMode.RETIRED)),
                1);

        MigrationModeSwitchOutcome.Changed changed = assertInstanceOf(
                MigrationModeSwitchOutcome.Changed.class,
                new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).switchMode(
                        new MigrationModeSwitchCommand(4, TurnEngineMode.ALL_V2, TurnEngineMode.RETIRED)));

        assertEquals(TurnEngineMode.RETIRED, changed.state().mode());
        assertEquals(1, jdbc.updates.size());
    }

    @Test
    void retirementReadinessReadsAllDatabaseSafetyCounters() {
        JdbcStub jdbc = new JdbcStub(List.of(migrationRow(4, TurnEngineMode.ALL_V2)), 1);

        LegacyRetirementGatePort.LegacyRetirementReadiness readiness =
                new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).readiness();

        assertEquals(new LegacyRetirementGatePort.LegacyRetirementReadiness(2, 1, 3, 4), readiness);
        assertTrue(jdbc.queries.get(0).contains("CURRENT_TIMESTAMP(3)"));
        assertTrue(jdbc.queries.get(0).contains("legacy_turn_tombstone"));
    }

    @Test
    void backfillUsesDatabaseCreatedAtAndLeavesExpiredGoneRowsUntouched() {
        JdbcStub jdbc = new JdbcStub(List.of(migrationRow(4, TurnEngineMode.LEGACY)), 3);

        assertEquals(3, new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).backfillRetryable(25));

        assertEquals(1, jdbc.updates.size());
        assertTrue(jdbc.updates.get(0).contains("DATE_ADD(created_at, INTERVAL 7 DAY)"));
        assertTrue(jdbc.updates.get(0).contains("legacy_retirement_state IS NULL"));
    }

    @Test
    void expiryScannerWritesTombstoneBeforeMarkingDueAssignmentGone() {
        JdbcStub jdbc = new JdbcStub(
                List.of(
                        migrationRow(4, TurnEngineMode.LEGACY),
                        values(
                                "owner_key", "owner-1",
                                "conversation_id", "conversation-1",
                                "diagram_id", "diagram-1",
                                "turn_id", "turn-1")),
                1);

        assertEquals(1, new MySqlTurnEngineMigrationControlAdapter(jdbc.proxy()).expireDue(25));

        assertEquals(2, jdbc.updates.size());
        assertTrue(jdbc.updates.get(0).contains("INSERT INTO legacy_turn_tombstone"));
        assertTrue(jdbc.updates.get(1).contains("legacy_retirement_state = 'EXPIRED_GONE'"));
    }

    private static Map<String, Object> migrationRow(long generation, TurnEngineMode mode) {
        return values(
                "generation", generation,
                "mode", mode.name(),
                "switched_at", Timestamp.from(Instant.parse("2026-07-26T00:00:00Z")),
                "tombstone_retain_until", Timestamp.from(Instant.parse("2026-08-26T00:00:00Z")));
    }

    private static Map<String, Object> readinessRow() {
        return values(
                "executable_assignments", 2L,
                "retry_horizon_pending", 1L,
                "missing_tombstones", 3L,
                "expired_tombstones", 4L);
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    private static final class JdbcStub {
        private final List<Map<String, Object>> stateRows;
        private final int updateResult;
        private final List<String> updates = new ArrayList<>();
        private final List<String> queries = new ArrayList<>();
        private int stateReads;

        private JdbcStub(List<Map<String, Object>> stateRows, int updateResult) {
            this.stateRows = stateRows;
            this.updateResult = updateResult;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("query".equals(method.getName())) {
                    String sql = (String) args[0];
                    queries.add(sql);
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    Map<String, Object> row = sql.contains("executable_assignments")
                            ? readinessRow()
                            : stateRows.get(Math.min(stateReads++, stateRows.size() - 1));
                    return List.of(map(mapper, row));
                }
                if ("update".equals(method.getName())) {
                    updates.add((String) args[0]);
                    return updateResult;
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
