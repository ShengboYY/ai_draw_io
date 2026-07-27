package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainTurnCommit;
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

class MySqlPlainTurnCommitAdapterTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final String NON_EMPTY_CANVAS = """
            <mxGraphModel><root>
              <mxCell id="0"/>
              <mxCell id="1" parent="0"/>
              <mxCell id="node-1" value="Step" vertex="1" parent="1">
                <mxGeometry x="20" y="20" width="120" height="60" as="geometry"/>
              </mxCell>
            </root></mxGraphModel>
            """;

    @Test
    void terminalReplayDoesNotTouchCanvasOrMessage() {
        FencedAttempt attempt = attempt();
        StubJdbc jdbc = new StubJdbc(
                executionRow(attempt, "COMPLETED", "COMPLETED", "plain"),
                null,
                null);

        FencedCommitOutcome outcome = new MySqlPlainTurnCommitAdapter(jdbc.proxy()).commit(command(attempt));

        assertInstanceOf(FencedCommitOutcome.AlreadyTerminal.class, outcome);
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void staleAttemptCannotReachCanvasOrMessage() {
        FencedAttempt attempt = attempt();
        StubJdbc jdbc = new StubJdbc(
                executionRow(attempt, "RUNNING", null, null, "other-attempt", 2L),
                null,
                null);

        FencedCommitOutcome outcome = new MySqlPlainTurnCommitAdapter(jdbc.proxy()).commit(command(attempt));

        FencedCommitOutcome.FenceLost lost = assertInstanceOf(FencedCommitOutcome.FenceLost.class, outcome);
        assertEquals("other-attempt", lost.status().attemptId());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void expiredAttemptCannotReachCanvasOrMessage() {
        FencedAttempt attempt = attempt();
        Map<String, Object> execution = executionRow(attempt, "RUNNING", null, null);
        execution.put("lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:00Z")));
        execution.put("database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")));
        StubJdbc jdbc = new StubJdbc(execution, null, null);

        FencedCommitOutcome outcome = new MySqlPlainTurnCommitAdapter(jdbc.proxy()).commit(command(attempt));

        assertInstanceOf(FencedCommitOutcome.FenceLost.class, outcome);
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void createCommitsCanvasMessageAndTerminalInOneWriteSequence() {
        FencedAttempt attempt = attempt();
        StubJdbc jdbc = new StubJdbc(
                executionRow(attempt, "RUNNING", null, null),
                values("version", 2L),
                values("version", null, "content_hash", null, "summary", null, "analysis_json", null));

        FencedCommitOutcome.Committed committed = assertInstanceOf(
                FencedCommitOutcome.Committed.class,
                new MySqlPlainTurnCommitAdapter(jdbc.proxy()).commit(command(attempt)));

        assertEquals(TurnStatus.COMPLETED, committed.outcome().status());
        assertEquals("plain", committed.outcome().terminalPayloadType());
        assertEquals(4, jdbc.updates.size());
        assertTrue(jdbc.updates.get(0).contains("INSERT INTO diagram_canvas_state"));
        assertTrue(jdbc.updates.get(0).contains("summary, analysis_json"));
        assertTrue(jdbc.updates.get(1).contains("UPDATE conversation"));
        assertTrue(jdbc.updates.get(2).contains("INSERT INTO diagram_conversation_message"));
        assertTrue(jdbc.updates.get(3).contains("UPDATE turn_execution"));
    }

    @Test
    void editRequiresThePinnedCanvasProjectionDigestBeforeWriting() {
        FencedAttempt attempt = attempt();
        StubJdbc jdbc = new StubJdbc(
                executionRow(attempt, "RUNNING", null, null),
                values("version", 2L),
                values("version", 2L,
                        "content_hash", "canvas-hash",
                        "summary", "two nodes and one edge",
                        "analysis_json", "{\"nodeCount\":2,\"edgeCount\":1}"));

        FencedCommitOutcome.Committed committed = assertInstanceOf(
                FencedCommitOutcome.Committed.class,
                new MySqlPlainTurnCommitAdapter(jdbc.proxy()).commit(editCommand(attempt)));

        assertEquals(TurnStatus.COMPLETED, committed.outcome().status());
        assertTrue(jdbc.updates.get(0).contains("UPDATE diagram_canvas_state"));
        assertTrue(jdbc.updates.get(0).contains("summary = ?, analysis_json = ?"));
    }

    @Test
    void emptyModelResponseCannotOverwriteCanvas() {
        FencedAttempt attempt = attempt();
        StubJdbc jdbc = new StubJdbc(
                executionRow(attempt, "RUNNING", null, null),
                values("version", 2L),
                values("version", 2L,
                        "content_hash", "canvas-hash",
                        "summary", "one node",
                        "analysis_json", "{\"nodeCount\":1,\"edgeCount\":0}"));

        PlainTurnCommit empty = new PlainTurnCommit(
                attempt,
                PlainDrawAction.EDIT,
                "diagram-1",
                2,
                digest("canvas", "diagram-1", "2", "canvas-hash",
                        "one node", "{\"nodeCount\":1,\"edgeCount\":0}"),
                "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/></root></mxGraphModel>",
                "edited",
                "payload-empty");

        FencedCommitOutcome.Rejected rejected = assertInstanceOf(
                FencedCommitOutcome.Rejected.class,
                new MySqlPlainTurnCommitAdapter(jdbc.proxy()).commit(empty));

        assertEquals("CANVAS_EMPTY_CANDIDATE", rejected.code());
        assertEquals(List.of(), jdbc.updates);
    }

    private PlainTurnCommit command(FencedAttempt attempt) {
        return new PlainTurnCommit(
                attempt,
                PlainDrawAction.CREATE,
                "diagram-1",
                0,
                "",
                NON_EMPTY_CANVAS,
                "created",
                "payload-1");
    }

    private PlainTurnCommit editCommand(FencedAttempt attempt) {
        return new PlainTurnCommit(
                attempt,
                PlainDrawAction.EDIT,
                "diagram-1",
                2,
                digest("canvas", "diagram-1", "2", "canvas-hash",
                        "two nodes and one edge", "{\"nodeCount\":2,\"edgeCount\":1}"),
                NON_EMPTY_CANVAS,
                "edited",
                "payload-2");
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
                "terminal_payload_ref", "payload-1",
                "terminal_payload_schema_version", 1,
                "terminal_payload_json", "{}",
                "response_message_id", null,
                "updated_at", Timestamp.from(UPDATED_AT));
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    private static String digest(String... values) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '|');
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static class StubJdbc {
        private final Map<String, Object> execution;
        private final Map<String, Object> conversation;
        private final Map<String, Object> canvas;
        private final List<String> updates = new ArrayList<>();

        private StubJdbc(
                Map<String, Object> execution,
                Map<String, Object> conversation,
                Map<String, Object> canvas
        ) {
            this.execution = execution;
            this.conversation = conversation;
            this.canvas = canvas;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("query".equals(method.getName())) {
                    String sql = (String) args[0];
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    Map<String, Object> row = sql.contains("FROM turn_execution")
                            ? execution : sql.contains("FROM conversation") ? conversation : canvas;
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
