package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryVectorOutboxMigrationTest {
    @Test
    void successorAddsOnlyRebuildableProjectionWork() throws Exception {
        Path migration = Path.of(
                "docs/sql/migrations/2026-08-16-create-auto-memory-vector-outbox.sql");
        if (!Files.exists(migration)) {
            migration = Path.of(
                    "../docs/sql/migrations/2026-08-16-create-auto-memory-vector-outbox.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS memory_vector_projection_work"));
        assertTrue(sql.contains("desired_revision"));
        assertTrue(sql.contains("projected_vector_ids"));
        assertTrue(sql.contains("idx_memory_vector_projection_claim"));
        assertTrue(sql.contains("INSERT IGNORE INTO memory_vector_projection_work"));
        assertTrue(sql.contains("FROM memory_item"));
        assertFalse(sql.contains("FOREIGN KEY"));
        assertFalse(sql.contains("DELETE FROM memory_item"));
        assertFalse(sql.contains("DROP "));
    }

    @Test
    void releaseRequiresTheImmutableAgingPredecessor() throws Exception {
        Path manifest = Path.of("../../deploy/aws/database/release-20260816.manifest");
        Path dockerfile = Path.of("../../deploy/aws/database/Dockerfile.20260816");
        String release = Files.readString(manifest);
        String image = Files.readString(dockerfile);

        assertTrue(release.contains("2026-08-16-create-auto-memory-vector-outbox.sql"));
        assertTrue(image.contains("PREDECESSOR_RELEASE=20260815"));
        assertTrue(image.contains("EXPECTED_PREDECESSOR_COUNT=1"));
        assertTrue(image.contains("MIGRATION_RELEASE=20260816"));
    }
}
