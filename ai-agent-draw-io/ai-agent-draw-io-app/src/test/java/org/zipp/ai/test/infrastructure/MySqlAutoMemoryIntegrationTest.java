package org.zipp.ai.test.infrastructure;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryFence;
import org.zipp.ai.application.memory.AutoMemoryManagementOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationCommand;
import org.zipp.ai.application.memory.AutoMemoryObservationOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.MemoryObservationKind;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.infrastructure.adapter.repository.MySqlAutoMemoryAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the production migrations and Auto Memory authority against a disposable MySQL 8 database.
 *
 * <p>The environment gate prevents this test from touching a developer database accidentally.</p>
 */
@EnabledIfEnvironmentVariable(named = "AUTO_MEMORY_MYSQL_TEST_ENABLED", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlAutoMemoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");

    private final String suffix = UUID.randomUUID().toString();
    private final String legacyOwner = "auto-memory-legacy-" + suffix;
    private final String legacyChartbook = "auto-memory-legacy-book-" + suffix;
    private final String adapterOwner = "auto-memory-adapter-" + suffix;
    private final String otherOwner = "auto-memory-other-" + suffix;
    private final String adapterChartbook = "auto-memory-adapter-book-" + suffix;
    private final String activeLegacyMemory = "legacy-active-" + suffix;
    private final String disabledLegacyMemory = "legacy-disabled-" + suffix;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private boolean schemaReady;

    @BeforeAll
    void migrateDisposableDatabase() throws Exception {
        dataSource = new DriverManagerDataSource(
                environment(
                        "AUTO_MEMORY_MYSQL_JDBC_URL",
                        "jdbc:mysql://127.0.0.1:33307/ai_draw_io"
                                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"),
                environment("AUTO_MEMORY_MYSQL_USER", "root"),
                environment("AUTO_MEMORY_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        // Reuse released SQL to build the exact predecessor instead of duplicating table fixtures.
        executeSql(dataSource, "docs/sql/diagram.sql");
        executeSql(
                dataSource,
                "docs/sql/migrations/2026-07-19-create-material-rag-foundation.sql");
        executeSql(
                dataSource,
                "docs/sql/migrations/2026-07-31-create-confirmed-memory.sql");

        insertChartbook(legacyChartbook, legacyOwner);
        insertChartbook(adapterChartbook, adapterOwner);
        insertLegacyMemory(activeLegacyMemory, "ACTIVE", "Prefer concise labels");
        insertLegacyMemory(disabledLegacyMemory, "DISABLED", "Avoid decorative icons");

        executeSql(
                dataSource,
                "docs/sql/migrations/2026-08-14-create-auto-memory.sql");
        schemaReady = true;
    }

    @AfterAll
    void removeSuiteFixtures() {
        if (!schemaReady || jdbc == null) {
            return;
        }
        // Evidence is deleted by the Item foreign key; released schema remains for inspection.
        jdbc.update("DELETE FROM memory_item WHERE owner_key IN (?, ?)",
                legacyOwner, adapterOwner);
        jdbc.update("DELETE FROM chartbook_memory WHERE owner_key IN (?, ?)",
                legacyOwner, adapterOwner);
        jdbc.update("DELETE FROM chartbook_memory_candidate WHERE owner_key IN (?, ?)",
                legacyOwner, adapterOwner);
        jdbc.update("DELETE FROM chartbook WHERE id IN (?, ?)",
                legacyChartbook, adapterChartbook);
    }

    @Test
    void migrationBackfillsLegacyRowsAndIsIdempotent() throws Exception {
        assertEquals(2, count(
                "SELECT COUNT(*) FROM memory_item WHERE owner_key = ?", legacyOwner));
        assertEquals(2, count("""
                SELECT COUNT(*)
                FROM memory_evidence e
                JOIN memory_item m ON m.memory_id = e.memory_id
                WHERE m.owner_key = ?
                """, legacyOwner));
        assertEquals("ACTIVE", text(
                "SELECT status FROM memory_item WHERE memory_id = ?", activeLegacyMemory));
        assertEquals("DISABLED", text(
                "SELECT status FROM memory_item WHERE memory_id = ?", disabledLegacyMemory));

        executeSql(
                dataSource,
                "docs/sql/migrations/2026-08-14-create-auto-memory.sql");

        assertEquals(2, count(
                "SELECT COUNT(*) FROM memory_item WHERE owner_key = ?", legacyOwner));
        assertEquals(2, count("""
                SELECT COUNT(*)
                FROM memory_evidence e
                JOIN memory_item m ON m.memory_id = e.memory_id
                WHERE m.owner_key = ?
                """, legacyOwner));
    }

    @Test
    void inferredEvidenceActivatesOnceAndUserDisableBlocksAutomaticReactivation() {
        MySqlAutoMemoryAdapter adapter = new MySqlAutoMemoryAdapter(jdbc);
        AutoMemoryObservationService service = service(adapter);
        AutoMemoryScope scope = AutoMemoryScope.user(adapterOwner);

        AutoMemoryObservationOutcome.Applied first = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer concise labels", "conversation-1", "turn-1",
                        MemoryObservationKind.INFERRED))));
        assertEquals(AutoMemoryStatus.OBSERVED, first.memory().status());
        assertEquals(1, first.memory().evidenceCount());
        assertEquals(
                List.of("label-density"),
                adapter.findConsolidationCandidates(scope, 10).stream()
                        .map(AutoMemory::semanticKey)
                        .toList());
        assertTrue(adapter.findConsolidationCandidates(
                AutoMemoryScope.user(otherOwner), 10).isEmpty());

        AutoMemoryObservationOutcome.Applied duplicate = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer concise labels", "conversation-1", "turn-1",
                        MemoryObservationKind.INFERRED))));
        assertFalse(duplicate.evidenceAdded());
        assertEquals(1, duplicate.memory().evidenceCount());

        AutoMemoryObservationOutcome.Applied second = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer concise labels", "conversation-2", "turn-2",
                        MemoryObservationKind.INFERRED))));
        assertEquals(AutoMemoryStatus.ACTIVE, second.memory().status());
        assertEquals(2, second.memory().evidenceCount());

        List<AutoMemory> active = adapter.recallActive(scope, 10);
        assertEquals(1, active.size());
        assertTrue(adapter.recallActive(AutoMemoryScope.user(otherOwner), 10).isEmpty());

        AutoMemoryManagementOutcome.Updated disabled = assertInstanceOf(
                AutoMemoryManagementOutcome.Updated.class,
                transaction(() -> adapter.disable(
                        new AutoMemoryFence(
                                scope, active.get(0).memoryId(), active.get(0).version()),
                        NOW.plusSeconds(1))));
        assertEquals(AutoMemoryStatus.DISABLED, disabled.memory().status());
        assertEquals(
                AutoMemoryStatus.DISABLED,
                adapter.findConsolidationCandidates(scope, 10).get(0).status());

        AutoMemoryObservationOutcome.Suppressed suppressed = assertInstanceOf(
                AutoMemoryObservationOutcome.Suppressed.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer concise labels", "conversation-3", "turn-3",
                        MemoryObservationKind.INFERRED))));
        assertEquals(AutoMemoryStatus.DISABLED, suppressed.current().status());
        assertTrue(adapter.recallActive(scope, 10).isEmpty());
    }

    @Test
    void explicitChartbookMemoryIsActiveAndCannotCrossTheOwnerFence() {
        MySqlAutoMemoryAdapter adapter = new MySqlAutoMemoryAdapter(jdbc);
        AutoMemoryObservationService service = service(adapter);
        AutoMemoryScope scope = AutoMemoryScope.chartbook(adapterOwner, adapterChartbook);

        AutoMemoryObservationOutcome.Applied applied = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "project-naming", "Use event-based service names",
                        "conversation-4", "turn-4", MemoryObservationKind.EXPLICIT))));
        assertEquals(AutoMemoryStatus.ACTIVE, applied.memory().status());
        assertEquals(1.0d, applied.memory().confidence());
        assertEquals(1, adapter.recallActive(scope, 10).size());
        assertTrue(adapter.recallActive(AutoMemoryScope.user(adapterOwner), 10).isEmpty());

        AutoMemoryObservationOutcome.Rejected rejected = assertInstanceOf(
                AutoMemoryObservationOutcome.Rejected.class,
                transaction(() -> service.observe(observation(
                        AutoMemoryScope.chartbook(otherOwner, adapterChartbook),
                        "project-naming",
                        "Use another naming scheme",
                        "conversation-5",
                        "turn-5",
                        MemoryObservationKind.EXPLICIT))));
        assertEquals("AUTO_MEMORY_SCOPE_NOT_FOUND", rejected.code());
    }

    private AutoMemoryObservationService service(MySqlAutoMemoryAdapter adapter) {
        return new AutoMemoryObservationService(
                new org.zipp.ai.application.memory.MemoryPolicySanitizer(),
                new org.zipp.ai.application.memory.AutoMemoryActivationPolicy(),
                adapter,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AutoMemoryObservationCommand observation(
            AutoMemoryScope scope,
            String semanticKey,
            String canonicalText,
            String conversationId,
            String turnId,
            MemoryObservationKind kind
    ) {
        return new AutoMemoryObservationCommand(
                scope,
                AutoMemoryType.PREFERENCE,
                semanticKey,
                "Memory integration fixture",
                canonicalText,
                new TurnKey(scope.ownerKey(), conversationId, turnId),
                null,
                kind,
                0.8d);
    }

    private <T> T transaction(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private void insertChartbook(String chartbookId, String ownerKey) {
        jdbc.update("""
                INSERT INTO chartbook (id, owner_key, name, status)
                VALUES (?, ?, 'Auto Memory integration', 'ACTIVE')
                """, chartbookId, ownerKey);
    }

    private void insertLegacyMemory(String memoryId, String status, String canonicalText) {
        jdbc.update("""
                INSERT INTO chartbook_memory (
                    memory_id, owner_key, chartbook_id,
                    source_conversation_id, source_turn_id, source_diagram_id,
                    decision_key, applicability_stage, scope, canonical_text,
                    policy_version, declaration_digest, status, version
                ) VALUES (?, ?, ?, 'legacy-conversation', ?, 'legacy-diagram',
                          'legacy-decision', 'all', 'CHARTBOOK', ?,
                          'MEMORY_V1', ?, ?, 1)
                """,
                memoryId,
                legacyOwner,
                legacyChartbook,
                "legacy-turn-" + memoryId,
                canonicalText,
                "digest-" + memoryId,
                status);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private String text(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private void executeSql(DriverManagerDataSource dataSource, String relativePath)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(sqlPath(relativePath)));
        }
    }

    private Path sqlPath(String relativePath) {
        for (Path candidate : List.of(
                Path.of(relativePath),
                Path.of("..").resolve(relativePath))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("SQL resource not found: " + relativePath);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
