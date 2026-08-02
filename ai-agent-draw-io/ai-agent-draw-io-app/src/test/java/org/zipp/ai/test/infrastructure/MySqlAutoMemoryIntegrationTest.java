package org.zipp.ai.test.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.zipp.ai.application.memory.AutoMemoryConsolidationQuery;
import org.zipp.ai.application.memory.AutoMemoryExtractionCandidate;
import org.zipp.ai.application.memory.AutoMemoryFence;
import org.zipp.ai.application.memory.AutoMemoryManagementOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationCommand;
import org.zipp.ai.application.memory.AutoMemoryObservationOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionLease;
import org.zipp.ai.application.memory.MemoryObservationKind;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.context.AutoMemoryContextQuery;
import org.zipp.ai.infrastructure.adapter.repository.MySqlAutoMemoryAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlAutoMemoryVectorProjectionWorkAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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
    private final String agingOwner = "auto-memory-aging-" + suffix;
    private final String conflictOwner = "auto-memory-conflict-" + suffix;
    private final String contextOwner = "auto-memory-context-" + suffix;
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
        if (count("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'memory_item'
                  AND index_name = 'idx_memory_item_aging'
                """) == 0) {
            // The release runner owns idempotence; this direct fixture may reuse a local schema.
            executeSql(
                    dataSource,
                    "docs/sql/migrations/2026-08-15-add-auto-memory-aging-index.sql");
        }
        // CREATE/INSERT IGNORE makes the vector outbox migration safe for reused local fixtures.
        executeSql(
                dataSource,
                "docs/sql/migrations/2026-08-16-create-auto-memory-vector-outbox.sql");
        schemaReady = true;
    }

    @AfterAll
    void removeSuiteFixtures() {
        if (!schemaReady || jdbc == null) {
            return;
        }
        jdbc.update("""
                DELETE w
                FROM memory_vector_projection_work w
                JOIN memory_item m ON m.memory_id = w.memory_id
                WHERE m.owner_key IN (?, ?, ?, ?, ?)
                """, legacyOwner, adapterOwner, agingOwner, conflictOwner, contextOwner);
        // Evidence is deleted by the Item foreign key; released schema remains for inspection.
        jdbc.update("DELETE FROM memory_item WHERE owner_key IN (?, ?, ?, ?, ?)",
                legacyOwner, adapterOwner, agingOwner, conflictOwner, contextOwner);
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
        assertEquals(2, count("""
                SELECT COUNT(*)
                FROM memory_vector_projection_work w
                JOIN memory_item m ON m.memory_id = w.memory_id
                WHERE m.owner_key = ?
                """, legacyOwner));

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
        MySqlAutoMemoryVectorProjectionWorkAdapter vectorWork =
                new MySqlAutoMemoryVectorProjectionWorkAdapter(jdbc, new ObjectMapper());
        MySqlAutoMemoryAdapter adapter = new MySqlAutoMemoryAdapter(jdbc, vectorWork);
        AutoMemoryObservationService service = service(adapter);
        AutoMemoryScope scope = AutoMemoryScope.user(adapterOwner);
        Instant projectionNow = Instant.now();
        String projectionWorker = "integration-worker-" + suffix;

        AutoMemoryObservationOutcome.Applied first = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer concise labels", "conversation-1", "turn-1",
                        MemoryObservationKind.INFERRED))));
        assertEquals(AutoMemoryStatus.OBSERVED, first.memory().status());
        assertEquals(1, first.memory().evidenceCount());
        assertEquals(1, count("""
                SELECT desired_revision
                FROM memory_vector_projection_work
                WHERE memory_id = ?
                """, first.memory().memoryId()));
        // A reused developer schema can contain unrelated backfill work; prioritize only this
        // random fixture without mutating or claiming another owner's queue item.
        jdbc.update("""
                UPDATE memory_vector_projection_work
                SET available_at = ?
                WHERE memory_id = ?
                """, Timestamp.from(Instant.EPOCH), first.memory().memoryId());
        AutoMemoryVectorProjectionLease firstLease = transaction(() -> vectorWork.claim(
                        projectionWorker, projectionNow, Duration.ofMinutes(2)))
                .orElseThrow();
        assertEquals(1, firstLease.desiredRevision());
        assertEquals(AutoMemoryVectorDocument.CandidateState.OBSERVED,
                firstLease.documents().get(0).state());
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
        assertEquals(1, count("""
                SELECT desired_revision
                FROM memory_vector_projection_work
                WHERE memory_id = ?
                """, first.memory().memoryId()));

        AutoMemoryObservationOutcome.Applied second = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer concise labels", "conversation-2", "turn-2",
                        MemoryObservationKind.INFERRED))));
        assertEquals(AutoMemoryStatus.ACTIVE, second.memory().status());
        assertEquals(2, second.memory().evidenceCount());
        assertEquals(2, count("""
                SELECT desired_revision
                FROM memory_vector_projection_work
                WHERE memory_id = ?
                """, second.memory().memoryId()));
        assertFalse(transaction(() -> vectorWork.complete(
                firstLease, projectionNow.plusSeconds(1))));
        jdbc.update("""
                UPDATE memory_vector_projection_work
                SET available_at = ?
                WHERE memory_id = ?
                """, Timestamp.from(Instant.EPOCH), second.memory().memoryId());
        AutoMemoryVectorProjectionLease secondLease = transaction(() -> vectorWork.claim(
                        projectionWorker, projectionNow.plusSeconds(2), Duration.ofMinutes(2)))
                .orElseThrow();
        assertEquals(2, secondLease.desiredRevision());
        assertEquals(AutoMemoryVectorDocument.CandidateState.ACTIVE,
                secondLease.documents().get(0).state());
        assertTrue(transaction(() -> vectorWork.complete(
                secondLease, projectionNow.plusSeconds(3))));

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
        List<AutoMemoryExtractionCandidate> hydratedDisabled = adapter.hydrate(
                new AutoMemoryConsolidationQuery(
                        new TurnKey(adapterOwner, "hydration-conversation", "hydration-turn"),
                        null,
                        "Keep labels concise",
                        16),
                List.of(AutoMemoryVectorDocument.current(
                        disabled.memory(), disabled.memory().version()).vectorId()));
        assertEquals(1, hydratedDisabled.size());
        assertEquals(AutoMemoryStatus.DISABLED, hydratedDisabled.get(0).status());
        assertEquals(3, count("""
                SELECT desired_revision
                FROM memory_vector_projection_work
                WHERE memory_id = ?
                """, second.memory().memoryId()));

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

    @Test
    void generationContextHydrationAcceptsOnlyActiveCurrentVectorsWithinScope() {
        MySqlAutoMemoryAdapter adapter = new MySqlAutoMemoryAdapter(jdbc);
        AutoMemoryScope scope = AutoMemoryScope.user(contextOwner);
        AutoMemory active = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service(adapter).observe(observation(
                        scope, "context-labels", "Prefer concise context labels",
                        "context-conversation", "context-turn", MemoryObservationKind.EXPLICIT))))
                .memory();
        AutoMemoryContextQuery query = new AutoMemoryContextQuery(
                new TurnKey(contextOwner, "context-query-conversation", "context-query-turn"),
                null,
                "Add a concise label");
        String currentVectorId = AutoMemoryVectorDocument.current(active, 1).vectorId();
        String challengerVectorId = AutoMemoryVectorDocument.challenger(
                active.memoryId(), active.scope(), active.title(), "Prefer detailed labels", 1)
                .vectorId();

        assertEquals(List.of(active.memoryId()),
                adapter.hydrateActiveVectorMatches(
                                query, List.of(challengerVectorId, currentVectorId, "unknown"))
                        .stream().map(AutoMemory::memoryId).toList());
        assertEquals(List.of(active.memoryId()),
                adapter.loadActive(query, List.of(active.memoryId()))
                        .stream().map(AutoMemory::memoryId).toList());
        assertTrue(adapter.loadActive(
                new AutoMemoryContextQuery(
                        new TurnKey(otherOwner, "context-query-conversation", "context-query-turn"),
                        null,
                        "Add a concise label"),
                List.of(active.memoryId())).isEmpty());

        transaction(() -> adapter.disable(
                new AutoMemoryFence(scope, active.memoryId(), active.version()), NOW.plusSeconds(1)));
        assertTrue(adapter.hydrateActiveVectorMatches(query, List.of(currentVectorId)).isEmpty());
        assertTrue(adapter.loadActive(query, List.of(active.memoryId())).isEmpty());
    }

    @Test
    void inferredChallengerNeedsTwoTurnsAndCannotOverrideExplicitMemory() {
        MySqlAutoMemoryAdapter adapter = new MySqlAutoMemoryAdapter(jdbc);
        AutoMemoryObservationService service = service(adapter);
        AutoMemoryScope scope = AutoMemoryScope.user(conflictOwner);

        transaction(() -> service.observe(observation(
                scope, "label-density", "Prefer concise labels", "conflict-conversation-1",
                "conflict-turn-1", MemoryObservationKind.INFERRED)));
        AutoMemory active = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer concise labels", "conflict-conversation-2",
                        "conflict-turn-2", MemoryObservationKind.INFERRED))))
                .memory();
        assertEquals(AutoMemoryStatus.ACTIVE, active.status());

        assertInstanceOf(
                AutoMemoryObservationOutcome.Conflict.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer detailed labels", "conflict-conversation-3",
                        "conflict-turn-3", MemoryObservationKind.INFERRED))));
        assertEquals("Prefer concise labels", adapter.recallActive(scope, 10).get(0).canonicalText());

        String challengerVectorId = AutoMemoryVectorDocument.challenger(
                active.memoryId(), active.scope(), active.title(),
                "Prefer detailed labels", active.version()).vectorId();
        AutoMemoryConsolidationQuery hydrationQuery = new AutoMemoryConsolidationQuery(
                new TurnKey(conflictOwner, "hydration-conversation", "hydration-turn"),
                null,
                "Keep labels detailed",
                16);
        List<AutoMemoryExtractionCandidate> hydrated = adapter.hydrate(
                hydrationQuery, List.of(challengerVectorId, "provider-owned-id"));
        assertEquals(1, hydrated.size());
        // A challenger hit identifies the parent semantic key; only MySQL current text is exposed.
        assertEquals("label-density", hydrated.get(0).semanticKey());
        assertEquals("Prefer concise labels", hydrated.get(0).canonicalText());
        assertTrue(adapter.hydrate(
                new AutoMemoryConsolidationQuery(
                        new TurnKey(otherOwner, "hydration-conversation", "hydration-turn"),
                        null,
                        "Keep labels detailed",
                        16),
                List.of(challengerVectorId)).isEmpty());

        AutoMemory promoted = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "label-density", "Prefer detailed labels", "conflict-conversation-4",
                        "conflict-turn-4", MemoryObservationKind.INFERRED))))
                .memory();
        assertEquals("Prefer detailed labels", promoted.canonicalText());
        assertEquals(2, promoted.evidenceCount());
        assertFalse(promoted.explicit());
        assertEquals(2, count("""
                SELECT COUNT(*) FROM memory_evidence
                WHERE memory_id = ? AND disposition = 'SUPPORTING'
                  AND observed_text = 'Prefer detailed labels'
                """, promoted.memoryId()));
        assertEquals(2, count("""
                SELECT COUNT(*) FROM memory_evidence
                WHERE memory_id = ? AND disposition = 'SUPERSEDED'
                  AND observed_text = 'Prefer concise labels'
                """, promoted.memoryId()));
        // Once promoted, the old challenger ID is stale; the authoritative current ID remains valid.
        assertTrue(adapter.hydrate(hydrationQuery, List.of(challengerVectorId)).isEmpty());
        List<AutoMemoryExtractionCandidate> hydratedCurrent = adapter.hydrate(
                hydrationQuery,
                List.of(AutoMemoryVectorDocument.current(
                        promoted, promoted.version()).vectorId()));
        assertEquals(1, hydratedCurrent.size());
        assertEquals("Prefer detailed labels", hydratedCurrent.get(0).canonicalText());

        AutoMemory explicit = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "decorative-icons", "Avoid decorative icons", "conflict-conversation-5",
                        "conflict-turn-5", MemoryObservationKind.EXPLICIT))))
                .memory();
        assertInstanceOf(
                AutoMemoryObservationOutcome.Conflict.class,
                transaction(() -> service.observe(observation(
                        scope, "decorative-icons", "Prefer decorative icons", "conflict-conversation-6",
                        "conflict-turn-6", MemoryObservationKind.INFERRED))));
        assertInstanceOf(
                AutoMemoryObservationOutcome.Conflict.class,
                transaction(() -> service.observe(observation(
                        scope, "decorative-icons", "Prefer decorative icons", "conflict-conversation-7",
                        "conflict-turn-7", MemoryObservationKind.INFERRED))));

        AutoMemory protectedExplicit = adapter.list(scope, true, true).stream()
                .filter(memory -> memory.memoryId().equals(explicit.memoryId()))
                .findFirst()
                .orElseThrow();
        assertEquals("Avoid decorative icons", protectedExplicit.canonicalText());
        assertTrue(protectedExplicit.explicit());
        assertEquals(2, count("""
                SELECT COUNT(*) FROM memory_evidence
                WHERE memory_id = ? AND disposition = 'CONFLICTING'
                """, explicit.memoryId()));
    }

    @Test
    void agingPurgesOnlyStaleUnconfirmedObservationsInBoundedBatches() {
        MySqlAutoMemoryAdapter adapter = new MySqlAutoMemoryAdapter(jdbc);
        AutoMemoryObservationService service = service(adapter);
        // Keep aging fixtures on their own owner so JUnit method order cannot leak state.
        AutoMemoryScope scope = AutoMemoryScope.user(agingOwner);

        AutoMemory oldA = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "aging-old-a", "Prefer compact groups", "aging-conversation-1",
                        "aging-turn-1", MemoryObservationKind.INFERRED))))
                .memory();
        AutoMemory oldB = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "aging-old-b", "Prefer compact boundaries", "aging-conversation-2",
                        "aging-turn-2", MemoryObservationKind.INFERRED))))
                .memory();
        transaction(() -> service.observe(observation(
                scope, "aging-recent", "Prefer recent labels", "aging-conversation-3",
                "aging-turn-3", MemoryObservationKind.INFERRED)));
        AutoMemory active = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "aging-active", "Prefer active labels", "aging-conversation-4",
                        "aging-turn-4", MemoryObservationKind.EXPLICIT))))
                .memory();
        AutoMemory disabledSource = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                transaction(() -> service.observe(observation(
                        scope, "aging-disabled", "Avoid disabled icons", "aging-conversation-5",
                        "aging-turn-5", MemoryObservationKind.EXPLICIT))))
                .memory();
        transaction(() -> adapter.disable(
                new AutoMemoryFence(scope, disabledSource.memoryId(), disabledSource.version()),
                NOW));

        // A historical test-only cutoff prevents this gated suite from matching developer rows.
        Instant old = Instant.parse("2001-01-01T00:00:00Z");
        jdbc.update("""
                UPDATE memory_item
                SET updated_at = ?
                WHERE owner_key = ? AND semantic_key IN (
                    'aging-old-a', 'aging-old-b', 'aging-active', 'aging-disabled')
                """, Timestamp.from(old), agingOwner);

        Instant cutoff = Instant.parse("2002-01-01T00:00:00Z");
        assertEquals(1, adapter.purgeStaleObserved(cutoff, 1));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM memory_item
                WHERE owner_key = ? AND semantic_key IN ('aging-old-a', 'aging-old-b')
                """, agingOwner));
        assertEquals(1, adapter.purgeStaleObserved(cutoff, 1));

        List<String> remaining = adapter.list(scope, true, true).stream()
                .map(AutoMemory::semanticKey)
                .toList();
        assertEquals(0, count("""
                SELECT COUNT(*) FROM memory_evidence WHERE memory_id IN (?, ?)
                """, oldA.memoryId(), oldB.memoryId()));
        assertFalse(remaining.contains("aging-old-a"));
        assertFalse(remaining.contains("aging-old-b"));
        assertTrue(remaining.contains("aging-recent"));
        assertTrue(remaining.contains(active.semanticKey()));
        assertTrue(remaining.contains(disabledSource.semanticKey()));
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
