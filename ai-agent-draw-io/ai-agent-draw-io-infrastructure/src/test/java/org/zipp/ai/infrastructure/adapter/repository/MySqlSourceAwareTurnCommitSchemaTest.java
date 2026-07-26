package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.DirectTurnCommit;
import org.zipp.ai.application.turn.DirectVisualProvenance;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.SourceCommitBinding;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Executes the Direct strong transaction against the real migration schema. */
@EnabledIfEnvironmentVariable(named = "M5_MYSQL_TEST_ENABLED", matches = "true")
class MySqlSourceAwareTurnCommitSchemaTest {

    @Test
    void directCommitUsesOnlyReleasedColumnsAndPersistsTheWholeWriteSet() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                String suffix = UUID.randomUUID().toString();
                TurnKey key = new TurnKey(
                        "m5-schema-owner",
                        "m5-conversation-" + suffix,
                        "m5-turn-" + suffix);
                String diagramId = "m5-diagram-" + suffix;
                String attemptId = "m5-attempt-" + suffix;
                SourceCommitBinding binding = binding();

                update(connection,
                        "INSERT INTO diagram (id, user_id, title, diagram_type) "
                                + "VALUES (?, ?, 'M5 schema smoke', 'basic')",
                        diagramId, key.ownerKey());
                update(connection,
                        "INSERT INTO conversation (id, owner_key, diagram_id, status) "
                                + "VALUES (?, ?, ?, 'ACTIVE')",
                        key.canonicalConversationId(), key.ownerKey(), diagramId);
                update(connection,
                        "INSERT INTO turn_engine_assignment ("
                                + "owner_key, conversation_id, diagram_id, turn_id, "
                                + "request_fingerprint_schema_version, request_fingerprint, "
                                + "selected_engine, migration_generation, migration_mode, "
                                + "execution_policy_schema_version, "
                                + "execution_policy_snapshot_json, execution_policy_hash, "
                                + "memory_write_schema_version, memory_write_declaration_json, "
                                + "memory_write_digest) "
                                + "VALUES (?, ?, ?, ?, 1, ?, 'V2', 0, 'ALL_V2', "
                                + "1, '{}', ?, 1, '{\"kind\":\"NONE\"}', 'NONE')",
                        key.ownerKey(), key.canonicalConversationId(), diagramId, key.turnId(),
                        "m5-fingerprint", "a".repeat(64));
                update(connection,
                        "INSERT INTO turn_execution ("
                                + "owner_key, conversation_id, diagram_id, turn_id, "
                                + "current_attempt_id, attempt_epoch, lease_policy_version, "
                                + "lease_ttl_ms, lease_expires_at, "
                                + "request_fingerprint_schema_version, request_fingerprint, "
                                + "migration_generation, migration_mode, "
                                + "execution_policy_schema_version, "
                                + "execution_policy_snapshot_json, execution_policy_hash, status) "
                                + "VALUES (?, ?, ?, ?, ?, 1, 'lease-v1', 30000, "
                                + "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR), "
                                + "1, ?, 0, 'ALL_V2', 1, '{}', ?, 'RUNNING')",
                        key.ownerKey(), key.canonicalConversationId(), diagramId, key.turnId(),
                        attemptId, "m5-fingerprint", "a".repeat(64));
                update(connection,
                        "INSERT INTO turn_source_execution_binding ("
                                + "owner_key, conversation_id, turn_id, plan_fingerprint, "
                                + "source_snapshot_ref, snapshot_binding_digest, "
                                + "execution_entry_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        key.ownerKey(), key.canonicalConversationId(), key.turnId(),
                        binding.planIdentity().planFingerprint(),
                        binding.sourceSnapshotRef(),
                        binding.snapshotBindingDigest(),
                        binding.executionEntryId());

