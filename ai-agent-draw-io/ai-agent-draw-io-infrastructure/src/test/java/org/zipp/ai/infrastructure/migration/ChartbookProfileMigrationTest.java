package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartbookProfileMigrationTest {
    @Test
    void migrationCreatesIndependentVersionedProfileStorageAndBackfillsEmptyRows() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-28-create-chartbook-profile.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-28-create-chartbook-profile.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS chartbook_profile "));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS chartbook_profile_version "));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS chartbook_profile_audit "));
        assertTrue(sql.contains("UNIQUE KEY uk_chartbook_profile_audit_request"));
        assertTrue(sql.contains("INSERT IGNORE INTO chartbook_profile"));
        assertTrue(sql.contains("INSERT IGNORE INTO chartbook_profile_version"));
        assertFalse(sql.contains("preferences_json"));
    }
}
