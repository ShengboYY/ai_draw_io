package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.MatchedInstructionSpan;
import org.zipp.ai.application.turn.MemoryWriteRuleVersion;
import org.zipp.ai.application.turn.MemoryWriteSemanticDigest;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;
import org.zipp.ai.application.turn.ReplyToClarification;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.ClarificationId;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.UntrustedLegacyVersionDeclaration;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlTurnAttemptInputRecoveryAdapterTest {

    @Test
    void rebuildsThePinnedCommandFromMessageAndDeclarationPayload() {
        UserTurnCommand expected = command();
        TurnInputBindingJsonCodec codec = new TurnInputBindingJsonCodec();
        JdbcStub jdbc = new JdbcStub(row(
                codec.encode(expected.declarations()),
                TurnInputBindingDigestCalculator.current(expected)));

        TurnAttemptInputRecoveryPort.Recovered recovered = assertInstanceOf(
                TurnAttemptInputRecoveryPort.Recovered.class,
                new MySqlTurnAttemptInputRecoveryAdapter(jdbc.proxy()).recover(attempt()));

        assertEquals(expected, recovered.command());
        assertTrue(jdbc.query.contains("FOR UPDATE"));
    }

    @Test
    void refusesAChangedPinnedDigestInsteadOfRunningDifferentDeclarations() {
        UserTurnCommand expected = command();
        TurnInputBindingJsonCodec codec = new TurnInputBindingJsonCodec();
        TurnAttemptInputRecoveryPort.Unavailable unavailable = assertInstanceOf(
                TurnAttemptInputRecoveryPort.Unavailable.class,
                new MySqlTurnAttemptInputRecoveryAdapter(new JdbcStub(row(
                        codec.encode(expected.declarations()), "wrong-digest")).proxy())
                        .recover(attempt()));

        assertEquals("TURN_INPUT_BINDING_DIGEST_MISMATCH", unavailable.code());
    }

    @Test
    void staleEpochCannotRecoverAfterDurableTakeover() {
        UserTurnCommand expected = command();
        TurnInputBindingJsonCodec codec = new TurnInputBindingJsonCodec();
        TurnAttemptInputRecoveryPort.FenceLost lost = assertInstanceOf(
                TurnAttemptInputRecoveryPort.FenceLost.class,
                new MySqlTurnAttemptInputRecoveryAdapter(new JdbcStub(
                        rowWithAttempt(codec.encode(expected.declarations()),
                                TurnInputBindingDigestCalculator.current(expected), "attempt-3", 3L)).proxy())
                        .recover(attempt()));

        assertEquals(new TurnAttemptInputRecoveryPort.FenceLost(), lost);
    }

    private static UserTurnCommand command() {
        TurnDeclarations declarations = new TurnDeclarations(
                List.of(new OpaqueConversationFileRef("file-1")),
                new ReplyToClarification(new ClarificationId("clarification-1")),
                List.of(new UntrustedLegacyVersionDeclaration("legacy-source-1")),
                new RememberDecisionDeclaration(
                        1,
                        new MemoryWriteRuleVersion("memory-rule-v1"),
                        new MatchedInstructionSpan("remember this decision"),
                        new MemoryWriteSemanticDigest("sha256:memory")));
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", "draw a box", null, declarations);
    }

    private static FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-2", 2,
                        Instant.parse("2026-07-26T00:00:00Z"), 30_000),
                1,
                TurnInputBindingDigestCalculator.current(command()),
                new ExecutionPolicySnapshot(1, TurnEngineMode.ALL_V2, "{}", "policy-hash"));
    }

    private static Map<String, Object> row(String json, String digest) {
        return rowWithAttempt(json, digest, "attempt-2", 2L);
    }

    private static Map<String, Object> rowWithAttempt(
            String json, String digest, String attemptId, long epoch) {
        Map<String, Object> values = new HashMap<>();
        values.put("diagram_id", "diagram-1");
        values.put("current_attempt_id", attemptId);
        values.put("attempt_epoch", epoch);
        values.put("lease_expires_at", Timestamp.from(Instant.parse("2026-07-26T00:00:30Z")));
        values.put("database_now", Timestamp.from(Instant.parse("2026-07-26T00:00:01Z")));
        values.put("turn_input_binding_schema_version", 1);
        values.put("turn_input_binding_digest", digest);
        values.put("turn_input_binding_json", json);
        values.put("request_message_id", 42L);
        values.put("turn_id", "turn-1");
        values.put("client_message_id", "client-1");
        values.put("content", "draw a box");
        values.put("status", "RUNNING");
        return values;
    }

    private static final class JdbcStub {
        private final Map<String, Object> row;
        private String query = "";

        private JdbcStub(Map<String, Object> row) {
            this.row = row;
        }

        private JdbcOperations proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("query".equals(method.getName())) {
                    query = (String) args[0];
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) args[1];
                    if (query.contains("conversation_message_attachment")) {
                        return List.of(map(mapper, Map.of("conversation_file_ref", "file-1")));
                    }
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
                if ("getLong".equals(method.getName())) {
                    Object value = values.get(args[0]);
                    return value == null ? 0L : ((Number) value).longValue();
                }
                if ("getInt".equals(method.getName())) {
                    Object value = values.get(args[0]);
                    return value == null ? 0 : ((Number) value).intValue();
                }
                if ("getTimestamp".equals(method.getName()) || "getObject".equals(method.getName())) {
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
