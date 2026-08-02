package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryAgingMigrationTest {
    @Test
    void successorAddsOnlyTheBoundedMaintenanceIndex() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-15-add-auto-memory-aging-index.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-15-add-auto-memory-aging-index.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("ALTER TABLE memory_item"));
        assertTrue(sql.contains(
                "idx_memory_item_aging (status, is_explicit, updated_at, memory_id)"));
        assertFalse(sql.contains("UPDATE memory_item"));
        assertFalse(sql.contains("DELETE FROM memory_item"));
        assertFalse(sql.contains("DROP "));
    }

    @Test
    void releaseRequiresTheImmutableAutoMemoryPredecessor() throws Exception {
        Path manifest = Path.of("../../deploy/aws/database/release-20260815.manifest");
        Path dockerfile = Path.of("../../deploy/aws/database/Dockerfile.20260815");
        String release = Files.readString(manifest);
        String image = Files.readString(dockerfile);

        assertTrue(release.contains("2026-08-15-add-auto-memory-aging-index.sql"));
        assertTrue(image.contains("PREDECESSOR_RELEASE=20260814"));
        assertTrue(image.contains("EXPECTED_PREDECESSOR_COUNT=1"));
        assertTrue(image.contains("MIGRATION_RELEASE=20260815"));
    }
}