                FencedAttempt attempt = new FencedAttempt(
                        key,
                        new AttemptLease(
                                attemptId, 1, Instant.now().plusSeconds(3600), 30_000),
                        0,
                        "input-digest",
                        new ExecutionPolicySnapshot(
                                1, TurnEngineMode.ALL_V2, "{}", "a".repeat(64)));
                FencedCommitOutcome outcome =
                        new MySqlSourceAwareTurnCommitAdapter(jdbc(connection)).commit(
                                new DirectTurnCommit(
                                        attempt,
                                        binding,
                                        diagramId,
                                        0,
                                        "",
                                        "<mxGraphModel/>",
                                        "direct schema smoke complete",
                                        "m5-payload",
                                        new DirectVisualProvenance(
                                                "m5-provenance",
                                                "m5-source",
                                                DirectCandidateOrigin
                                                        .CURRENT_MESSAGE_ATTACHMENT,
                                                "m5-observation")));

                assertInstanceOf(FencedCommitOutcome.Committed.class, outcome);
                assertEquals(1, count(connection, "diagram_canvas_state", diagramId));
                assertEquals(1, count(connection, "diagram_visual_provenance", diagramId));
                assertEquals(1, scalar(connection,
                        "SELECT COUNT(*) FROM direct_source_usage_pin "
                                + "WHERE owner_key = ? AND source_identity_ref = ?",
                        key.ownerKey(), "m5-source"));
                assertEquals("COMPLETED", text(connection,
                        "SELECT status FROM turn_execution "
                                + "WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?",
                        key.ownerKey(), key.canonicalConversationId(), key.turnId()));
            } finally {
                // Never retain smoke fixtures in a developer or CI database.
                connection.rollback();
            }
        }
    }

    private JdbcOperations jdbc(Connection connection) {
        return (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[]{JdbcOperations.class},
                (proxy, method, arguments) -> {
                    String name = method.getName();
                    String sql = (String) arguments[0];
                    Object[] parameters = parameters(arguments, 2);
                    if ("update".equals(name)) {
                        return update(connection, sql, parameters(arguments, 1));
                    }
                    if ("query".equals(name)) {
                        @SuppressWarnings("unchecked")
                        RowMapper<Object> mapper = (RowMapper<Object>) arguments[1];
                        List<Object> rows = new ArrayList<>();
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            bind(statement, parameters);
                            try (ResultSet resultSet = statement.executeQuery()) {
                                int row = 0;
                                while (resultSet.next()) {
                                    rows.add(mapper.mapRow(resultSet, row++));
                                }
                            }
                        }
                        return rows;
                    }
                    if ("queryForObject".equals(name)) {
                        try (PreparedStatement statement = connection.prepareStatement(sql);
                             ResultSet resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                return null;
                            }
                            Object value = resultSet.getObject(1);
                            Class<?> requested = (Class<?>) arguments[1];
                            if (requested == Long.class && value instanceof Number number) {
                                return number.longValue();
                            }
                            return requested.cast(value);
                        }
                    }
                    throw new UnsupportedOperationException("unsupported JDBC method: " + name);
                });
    }

    private Object[] parameters(Object[] arguments, int start) {
        if (arguments.length == start + 1 && arguments[start] instanceof Object[] values) {
            return values;
        }
        Object[] values = new Object[arguments.length - start];
        System.arraycopy(arguments, start, values, 0, values.length);
        return values;
    }

    private int update(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    private void bind(PreparedStatement statement, Object[] values) throws Exception {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private int count(Connection connection, String table, String diagramId) throws Exception {
        return scalar(connection,
                "SELECT COUNT(*) FROM " + table + " WHERE diagram_id = ?",
                diagramId);
    }

    private int scalar(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private String text(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private SourceCommitBinding binding() {
        return new SourceCommitBinding(
                new SourcePlanIdentity(
                        new PlanningLineageFingerprint("1".repeat(64)),
                        "2".repeat(64)),
                "m5-snapshot",
                "3".repeat(64),
                "4".repeat(64));
    }

    private Connection connection() throws Exception {
        String url = System.getenv().getOrDefault(
                "M5_MYSQL_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3307/ai_draw_io"
                        + "?useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("M5_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("M5_MYSQL_PASSWORD", "");
        return DriverManager.getConnection(url, username, password);
    }
}
