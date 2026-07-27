package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.DirectTurnCommit;
import org.zipp.ai.application.turn.DirectVisualProvenance;
import org.zipp.ai.application.turn.EvidenceAnswerTurnCommit;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.GroundedTurnCommit;
import org.zipp.ai.application.turn.SourceCommitBinding;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.ValidatedCitationManifest;
import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlSourceAwareTurnCommitAdapterTest {

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
    void directWritesCanvasProvenancePinMessageAndTerminalWithoutCitation() {
        StubJdbc jdbc = readyJdbc();

        FencedCommitOutcome outcome =
                new MySqlSourceAwareTurnCommitAdapter(jdbc.proxy())
                        .commit(directCommand());

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome);
        assertEquals(7, jdbc.updates.size());
        assertTrue(jdbc.updates.get(0).contains("summary, analysis_json"));
        assertTrue(jdbc.containsUpdate("INSERT INTO diagram_visual_provenance"));
        assertTrue(jdbc.containsUpdate("INSERT INTO direct_source_usage_pin"));
        assertFalse(jdbc.containsUpdate("INSERT INTO source_citation"));
        assertTrue(jdbc.updates.get(6).contains("UPDATE turn_execution"));
    }

    @Test
    void directCommitAcceptsTheDigestPinnedForAnExistingCanvas() {
        StubJdbc jdbc = readyJdbc();
        jdbc.canvas.put("version", 2L);
        jdbc.canvas.put("content_hash", "canvas-hash");
        jdbc.canvas.put("summary", "blank canvas");
        jdbc.canvas.put("analysis_json", "{\"nodeCount\":0,\"edgeCount\":0}");

        FencedCommitOutcome outcome =
                new MySqlSourceAwareTurnCommitAdapter(jdbc.proxy())
                        .commit(directCommand(
                                2,
                                digest("canvas", "diagram-1", "2", "canvas-hash",
                                        "blank canvas", "{\"nodeCount\":0,\"edgeCount\":0}")));

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome);
        assertTrue(jdbc.updates.get(0).contains("UPDATE diagram_canvas_state"));
        assertTrue(jdbc.updates.get(0).contains("summary = ?, analysis_json = ?"));
    }

    @Test
    void directEmptyModelResponseCannotOverwriteCanvas() {
        StubJdbc jdbc = readyJdbc();

        DirectTurnCommit empty = new DirectTurnCommit(
                attempt(),
                binding(),
                "diagram-1",
                0,
                "",
                "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/></root></mxGraphModel>",
                "direct done",
                "payload-direct-empty",
                new DirectVisualProvenance(
                        "provenance-1",
                        "source-identity-1",
                        DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT,
                        "observation-1"));

        FencedCommitOutcome.Rejected rejected = assertInstanceOf(
                FencedCommitOutcome.Rejected.class,
                new MySqlSourceAwareTurnCommitAdapter(jdbc.proxy()).commit(empty));

        assertEquals("CANVAS_EMPTY_CANDIDATE", rejected.code());
        assertEquals(List.of(), jdbc.updates);
    }

    @Test
    void groundedAndEvidenceAnswerPersistValidatedCitationsBeforeTerminal() {
        StubJdbc groundedJdbc = readyJdbc();
        FencedCommitOutcome grounded =
                new MySqlSourceAwareTurnCommitAdapter(groundedJdbc.proxy())
                        .commit(groundedCommand());

        assertInstanceOf(FencedCommitOutcome.Committed.class, grounded);
        assertTrue(groundedJdbc.containsUpdate("INSERT INTO source_citation"));
        assertTrue(groundedJdbc.containsUpdate("INSERT INTO citation_evidence"));
        assertTrue(groundedJdbc.containsUpdate("INSERT INTO diagram_source_pin"));

        StubJdbc answerJdbc = readyJdbc();
        FencedCommitOutcome answer =
                new MySqlSourceAwareTurnCommitAdapter(answerJdbc.proxy())
                        .commit(answerCommand());

        assertInstanceOf(FencedCommitOutcome.Committed.class, answer);
        assertFalse(answerJdbc.containsUpdate("diagram_canvas_state"));
        assertTrue(answerJdbc.containsUpdate("INSERT INTO source_citation"));
        assertTrue(answerJdbc.updates.get(answerJdbc.updates.size() - 1)
                .contains("UPDATE turn_execution"));
    }

    @Test
    void bindingMismatchAndUnknownTerminalSchemaNeverReachBusinessWrites() {
        StubJdbc mismatch = readyJdbc();
        mismatch.execution.put("plan_fingerprint", "9".repeat(64));
        FencedCommitOutcome rejected =
                new MySqlSourceAwareTurnCommitAdapter(mismatch.proxy())
                        .commit(directCommand());
        assertEquals("SOURCE_COMMIT_BINDING_MISMATCH",
                assertInstanceOf(FencedCommitOutcome.Rejected.class, rejected).code());
        assertEquals(List.of(), mismatch.updates);

        StubJdbc unknown = readyJdbc();
        unknown.execution.put("status", "COMPLETED");
        unknown.execution.put("terminal_payload_schema_version", 99);
        FencedCommitOutcome unavailable =
                new MySqlSourceAwareTurnCommitAdapter(unknown.proxy())
                        .commit(directCommand());
        assertInstanceOf(FencedCommitOutcome.TerminalUnavailable.class, unavailable);
        assertEquals(List.of(), unknown.updates);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    void everyDirectWritePointFailsBeforeAnyLaterWrite(int failurePoint) {
        StubJdbc jdbc = readyJdbc();
        jdbc.failAt = failurePoint;

        assertThrows(IllegalStateException.class,
                () -> new MySqlSourceAwareTurnCommitAdapter(jdbc.proxy())
                        .commit(directCommand()));

        assertEquals(failurePoint, jdbc.updates.size());
        assertFalse(jdbc.updates.subList(0, failurePoint - 1).stream()
                .anyMatch(sql -> sql.contains("UPDATE turn_execution")));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void everyGroundedWritePointFailsBeforeAnyLaterWrite(int failurePoint) {
        StubJdbc jdbc = readyJdbc();
        jdbc.failAt = failurePoint;

        assertThrows(IllegalStateException.class,
                () -> new MySqlSourceAwareTurnCommitAdapter(jdbc.proxy())
                        .commit(groundedCommand()));

        assertEquals(failurePoint, jdbc.updates.size());
        assertFalse(jdbc.updates.subList(0, failurePoint - 1).stream()
                .anyMatch(sql -> sql.contains("UPDATE turn_execution")));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void everyEvidenceAnswerWritePointFailsBeforeAnyLaterWrite(int failurePoint) {
        StubJdbc jdbc = readyJdbc();
        jdbc.failAt = failurePoint;

        assertThrows(IllegalStateException.class,
                () -> new MySqlSourceAwareTurnCommitAdapter(jdbc.proxy())
                        .commit(answerCommand()));

        assertEquals(failurePoint, jdbc.updates.size());
        assertFalse(jdbc.updates.subList(0, failurePoint - 1).stream()
                .anyMatch(sql -> sql.contains("UPDATE turn_execution")));
    }

    private DirectTurnCommit directCommand() {
        return directCommand(0, "");
    }

    private DirectTurnCommit directCommand(long expectedCanvasVersion, String expectedCanvasDigest) {
        return new DirectTurnCommit(
                attempt(),
                binding(),
                "diagram-1",
                expectedCanvasVersion,
                expectedCanvasDigest,
                NON_EMPTY_CANVAS,
                "direct done",
                "payload-direct",
                new DirectVisualProvenance(
                        "provenance-1",
                        "source-identity-1",
                        DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT,
                        "observation-1"));
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String normalized = value == null ? "" : value;
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '|');
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private GroundedTurnCommit groundedCommand() {
        return new GroundedTurnCommit(
                attempt(),
                binding(),
                "diagram-1",
                0,
                "",
                NON_EMPTY_CANVAS,
                "grounded done",
                "payload-grounded",
                manifest(),
                Optional.empty());
    }

    private EvidenceAnswerTurnCommit answerCommand() {
        return new EvidenceAnswerTurnCommit(
                attempt(),
                binding(),
                "diagram-1",
                0,
                "",
                "supported answer",
                "payload-answer",
                manifest());
    }

    private ValidatedCitationManifest manifest() {
        return ((ValidatedCitationManifest.ValidationOutcome.Ready)
                ValidatedCitationManifest.validate(
                        "6".repeat(64),
                        Set.of("evidence-1"),
                        List.of(new ValidatedCitationManifest.CandidateCitation(
                                "citation-1",
                                "claim-1",
                                "7".repeat(64),
                                true,
                                List.of(new ValidatedCitationManifest.EvidenceLink(
                                        "citation-key-1",
                                        "evidence-1",
                                        "material-1",
                                        "version-1",
                                        "revision-1",
                                        "PROJECT")))))).manifest();
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

    private FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease(
                        "attempt-1",
                        1,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(
                        1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private StubJdbc readyJdbc() {
        SourceCommitBinding binding = binding();
        FencedAttempt attempt = attempt();
        return new StubJdbc(
                values(
                        "current_attempt_id", attempt.attemptId(),
                        "attempt_epoch", attempt.attemptEpoch(),
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
                                Instant.parse("2026-07-26T00:00:00Z")),
                        "plan_fingerprint",
                        binding.planIdentity().planFingerprint(),
                        "source_snapshot_ref", binding.sourceSnapshotRef(),
                        "snapshot_binding_digest",
                        binding.snapshotBindingDigest(),
                        "execution_entry_id", binding.executionEntryId()),
                values("version", 2L),
                values(
                        "version", null,
                        "content_hash", null,
                        "summary", null,
                        "analysis_json", null));
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
        private final Map<String, Object> conversation;
        private final Map<String, Object> canvas;
        private final List<String> updates = new ArrayList<>();
        private int failAt;

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
                            ? execution
                            : sql.contains("FROM conversation") ? conversation : canvas;
                    return row == null ? List.of() : List.of(map(mapper, row));
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

        private boolean containsUpdate(String value) {
            return updates.stream().anyMatch(sql -> sql.contains(value));
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
}
