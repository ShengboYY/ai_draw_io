package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnExecutionControlMigrationContractTest {

    @Test
    void existingM1MigrationProvidesImmutableContextAndCheckpointColumns() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-26-create-turn-execution-control.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-26-create-turn-execution-control.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("context_message_high_water"));
        assertTrue(sql.contains("context_read_set_schema_version"));
        assertTrue(sql.contains("context_read_set_json"));
        assertTrue(sql.contains("context_read_set_digest"));
        assertTrue(sql.contains("context_read_set_pinned_at"));
        assertTrue(sql.contains("plan_payload_schema_version"));
        assertTrue(sql.contains("plan_payload_json"));
        assertTrue(sql.contains("plan_payload_digest"));
        assertTrue(sql.contains("plan_pinned_at"));
    }

    @Test
    void recoveryMigrationAddsPinnedInputPayloadWithoutReplacingTheDigest() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-27-add-turn-input-binding-payload.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-27-add-turn-input-binding-payload.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("turn_input_binding_json JSON NULL"));
        assertTrue(sql.contains("AFTER turn_input_binding_digest"));
        assertTrue(sql.contains("information_schema.COLUMNS"));
    }

    @Test
    void lifecycleTraceMigrationStoresOnlyRedactedEvidenceColumns() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-28-create-turn-lifecycle-trace.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-28-create-turn-lifecycle-trace.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS turn_lifecycle_trace"));
        assertTrue(sql.contains("policy_hash"));
        assertTrue(sql.contains("input_binding_digest"));
        assertTrue(sql.contains("decision_digest"));
        assertTrue(sql.contains("outcome_status"));
        assertTrue(!sql.contains("user_message"));
        assertTrue(!sql.contains("terminal_payload_json"));
    }

    @Test
    void m1MigrationsArePackagedInAnOrderedProductionRelease() throws Exception {
        Path manifest = projectFile("deploy/aws/database/release-20260729.manifest");
        List<String> entries = Files.readAllLines(manifest).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertEquals(List.of(
                "2026-07-26-create-turn-execution-control.sql",
                "2026-07-27-add-turn-input-binding-payload.sql",
                "2026-07-28-create-turn-lifecycle-trace.sql"), entries);
        assertTrue(Files.readString(projectFile("deploy/aws/database/Dockerfile.20260729"))
                .contains("EXPECTED_PREDECESSOR_COUNT=5"));
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
