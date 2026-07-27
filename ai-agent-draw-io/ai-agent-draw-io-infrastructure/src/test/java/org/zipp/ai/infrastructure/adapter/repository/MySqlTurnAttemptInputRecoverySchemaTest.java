package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.time.Instant;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Executes takeover recovery against the real M1 schema when a local MySQL is available. */
@EnabledIfEnvironmentVariable(named = "M1_MYSQL_TEST_ENABLED", matches = "true")
class MySqlTurnAttemptInputRecoverySchemaTest {

    @Test
    void recoversAgainstTheProductionColumnNames() {
        Database database = new Database();
        String owner = "schema-test-owner";
        String diagram = "schema-test-" + UUID.randomUUID();
        String conversation = "schema-test-" + UUID.randomUUID();
        String turn = "schema-test-" + UUID.randomUUID();
        UserTurnCommand expected = new UserTurnCommand(
                turn, conversation, diagram, "schema-client", "draw a box", null,
                TurnDeclarations.empty());
        String inputDigest = TurnInputBindingDigestCalculator.current(expected);
        String attemptId = "schema-attempt-" + UUID.randomUUID();
        TurnInputBindingJsonCodec codec = new TurnInputBindingJsonCodec();
        assertEquals(expected.declarations(), codec.decode(codec.encode(expected.declarations())));

        try {
            database.update("INSERT INTO diagram (id, user_id, title, diagram_type) VALUES (?, ?, ?, ?)",
                    diagram, owner, "schema recovery", "basic");
            database.update("INSERT INTO conversation (id, owner_key, diagram_id, status) VALUES (?, ?, ?, 'ACTIVE')",
                    conversation, owner, diagram);
            long messageId = database.insertAndGetGeneratedKey("INSERT INTO diagram_conversation_message "
                            + "(diagram_id, user_id, client_message_id, role, content, conversation_id, turn_id, message_sequence) "
                            + "VALUES (?, ?, ?, 'user', ?, ?, ?, 1)",
                    diagram, owner, "schema-client", "draw a box", conversation, turn);
            database.update("INSERT INTO turn_engine_assignment ("
                            + "owner_key, conversation_id, diagram_id, turn_id, "
                            + "request_fingerprint_schema_version, request_fingerprint, selected_engine, "
                            + "migration_generation, migration_mode, execution_policy_schema_version, "
                            + "execution_policy_snapshot_json, execution_policy_hash, memory_write_schema_version, "
                            + "memory_write_declaration_json, memory_write_digest) "
                            + "VALUES (?, ?, ?, ?, 1, ?, 'V2', 0, 'ALL_V2', 1, ?, ?, 1, ?, 'NONE')",
                    owner, conversation, diagram, turn, "schema-fingerprint", "{}", "a".repeat(64),
                    "{\"kind\":\"NONE\"}");
            database.update("INSERT INTO turn_execution ("
                            + "owner_key, conversation_id, diagram_id, turn_id, current_attempt_id, attempt_epoch, "
                            + "lease_policy_version, lease_ttl_ms, lease_expires_at, request_message_id, "
                            + "request_fingerprint_schema_version, request_fingerprint, migration_generation, migration_mode, "
                            + "execution_policy_schema_version, execution_policy_snapshot_json, execution_policy_hash, "
                            + "turn_input_binding_schema_version, turn_input_binding_digest, turn_input_binding_json, "
                            + "memory_write_schema_version, "
                            + "memory_write_declaration_json, memory_write_digest, status) "
                            + "VALUES (?, ?, ?, ?, ?, 2, 'lease-v1', 30000, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR), ?, "
                            + "1, ?, 0, 'ALL_V2', 1, ?, ?, 1, ?, ?, 1, ?, 'NONE', 'RUNNING')",
                    owner, conversation, diagram, turn, attemptId, messageId, "schema-fingerprint", "{}",
                    "b".repeat(64), inputDigest, codec.encode(expected.declarations()),
                    "{\"kind\":\"NONE\"}");

            List<StoredBinding> storedBindings = database.jdbc().query(
                    "SELECT turn_input_binding_digest, turn_input_binding_json "
                            + "FROM turn_execution WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?",
                    (resultSet, rowNumber) -> new StoredBinding(
                            resultSet.getString("turn_input_binding_digest"),
                            resultSet.getString("turn_input_binding_json")),
                    owner, conversation, turn);
            assertEquals(1, storedBindings.size());
            assertEquals(inputDigest, storedBindings.get(0).digest());
            assertNotNull(storedBindings.get(0).json());
            assertEquals(expected.declarations(), codec.decode(storedBindings.get(0).json()));

            FencedAttempt attempt = new FencedAttempt(
                    new TurnKey(owner, conversation, turn),
                    new AttemptLease(attemptId, 2, Instant.now().plusSeconds(3600), 30_000),
                    0, inputDigest,
                    new ExecutionPolicySnapshot(1, TurnEngineMode.ALL_V2, "{}", "b".repeat(64)));

            TurnAttemptInputRecoveryPort.RecoveryOutcome recovery =
                    new MySqlTurnAttemptInputRecoveryAdapter(database.jdbc()).recover(attempt);
            TurnAttemptInputRecoveryPort.Recovered recovered = assertInstanceOf(
                    TurnAttemptInputRecoveryPort.Recovered.class, recovery, String.valueOf(recovery));
            assertEquals(expected, recovered.command());
        } finally {
            database.update("DELETE FROM turn_execution WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?",
                    owner, conversation, turn);
            database.update("DELETE FROM turn_engine_assignment WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?",
                    owner, conversation, turn);
            database.update("DELETE FROM diagram_conversation_message WHERE conversation_id = ? AND turn_id = ?",
                    conversation, turn);
            database.update("DELETE FROM conversation WHERE id = ?", conversation);
            database.update("DELETE FROM diagram WHERE id = ?", diagram);
        }
    }

