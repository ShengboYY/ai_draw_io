package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.memory.MemoryCandidateFence;
import org.zipp.ai.application.memory.MemoryCandidateStatus;
import org.zipp.ai.application.memory.MemoryMaterializeCommand;
import org.zipp.ai.application.memory.MemoryMaterializeOutcome;
import org.zipp.ai.application.memory.MemoryPolicySanitizer;
import org.zipp.ai.application.memory.MemoryProposalOutcome;
import org.zipp.ai.application.memory.SanitizedMemoryProposal;
import org.zipp.ai.application.turn.TurnKey;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs the candidate/materialize/scrub contract against the actual MySQL tables when enabled. */
@EnabledIfEnvironmentVariable(named = "M2_MYSQL_TEST_ENABLED", matches = "true")
class MySqlConfirmedMemorySchemaTest {
    @Test
    void materializationIsIdempotentAndScrubsTheCandidatePayload() {
        Database database = new Database();
        MySqlConfirmedMemoryAdapter adapter = new MySqlConfirmedMemoryAdapter(database.jdbc());
        String owner = "memory-schema-owner-" + UUID.randomUUID();
        String chartbook = "memory-schema-book-" + UUID.randomUUID();
        String diagram = "memory-schema-diagram-" + UUID.randomUUID();
        String conversation = "memory-schema-conversation-" + UUID.randomUUID();
        String turn = "memory-schema-turn-" + UUID.randomUUID();
        TurnKey key = new TurnKey(owner, conversation, turn);
        String candidate = "memory-schema-candidate-" + UUID.randomUUID();
        String declarationDigest = "memory-declaration-1";

        try {
            database.update("INSERT INTO diagram (id, user_id, title, diagram_type) VALUES (?, ?, ?, ?)",
                    diagram, owner, "memory schema", "basic");
            database.update("INSERT INTO conversation (id, owner_key, diagram_id, status) VALUES (?, ?, ?, 'ACTIVE')",
                    conversation, owner, diagram);
            database.update("INSERT INTO chartbook (id, owner_key, name, status, preferences_json) "
                            + "VALUES (?, ?, ?, 'ACTIVE', JSON_OBJECT())",
                    chartbook, owner, "memory schema");
            database.update("UPDATE diagram SET chartbook_id = ? WHERE id = ?", chartbook, diagram);
            database.update("INSERT INTO turn_engine_assignment ("
                            + "owner_key, conversation_id, diagram_id, turn_id, request_fingerprint_schema_version, "
                            + "request_fingerprint, selected_engine, migration_generation, migration_mode, "
                            + "execution_policy_schema_version, execution_policy_snapshot_json, execution_policy_hash) "
                            + "VALUES (?, ?, ?, ?, 1, ?, 'V2', 0, 'ALL_V2', 1, JSON_OBJECT(), ?)",
                    owner, conversation, diagram, turn, "memory-fingerprint", "a".repeat(64));
            database.update("INSERT INTO turn_execution ("
                            + "owner_key, conversation_id, diagram_id, turn_id, request_fingerprint_schema_version, "
                            + "request_fingerprint, migration_generation, migration_mode, execution_policy_schema_version, "
                            + "execution_policy_snapshot_json, execution_policy_hash, status, terminal_code, terminal_payload_type) "
                            + "VALUES (?, ?, ?, ?, 1, ?, 0, 'ALL_V2', 1, JSON_OBJECT(), ?, 'COMPLETED', 'OK', 'TEST')",
                    owner, conversation, diagram, turn, "memory-fingerprint", "b".repeat(64));

            SanitizedMemoryProposal proposal = new SanitizedMemoryProposal(
                    key, chartbook, diagram, candidate, declarationDigest, "labels", "plain-generation",
                    "CHARTBOOK", "Prefer short labels", MemoryPolicySanitizer.POLICY_VERSION,
                    Duration.ofHours(24));
            MemoryProposalOutcome.Accepted accepted = assertInstanceOf(
                    MemoryProposalOutcome.Accepted.class, adapter.propose(proposal));
            assertEquals(MemoryCandidateStatus.PENDING, accepted.proposal().status());

            MemoryCandidateFence fence = new MemoryCandidateFence(
                    key, chartbook, candidate, declarationDigest);
            MemoryMaterializeOutcome.Materialized materialized = assertInstanceOf(
                    MemoryMaterializeOutcome.Materialized.class,
                    adapter.materialize(new MemoryMaterializeCommand(fence)));
            assertEquals("Prefer short labels", materialized.memory().canonicalText());
            assertNull(database.scalar(
                    "SELECT canonical_text FROM chartbook_memory_candidate WHERE candidate_id = ?",
                    candidate));

            assertInstanceOf(MemoryMaterializeOutcome.AlreadyMaterialized.class,
                    adapter.materialize(new MemoryMaterializeCommand(fence)));
            assertInstanceOf(MemoryMaterializeOutcome.Gone.class, adapter.revoke(fence));
            assertEquals(1, adapter.recall(owner, chartbook, 8).size());
        } finally {
            database.update("DELETE FROM chartbook_memory_candidate WHERE candidate_id = ?", candidate);
            database.update("DELETE FROM chartbook_memory WHERE memory_id = ?", candidate);
            database.update("DELETE FROM turn_execution WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?",
                    owner, conversation, turn);
            database.update("DELETE FROM turn_engine_assignment WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?",
                    owner, conversation, turn);
            database.update("DELETE FROM chartbook WHERE id = ?", chartbook);
            database.update("DELETE FROM conversation WHERE id = ?", conversation);
            database.update("DELETE FROM diagram WHERE id = ?", diagram);
        }
    }

