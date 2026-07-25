package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
