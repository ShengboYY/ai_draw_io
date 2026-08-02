package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryMigrationTest {
    @Test
    void migrationCreatesUnifiedScopedItemsAndBoundedEvidence() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-14-create-auto-memory.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-14-create-auto-memory.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS memory_item"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS memory_evidence"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS memory_extraction_work"));
        assertTrue(sql.contains("scope_type IN ('USER', 'CHARTBOOK')"));
        assertTrue(sql.contains("status IN ('OBSERVED', 'ACTIVE', 'DISABLED', 'DELETED')"));
        assertTrue(sql.contains("uk_memory_item_semantic"));
        assertTrue(sql.contains("uk_memory_evidence_turn"));
        assertTrue(sql.contains("uk_memory_extraction_turn"));
        assertTrue(sql.contains("INSERT IGNORE INTO memory_item"));
        assertTrue(sql.contains("FROM chartbook_memory"));
        assertFalse(sql.contains("DROP TABLE"));
    }

    @Test
    void migrationHasAnImmutableSuccessorRelease() throws Exception {
        Path manifest = Path.of("../../deploy/aws/database/release-20260814.manifest");
        if (!Files.exists(manifest)) {
            manifest = Path.of("deploy/aws/database/release-20260814.manifest");
        }
        String value = Files.readString(manifest);

        assertTrue(value.contains("2026-08-14-create-auto-memory.sql"));
    }
}
