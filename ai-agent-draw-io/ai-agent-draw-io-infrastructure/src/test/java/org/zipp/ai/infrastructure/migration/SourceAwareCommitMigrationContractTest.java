package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceAwareCommitMigrationContractTest {

    @Test
    void migrationProvidesExactBindingsClarificationAndDirectProvenance() throws Exception {
        String sql = Files.readString(projectFile(
                "ai-agent-draw-io/docs/sql/migrations/"
                        + "2026-07-30-create-source-aware-commit-boundaries.sql"));

        for (String table : List.of(
                "turn_source_execution_binding",
                "turn_clarification",
                "turn_clarification_option",
                "diagram_visual_provenance",
                "direct_source_usage_pin")) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table), table);
        }
        assertTrue(sql.contains("snapshot_binding_digest CHAR(64) NOT NULL"));
        assertTrue(sql.contains("execution_entry_id"));
        assertTrue(sql.contains("uk_turn_clarification_authority"));
        assertTrue(sql.contains("FOREIGN KEY (owner_key, conversation_id, turn_id)"));
        assertTrue(sql.contains("FOREIGN KEY (diagram_id) REFERENCES diagram (id)"));
    }

    @Test
    void migrationIsPackagedAsAnImmutableOrderedRelease() throws Exception {
        List<String> entries = Files.readAllLines(projectFile(
                        "deploy/aws/database/release-20260730.manifest")).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertEquals(List.of(
                "2026-07-30-create-source-aware-commit-boundaries.sql"), entries);
        String dockerfile = Files.readString(projectFile(
                "deploy/aws/database/Dockerfile.20260730"));
        assertTrue(dockerfile.contains("MIGRATION_RELEASE=20260730"));
        assertTrue(dockerfile.contains("PREDECESSOR_RELEASE=20260729"));
        assertTrue(dockerfile.contains("EXPECTED_PREDECESSOR_COUNT=3"));
    }

    private Path projectFile(String relativePath) {
        for (Path base : List.of(Path.of(""), Path.of("../"), Path.of("../../"))) {
            Path path = base.resolve(relativePath).normalize();
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("project file is missing: " + relativePath);
    }
}
