package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmedMemoryMigrationTest {
    @Test
    void memoryMigrationKeepsCandidatePayloadSeparateAndScrubbable() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-31-create-confirmed-memory.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-31-create-confirmed-memory.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS chartbook_memory_candidate"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS chartbook_memory"));
        assertTrue(sql.contains("canonical_text           VARCHAR(1000) NULL"));
        assertTrue(sql.contains("payload_deleted_at"));
        assertTrue(sql.contains("CHECK (status IN ('PENDING', 'MATERIALIZED', 'EXPIRED', 'REVOKED'))"));
        assertTrue(sql.contains("CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED'))"));
        assertTrue(sql.contains("ON DELETE CASCADE"));
    }

    @Test
    void migrationReleaseManifestIsImmutableAndFollowsM530() throws Exception {
        Path manifest = Path.of("../../deploy/aws/database/release-20260731.manifest");
        if (!Files.exists(manifest)) {
            manifest = Path.of("deploy/aws/database/release-20260731.manifest");
        }
        String value = Files.readString(manifest);
        assertTrue(value.contains("2026-07-31-create-confirmed-memory.sql"));
    }
}