    private DataSource dataSource() {
        String url = System.getenv().getOrDefault(
                "M1_MYSQL_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3307/ai_draw_io?useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("M1_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("M1_MYSQL_PASSWORD", "");
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, username, password);
            }

            @Override
            public Connection getConnection(String requestedUsername, String requestedPassword)
                    throws SQLException {
                return DriverManager.getConnection(url, requestedUsername, requestedPassword);
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
                // The schema test does not install a shared JDBC log writer.
            }

            @Override
            public void setLoginTimeout(int seconds) {
                // The schema test uses the driver default timeout.
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("schema test data source does not wrap " + iface.getName());
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private final class Database {
        private JdbcOperations jdbc() {
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(), new Class<?>[]{JdbcOperations.class},
                    (proxy, method, arguments) -> {
                        if (!"query".equals(method.getName())) {
                            throw new UnsupportedOperationException("schema test only needs query: " + method.getName());
                        }
                        String sql = (String) arguments[0];
                        @SuppressWarnings("unchecked")
                        RowMapper<Object> mapper = (RowMapper<Object>) arguments[1];
                        Object[] parameters;
                        if (arguments.length == 3 && arguments[2] instanceof Object[] varargs) {
                            parameters = varargs;
                        } else {
                            parameters = new Object[arguments.length - 2];
                            System.arraycopy(arguments, 2, parameters, 0, parameters.length);
                        }
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
                    });
        }

        private void update(String sql, Object... parameters) {
            try (Connection connection = dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("schema fixture update failed", exception);
            }
        }

        private long insertAndGetGeneratedKey(String sql, Object... parameters) {
            try (Connection connection = dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, parameters);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("schema fixture did not return a generated key");
                    }
                    return keys.getLong(1);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("schema fixture insert failed", exception);
            }
        }

        private void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
        }
    }

    private record StoredBinding(String digest, String json) {
    }
}
