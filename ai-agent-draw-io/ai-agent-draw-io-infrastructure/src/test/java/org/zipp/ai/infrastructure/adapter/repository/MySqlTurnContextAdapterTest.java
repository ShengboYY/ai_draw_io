package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.ChartbookProfileContext;
import org.zipp.ai.application.turn.context.ContextCandidateLoadOutcome;
import org.zipp.ai.application.turn.context.ContextMaterializationOutcome;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.ConfirmedMemoryContext;

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

class MySqlTurnContextAdapterTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void candidateIsPinnedAndMaterializedFromTheSameProjectionVersion() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        MySqlTurnContextAdapter adapter = new MySqlTurnContextAdapter(
                jdbc(executionRow(attempt), domainRow(2L, "canvas-hash", "<xml>"), List.of()));

        ContextCandidateLoadOutcome.Ready candidate = assertInstanceOf(
                ContextCandidateLoadOutcome.Ready.class, adapter.loadCandidate(attempt, command));
        ContextReadSet readSet = candidate.value().readSet();

        assertEquals(2L, readSet.summary().version());
        assertEquals("NO_ACTIVE_CHARTBOOK", readSet.membership().reference());
        assertEquals("PROFILE_NOT_AVAILABLE", readSet.profile().reference());

        ContextMaterializationOutcome.Ready materialized = assertInstanceOf(
                ContextMaterializationOutcome.Ready.class,
                adapter.materialize(attempt, command, readSet));
        AvailableContext<TrustedCanvasContext> canvas = assertInstanceOf(
                AvailableContext.class, materialized.value().canvas());
        assertEquals(2, canvas.value().nodeCount());
        assertEquals(1, canvas.value().edgeCount());
        assertInstanceOf(AbsentContext.class, materialized.value().chartbook());
        assertInstanceOf(AbsentContext.class, materialized.value().memory());
    }

    @Test
    void materializerRetriesWhenTheLatestCanvasVersionChangedAfterPinning() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        MySqlTurnContextAdapter candidateAdapter = new MySqlTurnContextAdapter(
                jdbc(executionRow(attempt), domainRow(2L, "canvas-hash", "<xml>"), List.of()));
        ContextReadSet readSet = assertInstanceOf(
                ContextCandidateLoadOutcome.Ready.class,
                candidateAdapter.loadCandidate(attempt, command)).value().readSet();

        MySqlTurnContextAdapter changedAdapter = new MySqlTurnContextAdapter(
                jdbc(executionRow(attempt), domainRow(3L, "new-canvas-hash", "<xml-new>"), List.of()));

        assertInstanceOf(
                ContextMaterializationOutcome.Retry.class,
                changedAdapter.materialize(attempt, command, readSet));
    }

    @Test
    void activeChartbookProfileIsPinnedAndProjectedWithoutSourceContent() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        Map<String, Object> domain = domainRow(2L, "canvas-hash", "<xml>");
        domain.put("diagram_chartbook_id", "book-1");
        domain.put("active_chartbook_id", "book-1");
        domain.put("chartbook_owner_key", "owner-1");
        domain.put("chartbook_status", "ACTIVE");
        domain.put("chartbook_updated_at", Timestamp.from(UPDATED_AT));
        domain.put("chartbook_profile_version", 3L);
        domain.put("chartbook_profile_instructions", "Use swimlanes");
        domain.put("chartbook_profile_goal", "Make review easy");
        domain.put("chartbook_profile_summary", "Platform flow");
        domain.put("chartbook_profile_glossary_json", "{\"SLO\":\"service objective\"}");
        domain.put("chartbook_profile_default_style_json", "{\"layout\":\"elk\"}");
        domain.put("chartbook_profile_stable_constraints_json", "[\"No hidden source calls\"]");
        domain.put("chartbook_profile_state", "CONFIGURED");

        MySqlTurnContextAdapter adapter = new MySqlTurnContextAdapter(
                jdbc(executionRow(attempt), domain, List.of()));
        ContextReadSet readSet = assertInstanceOf(
                ContextCandidateLoadOutcome.Ready.class,
                adapter.loadCandidate(attempt, command)).value().readSet();

        assertEquals(3L, readSet.profile().version());
        ContextMaterializationOutcome.Ready materialized = assertInstanceOf(
                ContextMaterializationOutcome.Ready.class,
                adapter.materialize(attempt, command, readSet));
        AvailableContext<ChartbookProfileContext> profile = assertInstanceOf(
                AvailableContext.class, materialized.value().chartbook());
        assertEquals("Use swimlanes", profile.value().instructions());
        assertEquals(List.of("SLO=service objective"), profile.value().glossary());
        assertEquals(List.of("No hidden source calls"), profile.value().stableConstraints());
    }

    @Test
    void confirmedMemoryIsPinnedAndProjectedAsContextOnlyData() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        Map<String, Object> domain = domainRow(2L, "canvas-hash", "<xml>");
        domain.put("diagram_chartbook_id", "book-1");
        domain.put("active_chartbook_id", "book-1");
        domain.put("chartbook_owner_key", "owner-1");
        domain.put("chartbook_status", "ACTIVE");
        domain.put("chartbook_updated_at", Timestamp.from(UPDATED_AT));
        domain.put("chartbook_memory_version", 1L);
        domain.put("chartbook_memory_json",
                "[{\"decisionKey\":\"labels\",\"text\":\"Prefer short labels\"}]");

        MySqlTurnContextAdapter adapter = new MySqlTurnContextAdapter(
                jdbc(executionRow(attempt), domain, List.of()));
        ContextReadSet readSet = assertInstanceOf(
                ContextCandidateLoadOutcome.Ready.class,
                adapter.loadCandidate(attempt, command)).value().readSet();

        assertEquals("chartbook-memory:book-1", readSet.memory().reference());
        ContextMaterializationOutcome.Ready materialized = assertInstanceOf(
                ContextMaterializationOutcome.Ready.class,
                adapter.materialize(attempt, command, readSet));
        AvailableContext<ConfirmedMemoryContext> memory = assertInstanceOf(
                AvailableContext.class, materialized.value().memory());
        assertEquals(List.of("labels: Prefer short labels"), memory.value().decisions());
    }

    @Test
    void conversationContextIsRebuiltFromThePinnedMessageHighWater() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        ContextReadSet readSet = assertInstanceOf(
                ContextCandidateLoadOutcome.Ready.class,
                new MySqlTurnContextAdapter(jdbc(
                        executionRow(attempt), domainRow(2L, "canvas-hash", "<xml>"), List.of()))
                        .loadCandidate(attempt, command)).value().readSet();
        List<Map<String, Object>> messages = List.of(
                values("role", "agent", "content", "flow created"),
                values("role", "user", "content", "draw a flow"));

        ContextMaterializationOutcome.Ready materialized = assertInstanceOf(
                ContextMaterializationOutcome.Ready.class,
                new MySqlTurnContextAdapter(jdbc(
                        executionRow(attempt), domainRow(2L, "canvas-hash", "<xml>"), messages))
                        .materialize(attempt, command, readSet));
        AvailableContext<ConversationContext> conversation = assertInstanceOf(
                AvailableContext.class, materialized.value().conversation());

        assertEquals(List.of("user: draw a flow", "agent: flow created"),
                conversation.value().recentTurns());
        assertEquals("messageHighWater=4", conversation.value().summary().substring(0, 18));
    }

    @Test
    void expiredAttemptCannotReadAContextCandidate() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        Map<String, Object> expiredExecution = executionRow(attempt);
        expiredExecution.put("lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:30Z")));
        expiredExecution.put("database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:31Z")));

        ContextCandidateLoadOutcome.FenceLost lost = assertInstanceOf(
                ContextCandidateLoadOutcome.FenceLost.class,
                new MySqlTurnContextAdapter(
                        jdbc(expiredExecution, domainRow(2L, "canvas-hash", "<xml>"), List.of()))
                        .loadCandidate(attempt, command));

        assertEquals(attempt.key(), lost.status().key());
    }

    private static UserTurnCommand command() {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "message-1", "draw a flow", "session-1",
                TurnDeclarations.empty());
    }

    private static FencedAttempt attempt(UserTurnCommand command) {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                4,
                TurnInputBindingDigestCalculator.current(command),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private static Map<String, Object> executionRow(FencedAttempt attempt) {
        return values(
                "current_attempt_id", attempt.attemptId(),
                "attempt_epoch", attempt.attemptEpoch(),
                "diagram_id", "diagram-1",
                "request_message_id", null,
                "turn_input_binding_digest", attempt.inputBindingDigest(),
                "attachment_binding_digest", "attachment-digest",
                "context_message_high_water", attempt.contextMessageHighWater(),
                "lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:30Z")),
                "database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")),
                "status", "RUNNING");
    }

    private static Map<String, Object> domainRow(long version, String contentHash, String currentXml) {
        return values(
                "diagram_id", "diagram-1",
                "user_id", "owner-1",
                "diagram_chartbook_id", null,
                "diagram_updated_at", Timestamp.from(UPDATED_AT),
                "canvas_version", version,
                "current_xml", currentXml,
                "canvas_content_hash", contentHash,
                "canvas_summary", "two nodes and one edge",
                "canvas_analysis_json", "{\"nodeCount\":2,\"edgeCount\":1}",
                "active_chartbook_id", null,
                "chartbook_owner_key", null,
                "chartbook_status", null,
                "chartbook_updated_at", null);
    }

    private static JdbcOperations jdbc(
            Map<String, Object> execution,
            Map<String, Object> domain,
            List<Map<String, Object>> messages
    ) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("query".equals(method.getName())) {
                String sql = (String) args[0];
                @SuppressWarnings("unchecked")
                RowMapper<Object> rowMapper = (RowMapper<Object>) args[1];
                if (sql.contains("FROM turn_execution")) {
                    return List.of(map(rowMapper, execution));
                }
                if (sql.contains("FROM diagram d")) {
                    return List.of(map(rowMapper, domain));
                }
                if (sql.contains("FROM diagram_conversation_message")) {
                    return messages.stream().map(row -> map(rowMapper, row)).toList();
                }
                throw new AssertionError("unexpected SQL: " + sql);
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
                if ("wasNull".equals(name)) {
                    return wasNull;
                }
                if ("toString".equals(name)) {
                    return "fake-result-set";
                }
                return defaultValue(method.getReturnType());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, handler);
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
