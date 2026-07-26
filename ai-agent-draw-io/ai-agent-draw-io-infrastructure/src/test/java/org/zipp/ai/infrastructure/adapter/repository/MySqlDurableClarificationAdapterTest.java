package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ClarificationId;
import org.zipp.ai.application.turn.DurableClarification;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.ReplyToClarification;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommit;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.planning.ClarificationReplyResolutionPort;
import org.zipp.ai.application.turn.planning.DirectCandidateFact;
import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.SourceProbeBinding;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlDurableClarificationAdapterTest {

    private static final TurnKey TURN =
            new TurnKey("owner-1", "conversation-1", "turn-1");

    @Test
    void terminalCommitPersistsHeaderOptionsMessageAndTerminalTogether() {
        StubJdbc jdbc = new StubJdbc();
        DurableClarification clarification = clarification();

        FencedCommitOutcome.Committed committed = assertInstanceOf(
                FencedCommitOutcome.Committed.class,
                new MySqlTerminalOnlyTurnCommitAdapter(jdbc.proxy()).commit(
                        new TerminalOnlyTurnCommit(
                                attempt(),
                                TurnStatus.REJECTED,
                                "NEEDS_USER_INPUT",
                                "clarification",
                                null,
                                "{}",
                                java.util.Optional.of(clarification))));

        assertEquals("clarification-1", committed.outcome().terminalPayloadRef());
        assertTrue(committed.outcome().terminalPayloadJson()
                .contains("\"optionSetDigest\":\"" + "a".repeat(64) + "\""));
        assertEquals(6, jdbc.updates.size());
        assertTrue(jdbc.updates.get(0).contains("INSERT INTO turn_clarification"));
        assertTrue(jdbc.updates.get(1).contains("turn_clarification_option"));
        assertTrue(jdbc.updates.get(2).contains("turn_clarification_option"));
        assertTrue(jdbc.updates.get(5).contains("UPDATE turn_execution"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void everyClarificationWritePointFailsBeforeTerminal(int failurePoint) {
        StubJdbc jdbc = new StubJdbc();
        jdbc.failAt = failurePoint;

        assertThrows(IllegalStateException.class,
                () -> new MySqlTerminalOnlyTurnCommitAdapter(jdbc.proxy()).commit(
                        new TerminalOnlyTurnCommit(
                                attempt(),
                                TurnStatus.REJECTED,
                                "NEEDS_USER_INPUT",
                                "clarification",
                                null,
                                "{}",
                                java.util.Optional.of(clarification()))));

        assertEquals(failurePoint, jdbc.updates.size());
        assertTrue(jdbc.updates.subList(0, failurePoint - 1).stream()
                .noneMatch(sql -> sql.contains("UPDATE turn_execution")));
    }

    @Test
    void resolverReturnsAuthorityCandidateAndRejectsStaleOrExpiredProposal() {
        Map<String, Object> row = optionRow();
        JdbcOperations jdbc = oneRowJdbc(row);
        MySqlClarificationReplyResolutionAdapter adapter =
                new MySqlClarificationReplyResolutionAdapter(jdbc);

        ClarificationReplyResolutionPort.Verified verified = assertInstanceOf(
                ClarificationReplyResolutionPort.Verified.class,
                adapter.resolve(
                        TURN,
                        new ReplyToClarification(
                                new ClarificationId("clarification-1")),
                        new ClarificationReplyResolutionPort.SelectionProposal(
                                "option-2", "a".repeat(64))));
        assertEquals("candidate-2", verified.candidate().candidateRef());
        assertEquals(TURN, verified.candidate().binding().turn());

        assertInstanceOf(
                ClarificationReplyResolutionPort.Stale.class,
                adapter.resolve(
                        TURN,
                        new ReplyToClarification(
                                new ClarificationId("clarification-1")),
                        new ClarificationReplyResolutionPort.SelectionProposal(
                                "option-2", "b".repeat(64))));

        row.put("database_now", Timestamp.from(
                Instant.parse("2026-07-26T02:00:00Z")));
        assertEquals(
                "CLARIFICATION_EXPIRED",
                assertInstanceOf(
                        ClarificationReplyResolutionPort.Stale.class,
                        adapter.resolve(
                                TURN,
                                new ReplyToClarification(
                                        new ClarificationId("clarification-1")),
                                new ClarificationReplyResolutionPort.SelectionProposal(
                                        "option-2", "a".repeat(64)))).code());
    }

    @Test
    void authorityStoreFailureIsTypedUnavailable() {
        JdbcOperations jdbc = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[]{JdbcOperations.class},
                (proxy, method, args) -> {
                    if ("query".equals(method.getName())) {
                        throw new TransientDataAccessResourceException("down");
                    }
                    return defaultValue(method.getReturnType());
                });

        assertInstanceOf(
                ClarificationReplyResolutionPort.Unavailable.class,
                new MySqlClarificationReplyResolutionAdapter(jdbc).resolve(
                        TURN,
                        new ReplyToClarification(
                                new ClarificationId("clarification-1")),
                        new ClarificationReplyResolutionPort.SelectionProposal(
                                "option-2", "a".repeat(64))));
    }

    private DirectCandidateFact candidate(String ref) {
        return new DirectCandidateFact(
                new SourceProbeBinding(
                        TURN,
                        new PlanningLineageFingerprint("1".repeat(64)),
                        "2".repeat(64),
                        "3".repeat(64),
                        "4".repeat(64)),
                ref,
                DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT,
                "observation-" + ref,
                "clarification-" + ref);
    }

    private DurableClarification clarification() {
        return new DurableClarification(
                new ClarificationId("clarification-1"),
                "请选择要还原的图片",
                "a".repeat(64),
                Duration.ofHours(1),
                List.of(
                        new DurableClarification.Option(
                                "option-1", "第一张", candidate("candidate-1")),
                        new DurableClarification.Option(
                                "option-2", "第二张", candidate("candidate-2"))));
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                TURN,
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

    private Map<String, Object> optionRow() {
        return values(
                "option_set_digest", "a".repeat(64),
                "expires_at", Timestamp.from(
                        Instant.parse("2026-07-26T01:00:00Z")),
                "database_now", Timestamp.from(
                        Instant.parse("2026-07-26T00:30:00Z")),
                "candidate_ref", "candidate-2",
                "candidate_origin", "CURRENT_MESSAGE_ATTACHMENT",
                "observation_fingerprint", "observation-candidate-2",
                "clarification_ref", "clarification-candidate-2",
                "lineage_fingerprint", "1".repeat(64),
                "declaration_digest", "2".repeat(64),
                "context_read_set_digest", "3".repeat(64),
                "input_binding_digest", "4".repeat(64));
    }

    private JdbcOperations oneRowJdbc(Map<String, Object> row) {
        return (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[]{JdbcOperations.class},
                (proxy, method, args) -> {
                    if ("query".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                        return List.of(map(mapper, row));
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static final class StubJdbc {
        private final List<String> updates = new ArrayList<>();
        private int failAt;

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("query".equals(method.getName())) {
                    String sql = (String) args[0];
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    Map<String, Object> row = sql.contains("FROM turn_execution")
                            ? values(
                            "diagram_id", "diagram-1",
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
                            "created_at", Timestamp.from(
                                    Instant.parse("2026-07-26T00:00:00Z")),
                            "updated_at", Timestamp.from(
                                    Instant.parse("2026-07-26T00:00:00Z")))
                            : values("version", 2L);
                    return List.of(map(mapper, row));
                }
                if ("queryForObject".equals(method.getName())) {
                    return 42L;
                }
                if ("update".equals(method.getName())) {
                    updates.add((String) args[0]);
                    if (failAt > 0 && updates.size() == failAt) {
                        throw new IllegalStateException("injected-write-" + failAt);
                    }
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
        InvocationHandler handler = new InvocationHandler() {
            private boolean wasNull;

            @Override
            public Object invoke(
                    Object proxy,
                    java.lang.reflect.Method method,
                    Object[] args
            ) {
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
                    if (args.length == 2 && value != null) {
                        return ((Class<?>) args[1]).cast(value);
                    }
                    return value;
                }
                if ("wasNull".equals(name)) {
                    return wasNull;
                }
                return defaultValue(method.getReturnType());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                handler);
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