    private DataSource dataSource() {
        String url = System.getenv().getOrDefault(
                "M2_MYSQL_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3307/ai_draw_io?useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("M2_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("M2_MYSQL_PASSWORD", "");
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, username, password);
            }
            @Override public Connection getConnection(String user, String requestedPassword) throws SQLException {
                return DriverManager.getConnection(url, user, requestedPassword);
            }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private final class Database {
        private JdbcOperations jdbc() {
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(), new Class<?>[]{JdbcOperations.class},
                    (proxy, method, arguments) -> {
                        if ("query".equals(method.getName())) {
                            String sql = (String) arguments[0];
                            @SuppressWarnings("unchecked")
                            RowMapper<Object> mapper = (RowMapper<Object>) arguments[1];
                            Object[] parameters = parameters(arguments, 2);
                            List<Object> rows = new ArrayList<>();
                            try (Connection connection = dataSource().getConnection();
                                 PreparedStatement statement = connection.prepareStatement(sql)) {
                                bind(statement, parameters);
                                try (ResultSet resultSet = statement.executeQuery()) {
                                    int rowNumber = 0;
                                    while (resultSet.next()) {
                                        rows.add(mapper.mapRow(resultSet, rowNumber++));
                                    }
                                }
                            }
                            return rows;
                        }
                        if ("update".equals(method.getName())) {
                            return update((String) arguments[0], parameters(arguments, 1));
                        }
                        throw new UnsupportedOperationException("schema test does not need: " + method.getName());
                    });
        }

        private int update(String sql, Object... values) {
            try (Connection connection = dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, values);
                return statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("schema fixture update failed", exception);
            }
        }

        private String scalar(String sql, Object... values) {
            try (Connection connection = dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, values);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getString(1) : null;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("schema fixture query failed", exception);
            }
        }

        private Object[] parameters(Object[] arguments, int start) {
            if (arguments.length == start + 1 && arguments[start] instanceof Object[] values) {
                return values;
            }
            Object[] values = new Object[arguments.length - start];
            System.arraycopy(arguments, start, values, 0, values.length);
            return values;
        }

        private void bind(PreparedStatement statement, Object[] values) throws SQLException {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
        }
    }
}
